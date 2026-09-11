package database

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/platform/storage"
)

func integrationStore(t *testing.T) (*Store, context.Context) {
	t.Helper()
	dsn := os.Getenv("LESSONFORGE_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("LESSONFORGE_TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	t.Cleanup(cancel)
	pool, err := Open(ctx, dsn)
	if err != nil {
		t.Fatalf("open integration database: %v", err)
	}
	migrationsDir, err := filepath.Abs(filepath.Join("..", "..", "..", "migrations"))
	if err != nil {
		pool.Close()
		t.Fatal(err)
	}
	if err := Migrate(ctx, pool, migrationsDir); err != nil {
		pool.Close()
		t.Fatalf("migrate integration database: %v", err)
	}
	t.Cleanup(pool.Close)
	unlock, err := AcquireIntegrationTestLock(ctx, pool)
	if err != nil {
		pool.Close()
		t.Fatal(err)
	}
	t.Cleanup(unlock)
	store := NewStore(pool)
	return store, ctx
}

type integrationFixture struct {
	store      *Store
	ctx        context.Context
	owner      model.User
	connection model.ModelConnection
	missionID  int64
	messageID  int64
	agentRunID string
	leaseToken string
	generation string
}

type staticTemplateProfile struct {
	profile map[string]any
}

func (p staticTemplateProfile) GetProfile(context.Context, int64, int64, string) (map[string]any, error) {
	copyProfile := make(map[string]any, len(p.profile))
	for key, value := range p.profile {
		copyProfile[key] = value
	}
	return copyProfile, nil
}

func newIntegrationFixture(t *testing.T) integrationFixture {
	t.Helper()
	store, ctx := integrationStore(t)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Fence Test", "fence-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	connection, err := store.CreateConnection(ctx, owner.ID, model.ModelConnection{Name: "Fence connection " + suffix, Protocol: "OPENAI_COMPATIBLE", BaseURL: "http://127.0.0.1:1", ModelID: "test-model", KeyHint: "test"}, "test-encrypted-key")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.DB.Exec(ctx, `UPDATE model_connections SET enabled=true,verification_status='VERIFIED' WHERE id=$1`, connection.ID); err != nil {
		t.Fatal(err)
	}
	var missionID, messageID int64
	if err := store.DB.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title,selected_model_connection_id) VALUES($1,'SELF_CREATED','Fence test mission',$2) RETURNING id`, owner.ID, connection.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','start') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	leaseToken := uuid.NewString()
	snapshot, _ := json.Marshal(map[string]any{"connectionId": connection.ID, "protocol": connection.Protocol, "baseUrl": connection.BaseURL, "modelId": connection.ModelID, "encryptedApiKey": "test-encrypted-key"})
	if _, err := store.DB.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_connection_id,model_identity_snapshot,status,lease_token,lease_expires_at) VALUES($1,$2,$3,$4,$5,'RUNNING',$6,now()+interval '10 minutes')`, runID, missionID, messageID, connection.ID, snapshot, leaseToken); err != nil {
		t.Fatal(err)
	}
	draftID := uuid.NewString()
	if _, err := store.DB.Exec(ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id) VALUES($1,$2,1,'test','{"slides":[]}'::jsonb,$3)`, draftID, missionID, runID); err != nil {
		t.Fatal(err)
	}
	specID := uuid.NewString()
	if _, err := store.DB.Exec(ctx, `INSERT INTO locked_specifications(id,mission_id,source_draft_id,version,specification_json,template_binding_json,content_hash) VALUES($1,$2,$3,1,'{"slides":[]}'::jsonb,'{}'::jsonb,$4)`, specID, missionID, draftID, fmt.Sprintf("%064x", 1)); err != nil {
		t.Fatal(err)
	}
	jobID := uuid.NewString()
	if _, err := store.DB.Exec(ctx, `INSERT INTO generation_jobs(id,mission_id,specification_id,status,lease_token,lease_expires_at) VALUES($1,$2,$3,'RUNNING',$4,now()+interval '10 minutes')`, jobID, missionID, specID, leaseToken); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		// The fixture deliberately creates a Locked Specification so fence and
		// idempotency tests can exercise the immutable boundary. Cleanup is the
		// only place where this isolated test data may be removed; temporarily
		// disable the trigger for the exact delete and restore it immediately.
		triggerDisabled := false
		if _, err := store.DB.Exec(cleanupCtx, `ALTER TABLE locked_specifications DISABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
			t.Errorf("cleanup disable locked specification trigger: %v", err)
		} else {
			triggerDisabled = true
		}
		defer func() {
			if triggerDisabled {
				if _, err := store.DB.Exec(cleanupCtx, `ALTER TABLE locked_specifications ENABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
					t.Errorf("cleanup enable locked specification trigger: %v", err)
				}
			}
		}()
		for _, cleanup := range []struct {
			name  string
			query string
			arg   any
		}{
			{"mission", `DELETE FROM missions WHERE id=$1`, missionID},
			{"execution audits", `DELETE FROM execution_audits WHERE actor_user_id=$1`, owner.ID},
			{"user", `DELETE FROM users WHERE id=$1`, owner.ID},
		} {
			if _, err := store.DB.Exec(cleanupCtx, cleanup.query, cleanup.arg); err != nil {
				t.Errorf("cleanup %s: %v", cleanup.name, err)
			}
		}
		var remaining int
		if err := store.DB.QueryRow(cleanupCtx, `SELECT count(*) FROM users WHERE id=$1`, owner.ID).Scan(&remaining); err != nil || remaining != 0 {
			t.Errorf("cleanup residual user %d: %v", remaining, err)
		}
	})
	return integrationFixture{store: store, ctx: ctx, owner: owner, connection: connection, missionID: missionID, messageID: messageID, agentRunID: runID, leaseToken: leaseToken, generation: jobID}
}

func TestExpiredUploadCleanupRetainsRowsUntilPhysicalDelete(t *testing.T) {
	store, ctx := integrationStore(t)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Upload Cleanup Test", "upload-cleanup-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, owner.ID); err != nil {
			t.Errorf("cleanup upload test user: %v", err)
		}
	})

	storageKey := "uploads/cleanup-" + suffix
	uploadID, err := store.CreateUpload(ctx, owner.ID, model.FileObject{
		OriginalName: "expired.pdf",
		MimeType:     "application/pdf",
		Size:         12,
		SHA256:       fmt.Sprintf("%064x", 1),
		StorageKey:   storageKey,
	}, time.Now().Add(-time.Minute))
	if err != nil {
		t.Fatal(err)
	}

	keys, err := store.CleanupExpiredUploads(ctx)
	if err != nil {
		t.Fatalf("cleanup expired uploads: %v", err)
	}
	if len(keys) != 1 || keys[0] != storageKey {
		t.Fatalf("cleanup keys = %v, want [%s]", keys, storageKey)
	}
	var uploads, files int
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM uploads WHERE id=$1`, uploadID).Scan(&uploads); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, storageKey).Scan(&files); err != nil {
		t.Fatal(err)
	}
	if uploads != 1 || files != 1 {
		t.Fatalf("after physical cleanup candidate selection uploads=%d files=%d, want 1/1", uploads, files)
	}

	if err := store.FinalizeExpiredUpload(ctx, storageKey); err != nil {
		t.Fatalf("finalize expired upload: %v", err)
	}
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM uploads WHERE id=$1`, uploadID).Scan(&uploads); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, storageKey).Scan(&files); err != nil {
		t.Fatal(err)
	}
	if uploads != 0 || files != 0 {
		t.Fatalf("after finalization uploads=%d files=%d, want 0/0", uploads, files)
	}
}

func TestExpiredUploadCleanupDeletesUnboundPhysicalFileAndPreservesBoundFile(t *testing.T) {
	store, ctx := integrationStore(t)
	files, err := storage.New(t.TempDir(), 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Physical cleanup test", "physical-cleanup-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	var boundMissionID, boundFileID int64
	var boundUploadID string
	t.Cleanup(func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if boundMissionID != 0 {
			if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, boundMissionID); err != nil {
				t.Errorf("cleanup bound mission: %v", err)
			}
		}
		if boundUploadID != "" {
			if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM uploads WHERE id=$1`, boundUploadID); err != nil {
				t.Errorf("cleanup bound upload: %v", err)
			}
		}
		if boundFileID != 0 {
			if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM file_objects WHERE id=$1`, boundFileID); err != nil {
				t.Errorf("cleanup bound file object: %v", err)
			}
		}
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, owner.ID); err != nil {
			t.Errorf("cleanup physical cleanup user: %v", err)
		}
	})

	unbound, err := files.SaveBytesAtKey(ctx, "uploads/unbound-"+suffix+".pdf", "unbound.pdf", "application/pdf", []byte("unbound material"))
	if err != nil {
		t.Fatal(err)
	}
	unboundUploadID, err := store.CreateUpload(ctx, owner.ID, model.FileObject{
		OriginalName: unbound.OriginalName,
		MimeType:     unbound.MimeType,
		Size:         unbound.Size,
		SHA256:       unbound.SHA256,
		StorageKey:   unbound.StorageKey,
	}, time.Now().Add(-time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	keys, err := store.CleanupExpiredUploads(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(keys) != 1 || keys[0] != unbound.StorageKey {
		t.Fatalf("physical cleanup candidates = %v, want [%s]", keys, unbound.StorageKey)
	}
	if err := files.Remove(unbound.StorageKey); err != nil {
		t.Fatal(err)
	}
	if err := store.FinalizeExpiredUpload(ctx, unbound.StorageKey); err != nil {
		t.Fatal(err)
	}
	if _, err := files.Open(ctx, unbound.StorageKey); !os.IsNotExist(err) {
		t.Fatalf("unbound physical file open error = %v, want not exists", err)
	}
	var unboundUploads, unboundFiles int
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM uploads WHERE id=$1`, unboundUploadID).Scan(&unboundUploads); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, unbound.StorageKey).Scan(&unboundFiles); err != nil {
		t.Fatal(err)
	}
	if unboundUploads != 0 || unboundFiles != 0 {
		t.Fatalf("unbound rows after cleanup = uploads %d files %d, want 0/0", unboundUploads, unboundFiles)
	}

	bound, err := files.SaveBytesAtKey(ctx, "uploads/bound-"+suffix+".pdf", "bound.pdf", "application/pdf", []byte("bound material"))
	if err != nil {
		t.Fatal(err)
	}
	boundUploadID, err = store.CreateUpload(ctx, owner.ID, model.FileObject{
		OriginalName: bound.OriginalName,
		MimeType:     bound.MimeType,
		Size:         bound.Size,
		SHA256:       bound.SHA256,
		StorageKey:   bound.StorageKey,
	}, time.Now().Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	boundMissionID, _, _, err = store.CreateMissionAtomic(ctx, owner.ID, "Bound cleanup mission", "", "Keep this bound file", []string{boundUploadID}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `SELECT file_object_id FROM uploads WHERE id=$1`, boundUploadID).Scan(&boundFileID); err != nil {
		t.Fatal(err)
	}
	keys, err = store.CleanupExpiredUploads(ctx)
	if err != nil {
		t.Fatal(err)
	}
	for _, key := range keys {
		if key == bound.StorageKey {
			t.Fatalf("bound file was selected for cleanup: %s", key)
		}
	}
	boundHandle, err := files.Open(ctx, bound.StorageKey)
	if err != nil {
		t.Fatalf("bound physical file was removed: %v", err)
	}
	if err := boundHandle.Close(); err != nil {
		t.Fatalf("close bound physical file: %v", err)
	}
}

