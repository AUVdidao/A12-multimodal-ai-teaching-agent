package generation

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"lessonforge.local/backend/internal/auth"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/platform/database"
	"lessonforge.local/backend/internal/platform/httpapi"
	"lessonforge.local/backend/internal/platform/storage"
	"lessonforge.local/backend/internal/pptengine"
)

type workerIntegrationFixture struct {
	store   *database.Store
	ctx     context.Context
	ownerID int64
	jobID   string
	server  *httptest.Server
	root    string
	data    []byte
}

func newWorkerIntegrationFixture(t *testing.T) workerIntegrationFixture {
	t.Helper()
	dsn := os.Getenv("LESSONFORGE_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("LESSONFORGE_TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	pool, err := database.Open(ctx, dsn)
	if err != nil {
		t.Fatalf("open integration database: %v", err)
	}
	t.Cleanup(pool.Close)
	if err := database.Migrate(ctx, pool, filepath.Join("..", "..", "migrations")); err != nil {
		t.Fatalf("migrate integration database: %v", err)
	}
	unlock, err := database.AcquireIntegrationTestLock(ctx, pool)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(unlock)
	store := database.NewStore(pool)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Generation worker", "generation-worker-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	connection, err := store.CreateConnection(ctx, owner.ID, model.ModelConnection{Name: "Generation connection " + suffix, Protocol: "OPENAI_COMPATIBLE", BaseURL: "http://127.0.0.1:1", ModelID: "test-model", KeyHint: "test"}, "test-encrypted-key")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE model_connections SET enabled=true,verification_status='VERIFIED' WHERE id=$1`, connection.ID); err != nil {
		t.Fatal(err)
	}
	var missionID int64
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title,selected_model_connection_id) VALUES($1,'SELF_CREATED','Generation worker test',$2) RETURNING id`, owner.ID, connection.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	var messageID int64
	if err := pool.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','start') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_connection_id,model_identity_snapshot,status) VALUES($1,$2,$3,$4,'{}'::jsonb,'COMPLETED')`, runID, missionID, messageID, connection.ID); err != nil {
		t.Fatal(err)
	}
	draftID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id,output_stage) VALUES($1,$2,1,'test','{"slides":[]}'::jsonb,$3,'PLAN_DRAFT')`, draftID, missionID, runID); err != nil {
		t.Fatal(err)
	}
	specID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO locked_specifications(id,mission_id,source_draft_id,version,specification_json,template_binding_json,content_hash) VALUES($1,$2,$3,1,'{"slides":[]}'::jsonb,'{}'::jsonb,$4)`, specID, missionID, draftID, "1111111111111111111111111111111111111111111111111111111111111111"); err != nil {
		t.Fatal(err)
	}
	jobID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO generation_jobs(id,mission_id,specification_id,status) VALUES($1,$2,$3,'QUEUED')`, jobID, missionID, specID); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	data := []byte("physical generation worker artifact")
	hash := sha256.Sum256(data)
	artifactPath := filepath.Join(root, "run", "result.pptx")
	if err := os.MkdirAll(filepath.Dir(artifactPath), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(artifactPath, data, 0o640); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if _, err := w.Write([]byte(`{"status":"SUCCEEDED","artifacts":[{"artifactId":"engine-1","artifactType":"PPTX","storageKey":"run/result.pptx","sha256":"` + hex.EncodeToString(hash[:]) + `","fileSize":` + strconv.Itoa(len(data)) + `,"mediaType":"application/vnd.openxmlformats-officedocument.presentationml.presentation"}]}`)); err != nil {
			t.Errorf("engine fixture response: %v", err)
		}
	}))
	t.Cleanup(server.Close)
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		for _, cleanup := range []struct {
			name  string
			query string
			arg   any
		}{
			{"execution audits", `DELETE FROM execution_audits WHERE actor_user_id=$1`, owner.ID},
			{"mission", `DELETE FROM missions WHERE id=$1`, missionID},
			{"file objects", `DELETE FROM file_objects WHERE owner_user_id=$1`, owner.ID},
			{"model connections", `DELETE FROM model_connections WHERE owner_user_id=$1`, owner.ID},
			{"user", `DELETE FROM users WHERE id=$1`, owner.ID},
		} {
			if _, err := pool.Exec(cleanupCtx, cleanup.query, cleanup.arg); err != nil {
				t.Errorf("cleanup %s: %v", cleanup.name, err)
			}
		}
		var remaining int
		if err := pool.QueryRow(cleanupCtx, `SELECT count(*) FROM users WHERE id=$1`, owner.ID).Scan(&remaining); err != nil || remaining != 0 {
			t.Errorf("cleanup residual user %d: %v", remaining, err)
		}
	})
	return workerIntegrationFixture{store: store, ctx: ctx, ownerID: owner.ID, jobID: jobID, server: server, root: root, data: data}
}

func newGenerationWorker(f workerIntegrationFixture) (*Worker, *storage.Service) {
	files, err := storage.New(filepath.Join(f.root, "stored"), 200*1024*1024)
	if err != nil {
		panic(err)
	}
	return &Worker{Store: f.store, Engine: pptengine.NewClient(f.server.URL, f.root, time.Second), Storage: files, OwnerResolver: func(context.Context, int64) (int64, error) { return f.ownerID, nil }}, files
}

func TestGenerationWorkerCommitAmbiguityUsesPhysicalFileReconciliation(t *testing.T) {
	f := newWorkerIntegrationFixture(t)
	worker, files := newGenerationWorker(f)
	worker.commitArtifact = func(ctx context.Context, owner, missionID int64, jobID, token string, file model.FileObject, contentType string, feedback any) (model.Artifact, error) {
		artifact, err := f.store.CommitGenerationArtifact(ctx, owner, missionID, jobID, token, file, contentType, feedback)
		if err != nil {
			return model.Artifact{}, err
		}
		return artifact, errors.New("DATABASE_COMMIT_AMBIGUOUS")
	}
	worker.RunOnce(f.ctx)
	artifactKey := generationArtifactStorageKey(t, f.store, f.jobID)
	var artifacts, filesCount int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE generation_job_id=$1`, f.jobID).Scan(&artifacts); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, artifactKey).Scan(&filesCount); err != nil {
		t.Fatal(err)
	}
	if artifacts != 1 || filesCount != 1 {
		t.Fatalf("ambiguous worker rows = artifacts %d files %d", artifacts, filesCount)
	}
	stored, err := files.Open(f.ctx, artifactKey)
	if err != nil {
		t.Fatalf("ambiguous commit removed referenced physical file: %v", err)
	}
	if err := stored.Close(); err != nil {
		t.Fatal(err)
	}
	artifact, err := f.store.Artifacts(f.ctx, f.ownerID, mustMissionID(t, f.store, f.jobID))
	if err != nil || len(artifact) != 1 {
		t.Fatalf("artifact reconciliation = %d/%v", len(artifact), err)
	}
	if err := files.Verify(f.ctx, storage.File{StorageKey: artifact[0].File.StorageKey, Size: artifact[0].File.Size, SHA256: artifact[0].File.SHA256}); err != nil {
		t.Fatalf("DB metadata and physical file diverged: %v", err)
	}
}