func TestAgentRunsScansNullableErrorsAndPreservesOwnerBoundary(t *testing.T) {
	f := newIntegrationFixture(t)
	statuses := []string{"WAITING_INPUTS", "QUEUED", "RUNNING", "FAILED", "CANCELLED"}
	for _, status := range statuses {
		id := uuid.NewString()
		if _, err := f.store.DB.Exec(f.ctx, `INSERT INTO agent_runs(id,mission_id,model_connection_id,model_identity_snapshot,status,error_code,error_message) VALUES($1,$2,$3,'{}'::jsonb,$4,$5,$6)`, id, f.missionID, f.connection.ID, status, nullableTestValue(status == "FAILED", "E_TEST"), nullableTestValue(status == "FAILED", "test failure")); err != nil {
			t.Fatal(err)
		}
	}
	runs, err := f.store.AgentRuns(f.ctx, f.owner.ID, f.missionID)
	if err != nil {
		t.Fatalf("AgentRuns: %v", err)
	}
	if len(runs) != len(statuses)+1 {
		t.Fatalf("run count = %d, want %d", len(runs), len(statuses)+1)
	}
	seen := map[string]bool{}
	for _, run := range runs {
		seen[run.Status] = true
	}
	for _, status := range statuses {
		if !seen[status] {
			t.Errorf("missing status %s", status)
		}
	}
	other, err := f.store.CreateUser(f.ctx, "Other", "other-"+uuid.NewString()+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		if _, err := f.store.DB.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, other.ID); err != nil {
			t.Errorf("cleanup secondary owner %d: %v", other.ID, err)
		}
	})
	if _, err := f.store.AgentRuns(f.ctx, other.ID, f.missionID); !errors.Is(err, pgx.ErrNoRows) {
		t.Fatalf("cross-owner AgentRuns error = %v", err)
	}
}

func TestConcurrentAgentClaimsAreExclusive(t *testing.T) {
	f := newIntegrationFixture(t)
	runID := uuid.NewString()
	snapshot, err := json.Marshal(map[string]any{
		"connectionId":    f.connection.ID,
		"protocol":        f.connection.Protocol,
		"baseUrl":         f.connection.BaseURL,
		"modelId":         f.connection.ModelID,
		"encryptedApiKey": "test-encrypted-key",
	})
	if err != nil {
		t.Fatal(err)
	}
	var messageID int64
	if err := f.store.DB.QueryRow(f.ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','claim me') RETURNING id`, f.missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.DB.Exec(f.ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_connection_id,model_identity_snapshot,status) VALUES($1,$2,$3,$4,$5,'QUEUED')`, runID, f.missionID, messageID, f.connection.ID, snapshot); err != nil {
		t.Fatal(err)
	}

	start := make(chan struct{})
	results := make(chan struct {
		run model.AgentRun
		err error
	}, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			run, err := f.store.ClaimAgent(f.ctx)
			results <- struct {
				run model.AgentRun
				err error
			}{run: run, err: err}
		}()
	}
	close(start)
	wg.Wait()
	close(results)

	var successful []model.AgentRun
	var noRows int
	for result := range results {
		if result.err == nil {
			successful = append(successful, result.run)
			continue
		}
		if errors.Is(result.err, pgx.ErrNoRows) {
			noRows++
			continue
		}
		t.Fatalf("unexpected concurrent claim error: %v", result.err)
	}
	if len(successful) != 1 || noRows != 1 || successful[0].ID != runID {
		t.Fatalf("concurrent claims = success %d noRows %d successfulRun=%+v", len(successful), noRows, successful)
	}
}

func TestAgentHeartbeatRenewsLeaseAndRejectsStaleToken(t *testing.T) {
	f := newIntegrationFixture(t)
	f.store.ConfigureWorker("heartbeat-test-worker", 5*time.Second)
	var beforeHeartbeat, beforeExpiry time.Time
	if err := f.store.DB.QueryRow(f.ctx, `UPDATE agent_runs SET heartbeat_at=clock_timestamp()-interval '1 minute',lease_expires_at=clock_timestamp()+interval '100 milliseconds' WHERE id=$1 RETURNING heartbeat_at,lease_expires_at`, f.agentRunID).Scan(&beforeHeartbeat, &beforeExpiry); err != nil {
		t.Fatal(err)
	}
	if err := f.store.RenewAgentLease(f.ctx, f.agentRunID, f.leaseToken); err != nil {
		t.Fatalf("renew agent lease: %v", err)
	}
	var afterHeartbeat, afterExpiry time.Time
	if err := f.store.DB.QueryRow(f.ctx, `SELECT heartbeat_at,lease_expires_at FROM agent_runs WHERE id=$1`, f.agentRunID).Scan(&afterHeartbeat, &afterExpiry); err != nil {
		t.Fatal(err)
	}
	if !afterHeartbeat.After(beforeHeartbeat) || !afterExpiry.After(beforeExpiry) {
		t.Fatalf("heartbeat renewal did not advance timestamps: before=%s/%s after=%s/%s", beforeHeartbeat, beforeExpiry, afterHeartbeat, afterExpiry)
	}
	if err := f.store.RenewAgentLease(f.ctx, f.agentRunID, uuid.NewString()); err == nil {
		t.Fatal("stale lease token unexpectedly renewed agent run")
	}
}

func TestRecoverAgentRunsRequeuesExpiredLeaseForRestart(t *testing.T) {
	f := newIntegrationFixture(t)
	startedAt := time.Now().Add(-time.Minute)
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET started_at=$1,heartbeat_at=clock_timestamp()-interval '1 minute',lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=$2`, startedAt, f.agentRunID); err != nil {
		t.Fatal(err)
	}
	if err := f.store.RecoverAgentRuns(f.ctx); err != nil {
		t.Fatalf("recover stale agent run: %v", err)
	}
	var status string
	var leaseOwner, leaseToken, leaseExpires, heartbeat *string
	var recoveredStartedAt *time.Time
	if err := f.store.DB.QueryRow(f.ctx, `SELECT status,lease_owner,lease_token,lease_expires_at::text,heartbeat_at::text,started_at FROM agent_runs WHERE id=$1`, f.agentRunID).Scan(&status, &leaseOwner, &leaseToken, &leaseExpires, &heartbeat, &recoveredStartedAt); err != nil {
		t.Fatal(err)
	}
	if status != "QUEUED" || leaseOwner != nil || leaseToken != nil || leaseExpires != nil || heartbeat != nil || recoveredStartedAt != nil {
		t.Fatalf("recovered stale run = status=%s owner=%v token=%v expires=%v heartbeat=%v started=%v", status, leaseOwner, leaseToken, leaseExpires, heartbeat, recoveredStartedAt)
	}
}

func TestPlanningDraftVersionsRetainMarkdownAndStructuredHistory(t *testing.T) {
	f := newIntegrationFixture(t)
	_, secondRunID, err := f.store.CreateMessageAndRun(f.ctx, f.owner.ID, f.missionID, "revise the planning draft")
	if err != nil {
		t.Fatal(err)
	}
	secondRun, err := f.store.ClaimAgent(f.ctx)
	if err != nil {
		t.Fatalf("claim revision agent run: %v", err)
	}
	if secondRun.ID != secondRunID {
		t.Fatalf("claimed run = %s, want %s", secondRun.ID, secondRunID)
	}
	second, err := f.store.SaveDraftForRun(f.ctx, f.missionID, secondRun.ID, secondRun.LeaseToken, "# Draft v2", map[string]any{"slides": []any{map[string]any{"title": "Revised"}}})
	if err != nil {
		t.Fatalf("save revision draft: %v", err)
	}
	if second.Version != 2 || second.Markdown != "# Draft v2" {
		t.Fatalf("revision draft = %#v", second)
	}
	drafts, err := f.store.Drafts(f.ctx, f.owner.ID, f.missionID)
	if err != nil {
		t.Fatalf("list planning drafts: %v", err)
	}
	if len(drafts) != 2 || drafts[0].Version != 2 || drafts[1].Version != 1 || drafts[1].Markdown != "test" {
		t.Fatalf("draft history = %#v", drafts)
	}
	if slides, ok := drafts[0].StructuredPlan.(map[string]any)["slides"].([]any); !ok || len(slides) != 1 {
		t.Fatalf("revision structured plan = %#v", drafts[0].StructuredPlan)
	}
}

func TestLockedSpecificationRejectsDirectUpdate(t *testing.T) {
	f := newIntegrationFixture(t)
	var originalHash string
	if err := f.store.DB.QueryRow(f.ctx, `SELECT content_hash FROM locked_specifications WHERE mission_id=$1`, f.missionID).Scan(&originalHash); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE locked_specifications SET content_hash=$1 WHERE mission_id=$2`, fmt.Sprintf("%064x", 2), f.missionID); err == nil {
		t.Fatal("direct Locked Specification update unexpectedly succeeded")
	}
	var persistedHash string
	if err := f.store.DB.QueryRow(f.ctx, `SELECT content_hash FROM locked_specifications WHERE mission_id=$1`, f.missionID).Scan(&persistedHash); err != nil {
		t.Fatal(err)
	}
	if persistedHash != originalHash {
		t.Fatalf("immutable content hash changed from %s to %s", originalHash, persistedHash)
	}
}

func TestApproveDraftStopsAtLockedSpecificationWithoutGenerationJob(t *testing.T) {
	store, ctx := integrationStore(t)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Lock boundary", "lock-boundary-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	var missionID, messageID, templateFileID, missionFileID int64
	if err := store.DB.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title) VALUES($1,'SELF_CREATED','Lock boundary mission') RETURNING id`, owner.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','approve this draft') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	if _, err := store.DB.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_identity_snapshot,status) VALUES($1,$2,$3,'{"source":"integration"}'::jsonb,'COMPLETED')`, runID, missionID, messageID); err != nil {
		t.Fatal(err)
	}
	digest := fmt.Sprintf("%064x", 77)
	if err := store.DB.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,'template.pptx','application/vnd.openxmlformats-officedocument.presentationml.presentation',12,$3) RETURNING id`, owner.ID, "template/"+suffix+".pptx", digest).Scan(&templateFileID); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `INSERT INTO mission_files(mission_id,file_object_id,role,provenance,parse_status,uploaded_by) VALUES($1,$2,'TEMPLATE','TEACHER','READY',$3) RETURNING id`, missionID, templateFileID, owner.ID).Scan(&missionFileID); err != nil {
		t.Fatal(err)
	}
	draftID := uuid.NewString()
	if _, err := store.DB.Exec(ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id,output_stage) VALUES($1,$2,1,'# Draft','{"slides":[{"title":"Intro"}]}'::jsonb,$3,'PLAN_DRAFT')`, draftID, missionID, runID); err != nil {
		t.Fatal(err)
	}
	store.TemplateProfile = staticTemplateProfile{profile: map[string]any{
		"templateFileSha256":     digest,
		"templateId":             "template-1",
		"templateFileVersion":    "file-v1",
		"templateProfileVersion": "profile-v1",
	}}

	spec, err := store.ApproveDraft(ctx, owner.ID, draftID)
	if err != nil {
		t.Fatalf("ApproveDraft: %v", err)
	}
	if spec.ID == "" || spec.SourceDraftID != draftID || spec.ContentHash == "" {
		t.Fatalf("locked specification = %#v", spec)
	}
	var locked, jobs, approvals int
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM locked_specifications WHERE id=$1`, spec.ID).Scan(&locked); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM generation_jobs WHERE specification_id=$1`, spec.ID).Scan(&jobs); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='PLAN_APPROVED'`, missionID).Scan(&approvals); err != nil {
		t.Fatal(err)
	}
	if locked != 1 || jobs != 0 || approvals != 1 {
		t.Fatalf("approval side effects = locked %d jobs %d approvals %d", locked, jobs, approvals)
	}

	// Repeating approval is idempotent for the same draft and still must not
	// enqueue a downstream job.
	repeated, err := store.ApproveDraft(ctx, owner.ID, draftID)
	if err != nil || repeated.ID != spec.ID {
		t.Fatalf("repeated approval = %#v/%v", repeated, err)
	}
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM generation_jobs WHERE mission_id=$1`, missionID).Scan(&jobs); err != nil {
		t.Fatal(err)
	}
	if jobs != 0 {
		t.Fatalf("repeated approval queued %d generation jobs", jobs)
	}

	cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := store.DB.Exec(cleanupCtx, `ALTER TABLE locked_specifications DISABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
		t.Fatalf("disable immutable trigger for isolated cleanup: %v", err)
	}
	if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, missionID); err != nil {
		_, _ = store.DB.Exec(cleanupCtx, `ALTER TABLE locked_specifications ENABLE TRIGGER locked_specifications_immutable_trg`)
		t.Fatalf("cleanup lock boundary mission: %v", err)
	}
	if _, err := store.DB.Exec(cleanupCtx, `ALTER TABLE locked_specifications ENABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
		t.Fatalf("restore immutable trigger after isolated cleanup: %v", err)
	}
	if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, owner.ID); err != nil {
		t.Fatalf("cleanup lock boundary user: %v", err)
	}
}