func TestGenerationWorkerUnknownCommitRetainsThenRetriesStableFile(t *testing.T) {
	f := newWorkerIntegrationFixture(t)
	worker, files := newGenerationWorker(f)
	claimed := make(chan model.GenerationJob, 1)
	f.store.SetGenerationClaimHooksForTest(nil, func(job model.GenerationJob) { claimed <- job })
	defer f.store.SetGenerationClaimHooksForTest(nil, nil)
	worker.commitArtifact = func(context.Context, int64, int64, string, string, model.FileObject, string, any) (model.Artifact, error) {
		return model.Artifact{}, database.ErrCommitAmbiguous
	}
	worker.RunOnce(f.ctx)
	job := <-claimed
	key := generationStorageKey(f.jobID, job.LeaseToken)
	staged, err := files.Open(f.ctx, key)
	if err != nil {
		t.Fatalf("unknown commit result removed staged file: %v", err)
	}
	if err := staged.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE generation_jobs SET status='QUEUED',lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$1`, f.jobID); err != nil {
		t.Fatal(err)
	}
	worker.commitArtifact = nil
	worker.RunOnce(f.ctx)
	var count int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE generation_job_id=$1`, f.jobID).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("stable-file retry artifact count = %d", count)
	}
}

func TestGenerationWorkerConfirmedUncommittedTerminalJobCleansPhysicalFile(t *testing.T) {
	f := newWorkerIntegrationFixture(t)
	worker, files := newGenerationWorker(f)
	claimed := make(chan model.GenerationJob, 1)
	f.store.SetGenerationClaimHooksForTest(nil, func(job model.GenerationJob) { claimed <- job })
	defer f.store.SetGenerationClaimHooksForTest(nil, nil)
	worker.commitArtifact = func(ctx context.Context, _ int64, _ int64, jobID, _ string, _ model.FileObject, _ string, _ any) (model.Artifact, error) {
		if _, err := f.store.DB.Exec(ctx, `UPDATE generation_jobs SET status='FAILED',lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$1`, jobID); err != nil {
			return model.Artifact{}, err
		}
		return model.Artifact{}, errors.New("GENERATION_EXECUTION_FENCE_LOST")
	}
	worker.RunOnce(f.ctx)
	job := <-claimed
	if _, err := files.Open(f.ctx, generationStorageKey(f.jobID, job.LeaseToken)); !os.IsNotExist(err) {
		t.Fatalf("confirmed uncommitted file cleanup error = %v", err)
	}
}