func TestApproveDraftBindsRealStoredPPTXBeforeLockedSpecification(t *testing.T) {
	store, ctx := integrationStore(t)
	root := t.TempDir()
	store.ConfigureStorageRoot(root)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Physical template owner", "physical-template-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	var missionID, messageID, fileObjectID, missionFileID int64
	if err := store.DB.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title) VALUES($1,'SELF_CREATED','Physical template boundary') RETURNING id`, owner.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	if err := store.DB.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','approve physical template') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.DB.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_identity_snapshot,status) VALUES($1,$2,$3,'{"source":"integration"}'::jsonb,'COMPLETED')`, runID, missionID, messageID); err != nil {
		t.Fatal(err)
	}
	data := minimalStoredPPTX(t)
	digest := sha256.Sum256(data)
	sha := hex.EncodeToString(digest[:])
	storageKey := "uploads/physical-" + suffix + ".pptx"
	path := filepath.Join(root, filepath.FromSlash(storageKey))
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o640); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,'teacher-template.pptx','application/vnd.openxmlformats-officedocument.presentationml.presentation',$3,$4) RETURNING id`, owner.ID, storageKey, len(data), sha).Scan(&fileObjectID); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `INSERT INTO mission_files(mission_id,file_object_id,role,provenance,parse_status,uploaded_by) VALUES($1,$2,'TEMPLATE','TEACHER','READY',$3) RETURNING id`, missionID, fileObjectID, owner.ID).Scan(&missionFileID); err != nil {
		t.Fatal(err)
	}
	draftID := uuid.NewString()
	if _, err := store.DB.Exec(ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id,output_stage) VALUES($1,$2,1,'# Physical template draft','{"slides":[{"title":"Introduction"}]}'::jsonb,$3,'PLAN_DRAFT')`, draftID, missionID, runID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := store.DB.Exec(cleanupCtx, `ALTER TABLE locked_specifications DISABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
			t.Errorf("disable immutable trigger for physical template cleanup: %v", err)
		}
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, missionID); err != nil {
			t.Errorf("cleanup physical template mission: %v", err)
		}
		if _, err := store.DB.Exec(cleanupCtx, `ALTER TABLE locked_specifications ENABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
			t.Errorf("restore immutable trigger after physical template cleanup: %v", err)
		}
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, owner.ID); err != nil {
			t.Errorf("cleanup physical template owner: %v", err)
		}
	})

	spec, err := store.ApproveDraft(ctx, owner.ID, draftID)
	if err != nil {
		t.Fatalf("ApproveDraft with real stored PPTX: %v", err)
	}
	if spec.ID == "" || spec.SourceDraftID != draftID || spec.ContentHash == "" {
		t.Fatalf("locked specification = %#v", spec)
	}
	binding, ok := spec.TemplateBinding.(map[string]any)
	if !ok {
		t.Fatalf("template binding type = %#v", spec.TemplateBinding)
	}
	if binding["missionFileId"] != float64(missionFileID) && binding["missionFileId"] != missionFileID {
		t.Fatalf("template mission file binding = %#v", binding)
	}
	if binding["templateFileSha256"] != sha || binding["templateFileSize"] != float64(len(data)) && binding["templateFileSize"] != int64(len(data)) {
		t.Fatalf("template physical identity = %#v", binding)
	}
	if binding["templateProfileVersion"] == "" || binding["executionReady"] != false || binding["engineNativeProfilePresent"] != false {
		t.Fatalf("template readiness boundary = %#v", binding)
	}
	var jobs int
	if err := store.DB.QueryRow(ctx, `SELECT count(*) FROM generation_jobs WHERE mission_id=$1`, missionID).Scan(&jobs); err != nil {
		t.Fatal(err)
	}
	if jobs != 0 {
		t.Fatalf("approval created %d generation jobs", jobs)
	}
}

func minimalStoredPPTX(t *testing.T) []byte {
	t.Helper()
	var out bytes.Buffer
	archive := zip.NewWriter(&out)
	for _, name := range []string{"[Content_Types].xml", "ppt/presentation.xml"} {
		entry, err := archive.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write([]byte("<x/>")); err != nil {
			t.Fatal(err)
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}

func TestSaveMissionFileParseResultAtomicallySetsReady(t *testing.T) {
	f := newIntegrationFixture(t)
	var fileID, missionFileID int64
	if err := f.store.DB.QueryRow(f.ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,'source.txt','text/plain',6,$3) RETURNING id`, f.owner.ID, "parser-result-"+uuid.NewString(), fmt.Sprintf("%064x", 2)).Scan(&fileID); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `INSERT INTO mission_files(mission_id,file_object_id,role,provenance,uploaded_by) VALUES($1,$2,'MATERIAL','MATERIAL',$3) RETURNING id`, f.missionID, fileID, f.owner.ID).Scan(&missionFileID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		if _, err := f.store.DB.Exec(cleanupCtx, `DELETE FROM mission_files WHERE id=$1`, missionFileID); err != nil {
			t.Errorf("cleanup parser mission file: %v", err)
		}
		if _, err := f.store.DB.Exec(cleanupCtx, `DELETE FROM file_objects WHERE id=$1`, fileID); err != nil {
			t.Errorf("cleanup parser file object: %v", err)
		}
	})
	pageCount := 3
	result := model.ParseResult{
		Summary:        "parsed summary",
		Keywords:       []string{"tcp", "handshake"},
		TeachingStages: []string{"概念讲解"},
		AnalysisText:   "analysis",
		ExtractedText:  "source text",
		PageCount:      &pageCount,
		Sections:       []any{map[string]any{"title": "section"}},
	}
	if err := f.store.SaveMissionFileParseResult(f.ctx, f.owner.ID, missionFileID, result); err != nil {
		t.Fatal(err)
	}
	var status, summary, extracted string
	var storedPageCount *int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT mf.parse_status,r.summary,r.extracted_text,r.page_count FROM mission_files mf JOIN mission_file_parse_results r ON r.mission_file_id=mf.id WHERE mf.id=$1`, missionFileID).Scan(&status, &summary, &extracted, &storedPageCount); err != nil {
		t.Fatal(err)
	}
	if status != "READY" || summary != result.Summary || extracted != result.ExtractedText || storedPageCount == nil || *storedPageCount != pageCount {
		t.Fatalf("stored parser result = status=%q summary=%q extracted=%q pageCount=%v", status, summary, extracted, storedPageCount)
	}
	var parseEvents int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='FILE_PARSE_READY' AND reference_type='MISSION_FILE' AND reference_id=$2`, f.missionID, fmt.Sprint(missionFileID)).Scan(&parseEvents); err != nil {
		t.Fatal(err)
	}
	if parseEvents != 1 {
		t.Fatalf("parser completion activities after first save = %d, want 1", parseEvents)
	}
	f.store.afterCommitHook = func(scope string) error {
		if scope == "parser-result" {
			return errors.New("simulated lost parser commit acknowledgement")
		}
		return nil
	}
	if err := f.store.SaveMissionFileParseResult(f.ctx, f.owner.ID, missionFileID, result); err != nil {
		t.Fatalf("parser commit ambiguity was not reconciled: %v", err)
	}
	f.store.afterCommitHook = nil
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='FILE_PARSE_READY' AND reference_type='MISSION_FILE' AND reference_id=$2`, f.missionID, fmt.Sprint(missionFileID)).Scan(&parseEvents); err != nil {
		t.Fatal(err)
	}
	if parseEvents != 1 {
		t.Fatalf("parser completion activities after idempotent retry = %d, want 1", parseEvents)
	}
}

func TestCreateMissionAtomicOrdersMissionFileBindingActivity(t *testing.T) {
	store, ctx := integrationStore(t)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Mission activity test", "mission-activity-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	connection, err := store.CreateConnection(ctx, owner.ID, model.ModelConnection{Name: "Mission connection " + suffix, Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://provider.example.test", ModelID: "mission-model", KeyHint: "test"}, "encrypted-mission-key")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.DB.Exec(ctx, `UPDATE model_connections SET enabled=true,verification_status='VERIFIED' WHERE id=$1`, connection.ID); err != nil {
		t.Fatal(err)
	}
	fileSHA := fmt.Sprintf("%064x", 19)
	var fileID int64
	if err := store.DB.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,'source.pdf','application/pdf',12,$3) RETURNING id`, owner.ID, "mission-activity/"+suffix, fileSHA).Scan(&fileID); err != nil {
		t.Fatal(err)
	}
	uploadID := uuid.NewString()
	if _, err := store.DB.Exec(ctx, `INSERT INTO uploads(id,owner_user_id,file_object_id,status,expires_at) VALUES($1,$2,$3,'TEMPORARY',now()+interval '1 hour')`, uploadID, owner.ID, fileID); err != nil {
		t.Fatal(err)
	}
	var missionID int64
	t.Cleanup(func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, missionID); err != nil && missionID != 0 {
			t.Errorf("cleanup mission activity mission: %v", err)
		}
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM uploads WHERE id=$1`, uploadID); err != nil {
			t.Errorf("cleanup mission activity upload: %v", err)
		}
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM file_objects WHERE id=$1`, fileID); err != nil {
			t.Errorf("cleanup mission activity file object: %v", err)
		}
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM model_connections WHERE id=$1`, connection.ID); err != nil {
			t.Errorf("cleanup mission activity connection: %v", err)
		}
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, owner.ID); err != nil {
			t.Errorf("cleanup mission activity owner: %v", err)
		}
	})
	missionID, _, _, err = store.CreateMissionAtomic(ctx, owner.ID, "Activity ordering", "", "Create a lesson", []string{uploadID}, &connection.ID)
	if err != nil {
		t.Fatal(err)
	}
	rows, err := store.DB.Query(ctx, `SELECT event_type,reference_type,reference_id FROM activity_events WHERE mission_id=$1 ORDER BY id`, missionID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var got []string
	for rows.Next() {
		var eventType, referenceType, referenceID string
		if err := rows.Scan(&eventType, &referenceType, &referenceID); err != nil {
			t.Fatal(err)
		}
		got = append(got, eventType+":"+referenceType+":"+referenceID)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if len(got) != 4 || !strings.HasPrefix(got[0], "MISSION_CREATED:MISSION:") || got[1] != "MODEL_CONNECTION_SELECTED:MODEL_CONNECTION:"+fmt.Sprint(connection.ID) || !strings.HasPrefix(got[2], "MISSION_FILE_BOUND:MISSION_FILE:") || !strings.HasPrefix(got[3], "MESSAGE_CREATED:MESSAGE:") {
		t.Fatalf("mission activity order = %#v", got)
	}
	var uploadStatus string
	if err := store.DB.QueryRow(ctx, `SELECT status FROM uploads WHERE id=$1`, uploadID).Scan(&uploadStatus); err != nil {
		t.Fatal(err)
	}
	if uploadStatus != "BOUND" {
		t.Fatalf("bound upload status = %q", uploadStatus)
	}
}

func TestAnswerQuestionRollsBackAnswerWhenAgentRunCannotBeCreated(t *testing.T) {
	f := newIntegrationFixture(t)
	questionID := uuid.NewString()
	if _, err := f.store.DB.Exec(f.ctx, `INSERT INTO questions(id,mission_id,agent_run_id,text,question_type,options_json) VALUES($1,$2,$3,'Choose','TEXT','[]'::jsonb)`, questionID, f.missionID, f.agentRunID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE model_connections SET verification_status='INVALID' WHERE id=$1`, f.connection.ID); err != nil {
		t.Fatal(err)
	}

	if _, _, err := f.store.AnswerQuestion(f.ctx, f.owner.ID, questionID, "answer", nil); err == nil {
		t.Fatal("answer succeeded without a usable model connection")
	}
	var answers, userMessages, runs int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM question_answers WHERE question_id=$1`, questionID).Scan(&answers); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND role='USER'`, f.missionID).Scan(&userMessages); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM agent_runs WHERE mission_id=$1`, f.missionID).Scan(&runs); err != nil {
		t.Fatal(err)
	}
	if answers != 0 || userMessages != 1 || runs != 1 {
		t.Fatalf("failed answer transaction left side effects: answers=%d user_messages=%d runs=%d", answers, userMessages, runs)
	}
}

func TestAgentOutputFenceRejectsExpiredTokenAndCommitsNewTokenAtomically(t *testing.T) {
	f := newIntegrationFixture(t)
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET lease_expires_at=now()-interval '1 second' WHERE id=$1`, f.agentRunID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.AddAssistantMessageForRun(f.ctx, f.missionID, f.agentRunID, f.leaseToken, "stale", "TEXT", map[string]any{"type": "MESSAGE"}); err == nil {
		t.Fatal("expired agent token was accepted")
	}
	var messages int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND role='ASSISTANT'`, f.missionID).Scan(&messages); err != nil {
		t.Fatal(err)
	}
	if messages != 0 {
		t.Fatalf("stale assistant messages = %d", messages)
	}
	newToken := uuid.NewString()
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET lease_token=$1,lease_expires_at=now()+interval '10 minutes' WHERE id=$2`, newToken, f.agentRunID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.SaveQuestionForRun(f.ctx, f.missionID, f.agentRunID, newToken, "Choose", "SINGLE_CHOICE", []string{"A", "B"}); err != nil {
		t.Fatal(err)
	}
	if err := f.store.FinishAgentWithActivity(f.ctx, f.agentRunID, newToken, "COMPLETED", "", "", "AGENT_RUN_COMPLETED", "done", "AGENT_RUN", f.agentRunID); err != nil {
		t.Fatal(err)
	}
	if err := f.store.FinishAgentWithActivity(f.ctx, f.agentRunID, newToken, "COMPLETED", "", "", "AGENT_RUN_COMPLETED", "done", "AGENT_RUN", f.agentRunID); err != nil {
		t.Fatalf("idempotent final agent status: %v", err)
	}
	var assistant, questions, activities int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND role='ASSISTANT'`, f.missionID).Scan(&assistant); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM questions WHERE mission_id=$1`, f.missionID).Scan(&questions); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type IN ('MESSAGE_CREATED','QUESTION_CREATED','AGENT_RUN_COMPLETED')`, f.missionID).Scan(&activities); err != nil {
		t.Fatal(err)
	}
	if assistant != 1 || questions != 1 || activities != 3 {
		t.Fatalf("atomic agent output counts = assistant %d questions %d activities %d", assistant, questions, activities)
	}
}

func TestQuestionRunWaitsForAnswerAndIsNotRequeued(t *testing.T) {
	f := newIntegrationFixture(t)
	questionID, err := f.store.SaveQuestionForRun(f.ctx, f.missionID, f.agentRunID, f.leaseToken, "Choose a grade", "SINGLE_CHOICE", []string{"Middle school", "High school"})
	if err != nil {
		t.Fatal(err)
	}
	if err := f.store.FinishAgentWithActivity(f.ctx, f.agentRunID, f.leaseToken, "WAITING_INPUTS", "", "", "AGENT_RUN_WAITING_INPUTS", "waiting", "AGENT_RUN", f.agentRunID); err != nil {
		t.Fatal(err)
	}
	if err := f.store.QueueReadyRuns(f.ctx); err != nil {
		t.Fatal(err)
	}
	var status string
	if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM agent_runs WHERE id=$1`, f.agentRunID).Scan(&status); err != nil {
		t.Fatal(err)
	}
	if status != "WAITING_INPUTS" {
		t.Fatalf("question run status after queue pass = %s, want WAITING_INPUTS", status)
	}
	if _, _, err := f.store.AnswerQuestion(f.ctx, f.owner.ID, questionID, "", []string{"High school"}); err != nil {
		t.Fatal(err)
	}
	var runs, answers, queued int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM agent_runs WHERE mission_id=$1`, f.missionID).Scan(&runs); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM question_answers qa JOIN questions q ON q.id=qa.question_id WHERE q.mission_id=$1`, f.missionID).Scan(&answers); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM agent_runs WHERE mission_id=$1 AND status='QUEUED'`, f.missionID).Scan(&queued); err != nil {
		t.Fatal(err)
	}
	if runs != 2 || answers != 1 || queued != 1 {
		t.Fatalf("question answer transition = runs %d answers %d queued %d, want 2/1/1", runs, answers, queued)
	}
}