func TestGenerationWorkerPostCommitVerificationFailureFailsClosed(t *testing.T) {
	for _, test := range []struct {
		name            string
		breakFile       func(string) error
		breakAfterReady bool
	}{
		{name: "missing-before-finalize", breakFile: os.Remove},
		{name: "corrupt-before-finalize", breakFile: func(path string) error { return os.WriteFile(path, []byte("corrupt-after-db-commit"), 0o640) }},
		{name: "missing-after-ready", breakFile: os.Remove, breakAfterReady: true},
		{name: "replace-after-ready", breakFile: func(path string) error { return os.WriteFile(path, []byte("replacement-after-ready"), 0o640) }, breakAfterReady: true},
		{name: "truncated-after-ready", breakFile: func(path string) error { return os.WriteFile(path, ftruncatePayload(), 0o640) }, breakAfterReady: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			f := newWorkerIntegrationFixture(t)
			worker, files := newGenerationWorker(f)
			claimed := make(chan model.GenerationJob, 1)
			f.store.SetGenerationClaimHooksForTest(nil, func(job model.GenerationJob) { claimed <- job })
			defer f.store.SetGenerationClaimHooksForTest(nil, nil)
			if test.breakAfterReady {
				worker.afterArtifactFinalizeHook = func(file storage.File) error {
					return test.breakFile(filepath.Join(f.root, "stored", filepath.FromSlash(file.StorageKey)))
				}
			} else {
				worker.afterArtifactCommitHook = func(file storage.File) error {
					return test.breakFile(filepath.Join(f.root, "stored", filepath.FromSlash(file.StorageKey)))
				}
			}
			worker.RunOnce(f.ctx)
			job := <-claimed
			artifactKey := generationStorageKey(f.jobID, job.LeaseToken)
			var status string
			if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM generation_jobs WHERE id=$1`, f.jobID).Scan(&status); err != nil {
				t.Fatal(err)
			}
			if status != "FAILED" {
				t.Fatalf("post-commit verification status = %s, want FAILED", status)
			}
			var artifacts, fileObjects, readyActivities, invalidActivities int
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE generation_job_id=$1`, f.jobID).Scan(&artifacts); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, artifactKey).Scan(&fileObjects); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=(SELECT mission_id FROM generation_jobs WHERE id=$1) AND event_type='ARTIFACT_READY'`, f.jobID).Scan(&readyActivities); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=(SELECT mission_id FROM generation_jobs WHERE id=$1) AND event_type='ARTIFACT_INVALID'`, f.jobID).Scan(&invalidActivities); err != nil {
				t.Fatal(err)
			}
			if artifacts != 0 || fileObjects != 0 || readyActivities != 0 || invalidActivities != 1 {
				t.Fatalf("post-commit verification reconciliation = artifacts %d file_objects %d ready %d invalid %d", artifacts, fileObjects, readyActivities, invalidActivities)
			}
			if _, err := files.Open(f.ctx, artifactKey); !os.IsNotExist(err) {
				t.Fatalf("failed physical artifact remains = %v", err)
			}
			if visible, err := f.store.Artifacts(f.ctx, f.ownerID, mustMissionID(t, f.store, f.jobID)); err != nil || len(visible) != 0 {
				t.Fatalf("invalid artifact remained visible = %d/%v", len(visible), err)
			}
		})
	}
}