func TestCancelWaitingInputAgentRunIsDurable(t *testing.T) {
	f := newIntegrationFixture(t)
	questionID, err := f.store.SaveQuestionForRun(f.ctx, f.missionID, f.agentRunID, f.leaseToken, "Choose a format", "SINGLE_CHOICE", []string{"Lecture", "Workshop"})
	if err != nil {
		t.Fatal(err)
	}
	if err := f.store.FinishAgentWithActivity(f.ctx, f.agentRunID, f.leaseToken, "WAITING_INPUTS", "", "", "AGENT_RUN_WAITING_INPUTS", "waiting", "AGENT_RUN", f.agentRunID); err != nil {
		t.Fatal(err)
	}
	run, err := f.store.CancelAgent(f.ctx, f.owner.ID, f.agentRunID)
	if err != nil {
		t.Fatalf("cancel waiting agent run: %v", err)
	}
	if run.ID != f.agentRunID || run.Status != "CANCELLED" || run.ErrorCode != "AGENT_CANCELLED" {
		t.Fatalf("cancelled waiting run = %#v", run)
	}
	var status string
	if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM agent_runs WHERE id=$1`, f.agentRunID).Scan(&status); err != nil {
		t.Fatal(err)
	}
	if status != "CANCELLED" {
		t.Fatalf("question %s run status = %s", questionID, status)
	}
}

func TestAgentOutputFenceUsesRealClockInsideTransactionAndAllowsTakeover(t *testing.T) {
	f := newIntegrationFixture(t)
	f.store.LeaseDuration = 100 * time.Millisecond
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET status='FAILED',error_code='TEST_ISOLATED',finished_at=clock_timestamp(),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id<>$1 AND status IN ('QUEUED','RUNNING')`, f.agentRunID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET lease_expires_at=clock_timestamp()+interval '100 milliseconds' WHERE id=$1`, f.agentRunID); err != nil {
		t.Fatal(err)
	}
	entered := make(chan struct{})
	release := make(chan struct{})
	var releaseOnce sync.Once
	unlock := func() { releaseOnce.Do(func() { close(release) }) }
	t.Cleanup(unlock)
	f.store.beforeAgentOutputWriteHook = func() {
		close(entered)
		<-release
	}
	type result struct {
		id  int64
		err error
	}
	done := make(chan result, 1)
	go func() {
		id, err := f.store.AddAssistantMessageForRun(f.ctx, f.missionID, f.agentRunID, f.leaseToken, "stale", "TEXT", map[string]any{"type": "MESSAGE"})
		done <- result{id: id, err: err}
	}()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("agent output fence hook was not reached")
	}
	time.Sleep(150 * time.Millisecond)
	type claimResult struct {
		run     model.AgentRun
		err     error
		elapsed time.Duration
	}
	claimDone := make(chan claimResult, 1)
	claimStarted := time.Now()
	go func() {
		run, err := f.store.ClaimAgent(f.ctx)
		claimDone <- claimResult{run: run, err: err, elapsed: time.Since(claimStarted)}
	}()
	select {
	case result := <-claimDone:
		if !errors.Is(result.err, pgx.ErrNoRows) {
			t.Fatalf("takeover while old transaction held lock = %v, want no row via SKIP LOCKED", result.err)
		}
		if result.elapsed > time.Second {
			t.Fatalf("takeover while old transaction held lock blocked for %s", result.elapsed)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("new worker did not receive an immediate locked-row result")
	}
	unlock()
	got := <-done
	if got.err == nil || got.err.Error() != "AGENT_EXECUTION_FENCE_LOST" {
		t.Fatalf("expired in-transaction agent output result = %d/%v", got.id, got.err)
	}
	f.store.beforeAgentOutputWriteHook = nil
	var messages, activities int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND role='ASSISTANT'`, f.missionID).Scan(&messages); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='MESSAGE_CREATED'`, f.missionID).Scan(&activities); err != nil {
		t.Fatal(err)
	}
	if messages != 0 || activities != 0 {
		t.Fatalf("expired in-transaction agent side effects = messages %d activities %d", messages, activities)
	}
	claimed, err := f.store.ClaimAgent(f.ctx)
	if err != nil {
		t.Fatalf("new agent worker takeover: %v", err)
	}
	if claimed.ID != f.agentRunID || claimed.LeaseToken == f.leaseToken {
		t.Fatalf("takeover claim = %s/%s", claimed.ID, claimed.LeaseToken)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET lease_expires_at=clock_timestamp()+interval '10 minutes' WHERE id=$1 AND lease_token=$2`, claimed.ID, claimed.LeaseToken); err != nil {
		t.Fatal(err)
	}
	if err := f.store.FinishAgentWithActivity(f.ctx, f.agentRunID, f.leaseToken, "COMPLETED", "", "", "AGENT_RUN_COMPLETED", "stale", "AGENT_RUN", f.agentRunID); err == nil {
		t.Fatal("old token final fence unexpectedly succeeded after takeover")
	}
	if _, err := f.store.AddAssistantMessageForRun(f.ctx, f.missionID, claimed.ID, claimed.LeaseToken, "fresh", "TEXT", map[string]any{"type": "MESSAGE"}); err != nil {
		t.Fatalf("fresh agent output: %v", err)
	}
	var finalMessages, questions, drafts, finalActivities int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2`, f.missionID, f.agentRunID).Scan(&finalMessages); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM questions WHERE mission_id=$1 AND agent_run_id=$2`, f.missionID, f.agentRunID).Scan(&questions); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage IS NOT NULL`, f.missionID, f.agentRunID).Scan(&drafts); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND idempotency_key LIKE $2`, f.missionID, "agent-run:"+f.agentRunID+":%").Scan(&finalActivities); err != nil {
		t.Fatal(err)
	}
	if finalMessages != 1 || questions != 0 || drafts != 0 || finalActivities != 1 {
		t.Fatalf("old/new takeover side effects = messages %d questions %d drafts %d activities %d", finalMessages, questions, drafts, finalActivities)
	}
}

func TestGenerationFenceRejectsOldWorkerAndCommitsArtifactWithNewToken(t *testing.T) {
	f := newIntegrationFixture(t)
	data := []byte("fenced artifact")
	hash := sha256.Sum256(data)
	file := model.FileObject{OriginalName: "result.pptx", MimeType: "application/vnd.openxmlformats-officedocument.presentationml.presentation", Size: int64(len(data)), SHA256: fmt.Sprintf("%x", hash), StorageKey: "generation/" + uuid.NewString() + ".pptx"}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE generation_jobs SET lease_expires_at=now()-interval '1 second' WHERE id=$1`, f.generation); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.CommitGenerationArtifact(f.ctx, f.owner.ID, f.missionID, f.generation, f.leaseToken, file, file.MimeType, map[string]any{"test": "stale"}); err == nil {
		t.Fatal("expired generation token was accepted")
	}
	var artifacts, artifactFiles, readyActivities int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE generation_job_id=$1`, f.generation).Scan(&artifacts); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, file.StorageKey).Scan(&artifactFiles); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='ARTIFACT_READY'`, f.missionID).Scan(&readyActivities); err != nil {
		t.Fatal(err)
	}
	if artifacts != 0 || artifactFiles != 0 || readyActivities != 0 {
		t.Fatalf("stale generation side effects = artifacts %d files %d activities %d", artifacts, artifactFiles, readyActivities)
	}
	newToken := uuid.NewString()
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE generation_jobs SET lease_token=$1,lease_expires_at=now()+interval '10 minutes' WHERE id=$2`, newToken, f.generation); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.CommitGenerationArtifact(f.ctx, f.owner.ID, f.missionID, f.generation, newToken, file, file.MimeType, map[string]any{"test": "fresh"}); err != nil {
		var status, currentToken string
		var expires time.Time
		_ = f.store.DB.QueryRow(f.ctx, `SELECT status,lease_token,lease_expires_at FROM generation_jobs WHERE id=$1`, f.generation).Scan(&status, &currentToken, &expires)
		t.Fatalf("fresh generation commit: %v (state %s token-match=%t expires-in=%s)", err, status, currentToken == newToken, time.Until(expires).Round(time.Second))
	}
	var freshArtifactID string
	if err := f.store.DB.QueryRow(f.ctx, `SELECT id FROM artifacts WHERE generation_job_id=$1`, f.generation).Scan(&freshArtifactID); err != nil {
		t.Fatal(err)
	}
	if err := f.store.FinalizeGenerationArtifact(f.ctx, f.owner.ID, f.missionID, f.generation, freshArtifactID); err != nil {
		t.Fatalf("fresh generation finalize: %v", err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE generation_job_id=$1`, f.generation).Scan(&artifacts); err != nil {
		t.Fatal(err)
	}
	if artifacts != 1 {
		t.Fatalf("fresh generation artifacts = %d", artifacts)
	}
}

func TestGenerationFenceUsesRealClockInsideTransactionAndAllowsTakeover(t *testing.T) {
	f := newIntegrationFixture(t)
	f.store.LeaseDuration = 100 * time.Millisecond
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE generation_jobs SET lease_expires_at=clock_timestamp()+interval '100 milliseconds' WHERE id=$1`, f.generation); err != nil {
		t.Fatal(err)
	}
	data := []byte("fenced artifact")
	hash := sha256.Sum256(data)
	file := model.FileObject{OriginalName: "result.pptx", MimeType: "application/vnd.openxmlformats-officedocument.presentationml.presentation", Size: int64(len(data)), SHA256: fmt.Sprintf("%x", hash), StorageKey: "generated/" + f.generation + ".pptx"}
	entered := make(chan struct{})
	release := make(chan struct{})
	f.store.beforeGenerationArtifactWriteHook = func() {
		close(entered)
		<-release
	}
	done := make(chan error, 1)
	go func() {
		_, err := f.store.CommitGenerationArtifact(f.ctx, f.owner.ID, f.missionID, f.generation, f.leaseToken, file, file.MimeType, map[string]any{"test": "stale"})
		done <- err
	}()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("generation artifact fence hook was not reached")
	}
	time.Sleep(150 * time.Millisecond)
	claimDone := make(chan error, 1)
	go func() {
		_, err := f.store.ClaimGeneration(f.ctx)
		claimDone <- err
	}()
	select {
	case err := <-claimDone:
		if !errors.Is(err, pgx.ErrNoRows) {
			t.Fatalf("generation takeover while old transaction held lock = %v, want no row via SKIP LOCKED", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("generation worker B did not receive an immediate locked-row result")
	}
	close(release)
	if err := <-done; err == nil || err.Error() != "GENERATION_EXECUTION_FENCE_LOST" {
		t.Fatalf("expired in-transaction generation result = %v", err)
	}
	f.store.beforeGenerationArtifactWriteHook = nil
	var artifacts, files, activities int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE generation_job_id=$1`, f.generation).Scan(&artifacts); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, file.StorageKey).Scan(&files); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='ARTIFACT_READY'`, f.missionID).Scan(&activities); err != nil {
		t.Fatal(err)
	}
	if artifacts != 0 || files != 0 || activities != 0 {
		t.Fatalf("expired in-transaction generation side effects = artifacts %d files %d activities %d", artifacts, files, activities)
	}
	// The first lease intentionally expires quickly; give the replacement claim
	// a normal window so this assertion tests fencing rather than test latency.
	f.store.LeaseDuration = 10 * time.Second
	claimed, err := f.store.ClaimGeneration(f.ctx)
	if err != nil {
		t.Fatalf("new generation worker takeover: %v", err)
	}
	if claimed.ID != f.generation || claimed.LeaseToken == f.leaseToken {
		t.Fatalf("generation takeover claim = %s/%s", claimed.ID, claimed.LeaseToken)
	}
	if _, err := f.store.CommitGenerationArtifact(f.ctx, f.owner.ID, f.missionID, claimed.ID, claimed.LeaseToken, file, file.MimeType, map[string]any{"test": "fresh"}); err != nil {
		t.Fatalf("fresh generation artifact: %v", err)
	}
	if err := f.store.FinalizeGenerationArtifact(f.ctx, f.owner.ID, f.missionID, claimed.ID, mustArtifactID(t, f.store, claimed.ID)); err != nil {
		t.Fatalf("fresh generation finalize after takeover: %v", err)
	}
}

func TestGenerationCommitAmbiguityReconcilesAndPreservesArtifact(t *testing.T) {
	f := newIntegrationFixture(t)
	data := []byte("committed artifact")
	hash := sha256.Sum256(data)
	file := model.FileObject{OriginalName: "result.pptx", MimeType: "application/vnd.openxmlformats-officedocument.presentationml.presentation", Size: int64(len(data)), SHA256: fmt.Sprintf("%x", hash), StorageKey: "generated/" + f.generation + ".pptx"}
	called := false
	f.store.afterCommitHook = func(scope string) error {
		if scope == "generation-artifact" && !called {
			called = true
			return errors.New("simulated lost commit acknowledgement")
		}
		return nil
	}
	artifact, err := f.store.CommitGenerationArtifact(f.ctx, f.owner.ID, f.missionID, f.generation, f.leaseToken, file, file.MimeType, map[string]any{"test": "ambiguous"})
	if err != nil {
		t.Fatalf("commit ambiguity was not reconciled: %v", err)
	}
	if !called {
		t.Fatal("commit ambiguity seam was not exercised")
	}
	if _, err := f.store.Artifact(f.ctx, f.owner.ID, artifact.ID); err == nil {
		t.Fatal("staged artifact was visible before physical verification")
	}
	if err := f.store.FinalizeGenerationArtifact(f.ctx, f.owner.ID, f.missionID, f.generation, artifact.ID); err != nil {
		t.Fatalf("reconciled artifact finalize: %v", err)
	}
	if got, err := f.store.Artifact(f.ctx, f.owner.ID, artifact.ID); err != nil || got.File.StorageKey != file.StorageKey {
		t.Fatalf("reconciled artifact = %+v/%v", got, err)
	}
	retried, err := f.store.CommitGenerationArtifact(f.ctx, f.owner.ID, f.missionID, f.generation, f.leaseToken, file, file.MimeType, map[string]any{"test": "retry"})
	if err != nil || retried.ID != artifact.ID {
		t.Fatalf("idempotent generation retry = %s/%v, want %s", retried.ID, err, artifact.ID)
	}
	var artifactCount, fileCount, activityCount int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE generation_job_id=$1`, f.generation).Scan(&artifactCount); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, file.StorageKey).Scan(&fileCount); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND idempotency_key=$2`, f.missionID, "generation-job:"+f.generation+":artifact").Scan(&activityCount); err != nil {
		t.Fatal(err)
	}
	if artifactCount != 1 || fileCount != 1 || activityCount != 1 {
		t.Fatalf("reconciled generation duplicates = artifact %d file %d activity %d", artifactCount, fileCount, activityCount)
	}
}

func TestAgentOutputReconciliationIsExactByRunAndStageAcrossAmbiguity(t *testing.T) {
	f := newIntegrationFixture(t)
	if _, err := f.store.AddAssistantMessageForRun(f.ctx, f.missionID, f.agentRunID, f.leaseToken, "message stage", "TEXT", map[string]any{"type": "MESSAGE"}); err != nil {
		t.Fatal(err)
	}
	found, err := f.store.ReconcileAgentOutput(f.ctx, f.missionID, f.agentRunID, AgentOutputStageQuestion)
	if err != nil {
		t.Fatal(err)
	}
	if found {
		t.Fatal("message stage was mistaken for question stage")
	}
	tx, err := f.store.DB.Begin(f.ctx)
	if err != nil {
		t.Fatal(err)
	}
	f.store.afterCommitHook = func(scope string) error {
		if scope == "synthetic-question-ambiguity" {
			return errors.New("lost acknowledgement")
		}
		return nil
	}
	err = f.store.commitAgentOutputTx(f.ctx, tx, f.missionID, f.agentRunID, AgentOutputStageQuestion, "synthetic-question-ambiguity")
	f.store.afterCommitHook = nil
	if !errors.Is(err, ErrCommitAmbiguous) {
		t.Fatalf("stage-A output incorrectly reconciled as stage-B: %v", err)
	}
	f.store.afterCommitHook = func(scope string) error {
		if scope == "agent-question" {
			return errors.New("lost acknowledgement")
		}
		return nil
	}
	questionID, err := f.store.SaveQuestionForRun(f.ctx, f.missionID, f.agentRunID, f.leaseToken, "question stage", "TEXT", nil)
	f.store.afterCommitHook = nil
	if err != nil || questionID == "" {
		t.Fatalf("stage-B retry after ambiguous result = %s/%v", questionID, err)
	}
	retriedID, err := f.store.SaveQuestionForRun(f.ctx, f.missionID, f.agentRunID, f.leaseToken, "different text must not duplicate", "TEXT", nil)
	if err != nil || retriedID != questionID {
		t.Fatalf("same stage retry = %s/%v, want %s", retriedID, err, questionID)
	}
	var messages, questions, questionMessages int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='MESSAGE'`, f.missionID, f.agentRunID).Scan(&messages); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM questions WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION'`, f.missionID, f.agentRunID).Scan(&questions); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION_MESSAGE'`, f.missionID, f.agentRunID).Scan(&questionMessages); err != nil {
		t.Fatal(err)
	}
	if messages != 1 || questions != 1 || questionMessages != 1 {
		t.Fatalf("stage-isolated output counts = message %d question %d question-message %d", messages, questions, questionMessages)
	}
}