func ftruncatePayload() []byte { return []byte("x") }

func TestGenerationWorkersTakeoverOnlyAfterOldOutputTransactionReleases(t *testing.T) {
	f := newWorkerIntegrationFixture(t)
	f.store.LeaseDuration = 100 * time.Millisecond
	worker, files := newGenerationWorker(f)
	worker.skipLeaseRenewal = true
	entered := make(chan struct{})
	var enteredOnce sync.Once
	release := make(chan struct{})
	var releaseOnce sync.Once
	f.store.SetBeforeGenerationArtifactWriteHookForTest(func() {
		enteredOnce.Do(func() { close(entered) })
		<-release
	})
	defer func() {
		releaseOnce.Do(func() { close(release) })
		f.store.SetBeforeGenerationArtifactWriteHookForTest(nil)
		f.store.SetGenerationClaimHooksForTest(nil, nil)
	}()
	aDone := make(chan struct{})
	go func() {
		worker.RunOnce(f.ctx)
		close(aDone)
	}()
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("generation worker A did not hold the output transaction")
	}
	waitForGenerationLeaseExpiry(t, f.ctx, f.store, f.jobID, 3*time.Second)
	bAttempted := make(chan struct{})
	var bAttemptOnce sync.Once
	f.store.SetGenerationClaimHooksForTest(func() { bAttemptOnce.Do(func() { close(bAttempted) }) }, nil)
	if err := worker.RunOnce(f.ctx); err != nil {
		t.Fatalf("generation worker B first SKIP LOCKED pass = %v", err)
	}
	select {
	case <-bAttempted:
	case <-time.After(3 * time.Second):
		t.Fatal("generation worker B did not enter claim while A held lock")
	}
	releaseOnce.Do(func() { close(release) })
	select {
	case <-aDone:
	case <-time.After(3 * time.Second):
		t.Fatal("generation worker A did not finish after release")
	}
	if err := worker.RunOnce(f.ctx); err != nil {
		t.Fatalf("generation worker B takeover retry = %v", err)
	}
	var status string
	if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM generation_jobs WHERE id=$1`, f.jobID).Scan(&status); err != nil {
		t.Fatal(err)
	}
	if status != "SUCCEEDED" {
		t.Fatalf("generation takeover final status = %s", status)
	}
	var artifacts, fileObjects, readyActivities int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE generation_job_id=$1`, f.jobID).Scan(&artifacts); err != nil {
		t.Fatal(err)
	}
	artifactKey := generationArtifactStorageKey(t, f.store, f.jobID)
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, artifactKey).Scan(&fileObjects); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=(SELECT mission_id FROM generation_jobs WHERE id=$1) AND event_type='ARTIFACT_READY'`, f.jobID).Scan(&readyActivities); err != nil {
		t.Fatal(err)
	}
	if artifacts != 1 || fileObjects != 1 || readyActivities != 1 {
		t.Fatalf("generation takeover side effects = artifacts %d file_objects %d ready activities %d", artifacts, fileObjects, readyActivities)
	}
	hash := sha256.Sum256(f.data)
	if err := files.Verify(f.ctx, storage.File{StorageKey: artifactKey, Size: int64(len(f.data)), SHA256: hex.EncodeToString(hash[:])}); err != nil {
		t.Fatalf("generation takeover physical file reconciliation: %v", err)
	}
}

func TestIndependentGenerationWorkerBCompletesTakeoverAfterALeaseExpires(t *testing.T) {
	f := newWorkerIntegrationFixture(t)
	f.store.ConfigureWorker("generation-worker-a", 100*time.Millisecond)
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE generation_jobs SET lease_expires_at=clock_timestamp()+interval '100 milliseconds' WHERE id=$1`, f.jobID); err != nil {
		t.Fatal(err)
	}
	workerA, filesA := newGenerationWorker(f)
	workerA.skipLeaseRenewal = true
	dataB := []byte("physical generation worker artifact B")
	hashB := sha256.Sum256(dataB)
	engineBPath := filepath.Join(f.root, "run", "result-b.pptx")
	if err := os.WriteFile(engineBPath, dataB, 0o640); err != nil {
		t.Fatal(err)
	}
	engineB := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if _, err := w.Write([]byte(`{"status":"SUCCEEDED","artifacts":[{"artifactId":"engine-b","artifactType":"PPTX","storageKey":"run/result-b.pptx","sha256":"` + hex.EncodeToString(hashB[:]) + `","fileSize":` + strconv.Itoa(len(dataB)) + `,"mediaType":"application/vnd.openxmlformats-officedocument.presentationml.presentation"}]}`)); err != nil {
			t.Errorf("engine B fixture response: %v", err)
		}
	}))
	defer engineB.Close()

	entered := make(chan struct{})
	release := make(chan struct{})
	var releaseOnce sync.Once
	f.store.SetBeforeGenerationArtifactWriteHookForTest(func() {
		close(entered)
		<-release
	})
	defer func() {
		releaseOnce.Do(func() { close(release) })
		f.store.SetBeforeGenerationArtifactWriteHookForTest(nil)
		f.store.SetGenerationClaimHooksForTest(nil, nil)
	}()
	aClaimed := make(chan model.GenerationJob, 1)
	f.store.SetGenerationClaimHooksForTest(nil, func(job model.GenerationJob) { aClaimed <- job })
	var aJob model.GenerationJob
	aDone := make(chan struct{})
	aResult := make(chan error, 1)
	go func() {
		aResult <- workerA.RunOnce(f.ctx)
		close(aDone)
	}()
	select {
	case aJob = <-aClaimed:
		if f.store.WorkerID != "generation-worker-a" || aJob.LeaseToken == "" {
			t.Fatalf("worker A identity/token = %q/%q", f.store.WorkerID, aJob.LeaseToken)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("worker A did not claim through the real worker path")
	}
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("worker A did not enter the real output transaction")
	}
	// A has already received its own Engine receipt and persisted distinct
	// physical bytes before the final-fence transaction is released. The only
	// database effects are inside that still-open transaction, so a stale token
	// cannot leave a partial file object, artifact or activity row.
	oldKey := generationStorageKey(f.jobID, aJob.LeaseToken)
	oldFile, err := filesA.Open(f.ctx, oldKey)
	if err != nil {
		t.Fatalf("open worker A old physical receipt: %v", err)
	}
	oldBytes, readErr := io.ReadAll(oldFile)
	closeErr := oldFile.Close()
	if readErr != nil || closeErr != nil || string(oldBytes) != string(f.data) {
		t.Fatalf("worker A old physical receipt = %q read=%v close=%v", string(oldBytes), readErr, closeErr)
	}
	for _, table := range []string{"file_objects", "artifacts", "activity_events"} {
		var count int
		query := "SELECT count(*) FROM " + table + " WHERE "
		if table == "file_objects" {
			query += "storage_key=$1"
		} else if table == "artifacts" {
			query += "generation_job_id=$1"
		} else {
			query += "mission_id=$1 AND idempotency_key LIKE $2"
		}
		if table == "activity_events" {
			err = f.store.DB.QueryRow(f.ctx, query, mustMissionID(t, f.store, f.jobID), "generation-job:"+f.jobID+":%").Scan(&count)
		} else if table == "file_objects" {
			err = f.store.DB.QueryRow(f.ctx, query, oldKey).Scan(&count)
		} else {
			err = f.store.DB.QueryRow(f.ctx, query, f.jobID).Scan(&count)
		}
		if err != nil || count != 0 {
			t.Fatalf("worker A pre-fence %s effects=%d err=%v", table, count, err)
		}
	}

	bPool, err := database.Open(f.ctx, os.Getenv("LESSONFORGE_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatalf("open independent worker B pool: %v", err)
	}
	t.Cleanup(bPool.Close)
	storeB := database.NewStore(bPool)
	storeB.ConfigureWorker("generation-worker-b", 10*time.Second)
	filesB, err := storage.New(filepath.Join(f.root, "stored"), 200*1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	workerB := &Worker{Store: storeB, Engine: pptengine.NewClient(engineB.URL, f.root, time.Second), Storage: filesB, OwnerResolver: func(context.Context, int64) (int64, error) { return f.ownerID, nil }}
	bAttempted := make(chan struct{})
	var bAttemptOnce sync.Once
	bClaimed := make(chan model.GenerationJob, 1)
	storeB.SetGenerationClaimHooksForTest(func() { bAttemptOnce.Do(func() { close(bAttempted) }) }, func(job model.GenerationJob) { bClaimed <- job })
	// Let the lease expire while A still owns the real final-fence transaction.
	// This waits on the database clock with a deadline; it is not a timing sleep.
	waitForGenerationLeaseExpiry(t, f.ctx, f.store, f.jobID, 3*time.Second)
	// The first B pass reaches the production SKIP LOCKED claim while A's
	// transaction is open and must find no claimable row. The explicit second
	// RunOnce below is the worker's real retry after the fence is released.
	if err := workerB.RunOnce(f.ctx); err != nil {
		t.Fatalf("worker B first SKIP LOCKED pass = %v", err)
	}
	select {
	case <-bAttempted:
	case <-time.After(2 * time.Second):
		t.Fatal("worker B did not enter ClaimGeneration while A held the output transaction")
	}
	select {
	case unexpected := <-bClaimed:
		t.Fatalf("worker B claimed while A held final fence: token=%s", unexpected.LeaseToken)
	default:
	}
	releaseOnce.Do(func() { close(release) })
	select {
	case <-aDone:
		if err := <-aResult; err == nil || !strings.Contains(err.Error(), "GENERATION_EXECUTION_FENCE_LOST") {
			t.Fatalf("worker A old token result = %v, want observable final-fence failure", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("worker A did not finish after release")
	}
	if _, err := filesA.Open(f.ctx, oldKey); !os.IsNotExist(err) {
		t.Fatalf("stale worker A physical file was not cleaned before takeover: %v", err)
	}
	if err := workerB.RunOnce(f.ctx); err != nil {
		t.Fatalf("worker B takeover retry = %v", err)
	}
	var bJob model.GenerationJob
	select {
	case bJob = <-bClaimed:
	case <-time.After(2 * time.Second):
		t.Fatal("worker B did not expose a successful claim through its real worker path")
	}
	if storeB.WorkerID != "generation-worker-b" || bJob.LeaseToken == "" || bJob.LeaseToken == aJob.LeaseToken {
		t.Fatalf("worker A/B identity and tokens = %q/%q %q/%q", f.store.WorkerID, aJob.LeaseToken, storeB.WorkerID, bJob.LeaseToken)
	}
	bKey := generationStorageKey(f.jobID, bJob.LeaseToken)
	var status string
	if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM generation_jobs WHERE id=$1`, f.jobID).Scan(&status); err != nil {
		t.Fatal(err)
	}
	if status != "SUCCEEDED" {
		t.Fatalf("takeover final status = %s", status)
	}
	var artifacts, fileObjects, readyActivities int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE generation_job_id=$1`, f.jobID).Scan(&artifacts); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, bKey).Scan(&fileObjects); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=(SELECT mission_id FROM generation_jobs WHERE id=$1) AND event_type='ARTIFACT_READY'`, f.jobID).Scan(&readyActivities); err != nil {
		t.Fatal(err)
	}
	if artifacts != 1 || fileObjects != 1 || readyActivities != 1 {
		t.Fatalf("worker B visible side effects = artifacts %d file_objects %d ready %d", artifacts, fileObjects, readyActivities)
	}
	var artifactID, artifactKey, artifactHash, fileHash, readyRefType, readyRefID string
	var artifactSize, fileSize int64
	if err := f.store.DB.QueryRow(f.ctx, `SELECT a.id,a.sha256,a.size_bytes,fo.storage_key,fo.sha256,fo.size_bytes FROM artifacts a JOIN file_objects fo ON fo.id=a.file_object_id WHERE a.generation_job_id=$1`, f.jobID).Scan(&artifactID, &artifactHash, &artifactSize, &artifactKey, &fileHash, &fileSize); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT reference_type,reference_id FROM activity_events WHERE mission_id=$1 AND idempotency_key=$2`, mustMissionID(t, f.store, f.jobID), "generation-job:"+f.jobID+":artifact").Scan(&readyRefType, &readyRefID); err != nil {
		t.Fatal(err)
	}
	wantHash := hex.EncodeToString(hashB[:])
	if artifactID == "" || artifactKey != bKey || artifactHash != wantHash || fileHash != wantHash || artifactSize != int64(len(dataB)) || fileSize != int64(len(dataB)) || readyRefType != "ARTIFACT" || readyRefID != artifactID {
		t.Fatalf("B receipt mismatch: artifact=%s key=%s artifactHash=%s fileHash=%s sizes=%d/%d ref=%s/%s", artifactID, artifactKey, artifactHash, fileHash, artifactSize, fileSize, readyRefType, readyRefID)
	}
	if err := filesA.Verify(f.ctx, storage.File{StorageKey: artifactKey, Size: int64(len(dataB)), SHA256: wantHash}); err != nil {
		t.Fatalf("worker B physical file verification: %v", err)
	}
	missionID := mustMissionID(t, f.store, f.jobID)
	// A reached the real expired-lease final fence and its compensation path;
	// the old attempt must leave no artifact/activity/file object of its own.
	var oldReady, oldArtifacts, oldFiles int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='ARTIFACT_READY' AND reference_id<>$2`, missionID, artifactID).Scan(&oldReady); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE generation_job_id=$1 AND id<>$2`, f.jobID, artifactID).Scan(&oldArtifacts); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1 AND sha256<>$2`, artifactKey, wantHash).Scan(&oldFiles); err != nil {
		t.Fatal(err)
	}
	if oldReady != 0 || oldArtifacts != 0 || oldFiles != 0 {
		t.Fatalf("old A worker left visible side effects: ready=%d artifacts=%d files=%d", oldReady, oldArtifacts, oldFiles)
	}
	token, err := auth.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if err := f.store.CreateSession(f.ctx, f.ownerID, auth.TokenHash(token), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	api := httpapi.NewServer(httpapi.Config{SessionCookie: "session"}, f.store.DB, f.store, nil, filesA, nil, nil)
	tcp := httptest.NewServer(api.Router())
	defer tcp.Close()
	request, err := http.NewRequest(http.MethodGet, tcp.URL+"/api/missions/"+strconv.FormatInt(missionID, 10)+"/artifacts", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+token)
	response, err := tcp.Client().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	apiBody, readErr := io.ReadAll(response.Body)
	closeErr = response.Body.Close()
	if readErr != nil || closeErr != nil {
		t.Fatalf("takeover artifact API response: read=%v close=%v", readErr, closeErr)
	}
	var apiArtifacts []model.Artifact
	if err := json.Unmarshal(apiBody, &apiArtifacts); err != nil {
		t.Fatalf("takeover artifact API JSON: %v body=%s", err, string(apiBody))
	}
	if response.StatusCode != http.StatusOK || len(apiArtifacts) != 1 || apiArtifacts[0].ID != artifactID || apiArtifacts[0].GenerationJobID != f.jobID || apiArtifacts[0].Status != database.ArtifactStatusReady || apiArtifacts[0].Size != int64(len(dataB)) || apiArtifacts[0].SHA256 != wantHash || apiArtifacts[0].File.SHA256 != wantHash || apiArtifacts[0].File.Size != int64(len(dataB)) {
		t.Fatalf("takeover artifact API = %d/%s", response.StatusCode, string(apiBody))
	}
	download, err := http.NewRequest(http.MethodGet, tcp.URL+"/api/artifacts/"+artifactID+"/download", nil)
	if err != nil {
		t.Fatal(err)
	}
	download.Header.Set("Authorization", "Bearer "+token)
	downloadResponse, err := tcp.Client().Do(download)
	if err != nil {
		t.Fatal(err)
	}
	downloaded, readErr := io.ReadAll(downloadResponse.Body)
	closeErr = downloadResponse.Body.Close()
	if readErr != nil || closeErr != nil {
		t.Fatalf("takeover download response: read=%v close=%v", readErr, closeErr)
	}
	downloadedHash := sha256.Sum256(downloaded)
	if downloadResponse.StatusCode != http.StatusOK || downloadResponse.Header.Get("Content-Length") != strconv.Itoa(len(dataB)) || string(downloaded) != string(dataB) || hex.EncodeToString(downloadedHash[:]) != wantHash {
		t.Fatalf("takeover download = %d length=%q body=%q", downloadResponse.StatusCode, downloadResponse.Header.Get("Content-Length"), string(downloaded))
	}
	other, err := f.store.CreateUser(f.ctx, "Takeover outsider", "takeover-"+uuid.NewString()+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if _, err := f.store.DB.Exec(context.Background(), `DELETE FROM users WHERE id=$1`, other.ID); err != nil {
			t.Errorf("cleanup takeover outsider: %v", err)
		}
	})
	otherToken, err := auth.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if err := f.store.CreateSession(f.ctx, other.ID, auth.TokenHash(otherToken), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	crossOwner, err := http.NewRequest(http.MethodGet, tcp.URL+"/api/artifacts/"+artifactID+"/download", nil)
	if err != nil {
		t.Fatal(err)
	}
	crossOwner.Header.Set("Authorization", "Bearer "+otherToken)
	crossResponse, err := tcp.Client().Do(crossOwner)
	if err != nil {
		t.Fatal(err)
	}
	if closeErr := crossResponse.Body.Close(); closeErr != nil {
		t.Fatal(closeErr)
	}
	if crossResponse.StatusCode != http.StatusNotFound {
		t.Fatalf("cross-owner artifact download = %d, want 404", crossResponse.StatusCode)
	}
}

func mustMissionID(t *testing.T, store *database.Store, jobID string) int64 {
	t.Helper()
	var missionID int64
	if err := store.DB.QueryRow(context.Background(), `SELECT mission_id FROM generation_jobs WHERE id=$1`, jobID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	return missionID
}

func generationArtifactStorageKey(t *testing.T, store *database.Store, jobID string) string {
	t.Helper()
	var key string
	if err := store.DB.QueryRow(context.Background(), `SELECT fo.storage_key FROM artifacts a JOIN file_objects fo ON fo.id=a.file_object_id WHERE a.generation_job_id=$1`, jobID).Scan(&key); err != nil {
		t.Fatal(err)
	}
	return key
}

func waitForGenerationLeaseExpiry(t *testing.T, parent context.Context, store *database.Store, jobID string, limit time.Duration) {
	t.Helper()
	ctx, cancel := context.WithTimeout(parent, limit)
	defer cancel()
	ticker := time.NewTicker(5 * time.Millisecond)
	defer ticker.Stop()
	for {
		var expired bool
		if err := store.DB.QueryRow(ctx, `SELECT lease_expires_at<=clock_timestamp() FROM generation_jobs WHERE id=$1`, jobID).Scan(&expired); err != nil {
			t.Fatalf("read generation lease expiry: %v", err)
		}
		if expired {
			return
		}
		select {
		case <-ctx.Done():
			t.Fatalf("generation lease did not expire within %s: %v", limit, ctx.Err())
		case <-ticker.C:
		}
	}
}