func TestAgentOutputIdempotencyAcrossRetryLeaseAndCancellation(t *testing.T) {
	f := newIntegrationFixture(t)
	messageID, err := f.store.AddAssistantMessageForRun(f.ctx, f.missionID, f.agentRunID, f.leaseToken, "same", "TEXT", map[string]any{"type": "MESSAGE"})
	if err != nil {
		t.Fatal(err)
	}
	retriedID, err := f.store.AddAssistantMessageForRun(f.ctx, f.missionID, f.agentRunID, f.leaseToken, "same", "TEXT", map[string]any{"type": "MESSAGE"})
	if err != nil || retriedID != messageID {
		t.Fatalf("idempotent agent message = %d/%v, want %d", retriedID, err, messageID)
	}
	newToken := uuid.NewString()
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET lease_token=$1,lease_expires_at=clock_timestamp()+interval '10 minutes' WHERE id=$2`, newToken, f.agentRunID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.SaveQuestionForRun(f.ctx, f.missionID, f.agentRunID, f.leaseToken, "stale", "TEXT", nil); err == nil {
		t.Fatal("old agent token created a new output")
	}
	questionID, err := f.store.SaveQuestionForRun(f.ctx, f.missionID, f.agentRunID, newToken, "choose", "SINGLE_CHOICE", []string{"A", "B"})
	if err != nil {
		t.Fatal(err)
	}
	retriedQuestion, err := f.store.SaveQuestionForRun(f.ctx, f.missionID, f.agentRunID, newToken, "choose", "SINGLE_CHOICE", []string{"A", "B"})
	if err != nil || retriedQuestion != questionID {
		t.Fatalf("idempotent agent question = %s/%v, want %s", retriedQuestion, err, questionID)
	}
	if _, err := f.store.CancelAgent(f.ctx, f.owner.ID, f.agentRunID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.SaveDraftForRun(f.ctx, f.missionID, f.agentRunID, newToken, "stale draft", map[string]any{"slides": []any{}}); err == nil {
		t.Fatal("cancelled agent token created a draft")
	}
	var messages, questions, drafts, activities int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2`, f.missionID, f.agentRunID).Scan(&messages); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM questions WHERE mission_id=$1 AND agent_run_id=$2`, f.missionID, f.agentRunID).Scan(&questions); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage IS NOT NULL`, f.missionID, f.agentRunID).Scan(&drafts); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND idempotency_key LIKE $2`, f.missionID, "agent-run:"+f.agentRunID+":%").Scan(&activities); err != nil {
		t.Fatal(err)
	}
	if messages != 2 || questions != 1 || drafts != 0 || activities != 3 {
		t.Fatalf("agent output idempotency counts = messages %d questions %d drafts %d activities %d", messages, questions, drafts, activities)
	}
}

func TestConnectionDisableCancelsOldRunAndSelectionCreatesOneFrozenReplacement(t *testing.T) {
	f := newIntegrationFixture(t)
	connectionB, err := f.store.CreateConnection(f.ctx, f.owner.ID, model.ModelConnection{Name: "Replacement connection " + uuid.NewString(), Protocol: "OPENAI_COMPATIBLE", BaseURL: "http://127.0.0.1:2", ModelID: "replacement-model", KeyHint: "test"}, "test-encrypted-key-b")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE model_connections SET enabled=true,verification_status='VERIFIED' WHERE id=$1`, connectionB.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.SetConnectionEnabled(f.ctx, f.owner.ID, f.connection.ID, false); err != nil {
		t.Fatal(err)
	}
	var selected *int64
	if err := f.store.DB.QueryRow(f.ctx, `SELECT selected_model_connection_id FROM missions WHERE id=$1`, f.missionID).Scan(&selected); err != nil {
		t.Fatal(err)
	}
	if selected != nil {
		t.Fatalf("disabled connection remained selected: %d", *selected)
	}
	var clearedEvents int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='MODEL_CONNECTION_CLEARED' AND reference_type='MODEL_CONNECTION' AND reference_id IS NULL`, f.missionID).Scan(&clearedEvents); err != nil {
		t.Fatal(err)
	}
	if clearedEvents != 1 {
		t.Fatalf("connection disable clear activities = %d, want 1", clearedEvents)
	}
	var oldStatus, oldCode string
	if err := f.store.DB.QueryRow(f.ctx, `SELECT status,error_code FROM agent_runs WHERE id=$1`, f.agentRunID).Scan(&oldStatus, &oldCode); err != nil {
		t.Fatal(err)
	}
	if oldStatus != "CANCELLED" || oldCode != "AGENT_RUN_CONNECTION_DISABLED" {
		t.Fatalf("old run after disable = %s/%s", oldStatus, oldCode)
	}
	newRunID, err := f.store.SetMissionConnection(f.ctx, f.owner.ID, f.missionID, &connectionB.ID)
	if err != nil {
		t.Fatal(err)
	}
	if newRunID == "" {
		t.Fatal("connection replacement did not create a run")
	}
	var selectedEvents int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='MODEL_CONNECTION_SELECTED' AND reference_type='MODEL_CONNECTION' AND reference_id=$2`, f.missionID, fmt.Sprint(connectionB.ID)).Scan(&selectedEvents); err != nil {
		t.Fatal(err)
	}
	if selectedEvents != 1 {
		t.Fatalf("model connection selection activities = %d, want 1", selectedEvents)
	}
	var snapshot []byte
	var status string
	if err := f.store.DB.QueryRow(f.ctx, `SELECT status,model_identity_snapshot FROM agent_runs WHERE id=$1`, newRunID).Scan(&status, &snapshot); err != nil {
		t.Fatal(err)
	}
	if status != "QUEUED" {
		t.Fatalf("replacement run status = %s", status)
	}
	var identity map[string]any
	if err := json.Unmarshal(snapshot, &identity); err != nil {
		t.Fatal(err)
	}
	if int64(identity["connectionId"].(float64)) != connectionB.ID || identity["modelId"] != "replacement-model" {
		t.Fatalf("replacement snapshot = %v", identity)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET status='FAILED',error_code='TEST_SUPERSEDED',finished_at=clock_timestamp(),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id<>$1 AND status IN ('QUEUED','RUNNING')`, newRunID); err != nil {
		t.Fatal(err)
	}
	claimed, err := f.store.ClaimAgent(f.ctx)
	if err != nil {
		t.Fatal(err)
	}
	if claimed.ID != newRunID || claimed.ModelConnectionID == nil || *claimed.ModelConnectionID != connectionB.ID {
		t.Fatalf("claimed run = %s/%v", claimed.ID, claimed.ModelConnectionID)
	}
}

func nullableTestValue(enabled bool, value string) any {
	if enabled {
		return value
	}
	return nil
}

func mustArtifactID(t *testing.T, store *Store, jobID string) string {
	t.Helper()
	var artifactID string
	if err := store.DB.QueryRow(context.Background(), `SELECT id FROM artifacts WHERE generation_job_id=$1`, jobID).Scan(&artifactID); err != nil {
		t.Fatal(err)
	}
	return artifactID
}
