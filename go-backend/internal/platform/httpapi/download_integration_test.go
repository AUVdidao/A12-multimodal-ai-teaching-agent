package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
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
	"lessonforge.local/backend/internal/agent"
	"lessonforge.local/backend/internal/auth"
	"lessonforge.local/backend/internal/generation"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/platform/crypto"
	"lessonforge.local/backend/internal/platform/database"
	"lessonforge.local/backend/internal/platform/storage"
	"lessonforge.local/backend/internal/pptengine"
)

type downloadFixture struct {
	ctx          context.Context
	store        *database.Store
	ownerID      int64
	artifact     model.Artifact
	files        *storage.Service
	root         string
	data         []byte
	token        string
	workerResult error
}

func TestDownloadRevalidatesAndServesHealthyReadyArtifact(t *testing.T) {
	f, cleanup := newDownloadFixture(t)
	defer cleanup()
	server := NewServer(Config{SessionCookie: "session"}, f.store.DB, f.store, nil, f.files, nil, nil)
	tcp := httptest.NewServer(server.Router())
	defer tcp.Close()
	req, err := http.NewRequest(http.MethodGet, tcp.URL+"/api/artifacts/"+f.artifact.ID+"/download", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+f.token)
	response, err := tcp.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || response.Header.Get("Content-Length") != strconv.Itoa(len(f.data)) || string(body) != string(f.data) {
		t.Fatalf("healthy TCP download = %d length=%q body=%q", response.StatusCode, response.Header.Get("Content-Length"), string(body))
	}
	visible, err := f.store.Artifacts(f.ctx, f.ownerID, f.artifact.MissionID)
	if err != nil || len(visible) != 1 || visible[0].Status != database.ArtifactStatusReady {
		t.Fatalf("healthy artifact visibility = %d/%v", len(visible), err)
	}
}

// newDownloadFixture deliberately uses the real Store commit/finalize path so
// the HTTP test starts from the same READY/SUCCEEDED contract as a worker.
func newDownloadFixture(t *testing.T, beforeWorker ...func(*database.Store)) (*downloadFixture, func()) {
	t.Helper()
	dsn := os.Getenv("LESSONFORGE_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("LESSONFORGE_TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	pool, err := database.Open(ctx, dsn)
	if err != nil {
		cancel()
		t.Fatalf("open download integration database: %v", err)
	}
	if err := database.Migrate(ctx, pool, filepath.Join("..", "..", "..", "migrations")); err != nil {
		pool.Close()
		cancel()
		t.Fatalf("migrate download integration database: %v", err)
	}
	unlock, err := database.AcquireIntegrationTestLock(ctx, pool)
	if err != nil {
		pool.Close()
		cancel()
		t.Fatal(err)
	}
	store := database.NewStore(pool)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Download test", "download-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		unlock()
		pool.Close()
		cancel()
		t.Fatal(err)
	}
	var missionID, messageID int64
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title) VALUES($1,'SELF_CREATED','Download test') RETURNING id`, owner.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','start') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_identity_snapshot,status) VALUES($1,$2,$3,'{}'::jsonb,'COMPLETED')`, runID, missionID, messageID); err != nil {
		t.Fatal(err)
	}
	draftID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id,output_stage) VALUES($1,$2,1,'download','{"slides":[]}'::jsonb,$3,'PLAN_DRAFT')`, draftID, missionID, runID); err != nil {
		t.Fatal(err)
	}
	specID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO locked_specifications(id,mission_id,source_draft_id,version,specification_json,template_binding_json,content_hash) VALUES($1,$2,$3,1,'{"slides":[]}'::jsonb,'{}'::jsonb,$4)`, specID, missionID, draftID, strings.Repeat("1", 64)); err != nil {
		t.Fatal(err)
	}
	jobID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO generation_jobs(id,mission_id,specification_id,status) VALUES($1,$2,$3,'QUEUED')`, jobID, missionID, specID); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	files, err := storage.New(filepath.Join(root, "stored"), 200*1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("downloadable-ready-artifact")
	hash := sha256.Sum256(data)
	enginePath := filepath.Join(root, "run", "result.pptx")
	if err := os.MkdirAll(filepath.Dir(enginePath), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(enginePath, data, 0o640); err != nil {
		t.Fatal(err)
	}
	engine := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if _, err := w.Write([]byte(`{"status":"SUCCEEDED","artifacts":[{"artifactId":"worker-artifact","artifactType":"PPTX","storageKey":"run/result.pptx","sha256":"` + hex.EncodeToString(hash[:]) + `","fileSize":` + strconv.Itoa(len(data)) + `,"mediaType":"application/vnd.openxmlformats-officedocument.presentationml.presentation"}]}`)); err != nil {
			t.Errorf("engine fixture response: %v", err)
		}
	}))
	for _, configure := range beforeWorker {
		configure(store)
	}
	worker := &generation.Worker{Store: store, Engine: pptengine.NewClient(engine.URL, root, time.Second), Storage: files, OwnerResolver: func(context.Context, int64) (int64, error) { return owner.ID, nil }}
	workerResult := worker.RunOnce(ctx)
	artifacts, err := store.Artifacts(ctx, owner.ID, missionID)
	if err != nil {
		t.Fatal(err)
	}
	if len(artifacts) != 1 {
		t.Fatalf("worker did not produce one visible READY artifact: %d", len(artifacts))
	}
	artifact := artifacts[0]
	if artifact.File.SHA256 != hex.EncodeToString(hash[:]) {
		t.Fatalf("fixture hash = %s", artifact.File.SHA256)
	}
	token, err := auth.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if err := store.CreateSession(ctx, owner.ID, auth.TokenHash(token), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	fixture := &downloadFixture{ctx: ctx, store: store, ownerID: owner.ID, artifact: artifact, files: files, root: root, data: data, token: token, workerResult: workerResult}
	cleanup := func() {
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
		unlock()
		engine.Close()
		pool.Close()
		cancel()
	}
	return fixture, cleanup
}

func TestGenerationWorkerFinalizeAmbiguityReconcilesThenServesTCPDownload(t *testing.T) {
	ambiguous := false
	f, cleanup := newDownloadFixture(t, func(store *database.Store) {
		store.SetAfterCommitHookForTest(func(scope string) error {
			if scope == "generation-finalize" && !ambiguous {
				ambiguous = true
				return errors.New("finalize acknowledgement interrupted")
			}
			return nil
		})
	})
	defer cleanup()
	if !ambiguous {
		t.Fatal("finalize acknowledgement ambiguity was not injected through the real worker")
	}
	if !errors.Is(f.workerResult, database.ErrCommitAmbiguous) {
		t.Fatalf("first Worker.RunOnce result = %v, want acknowledgement ambiguity", f.workerResult)
	}
	// The first worker cannot know whether finalization committed. A fresh
	// production RunOnce performs the ordinary staged-recovery/claim pass
	// against the database state left by that unknown result; it must neither
	// regenerate nor disturb the committed receipt.
	recoveryWorker := &generation.Worker{Store: f.store, Engine: pptengine.NewClient("http://127.0.0.1:1", f.root, time.Second), Storage: f.files, OwnerResolver: func(context.Context, int64) (int64, error) { return f.ownerID, nil }}
	if err := recoveryWorker.RunOnce(f.ctx); err != nil {
		t.Fatalf("next real Worker.RunOnce recovery = %v", err)
	}
	server := NewServer(Config{SessionCookie: "session"}, f.store.DB, f.store, nil, f.files, nil, nil)
	tcp := httptest.NewServer(server.Router())
	defer tcp.Close()
	response := downloadOverTCP(t, tcp, f)
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || string(body) != string(f.data) {
		t.Fatalf("finalize ambiguity download = %d/%q", response.StatusCode, string(body))
	}
	assertReadyGenerationRows(t, f)
}

func TestDownloadFailsClosedWhenProtectedHandleChangesBeforeSend(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(string) error
	}{
		{"replace", func(path string) error { return os.WriteFile(path, []byte("replacement-bytes"), 0o640) }},
		{"truncate", func(path string) error { return os.WriteFile(path, []byte("x"), 0o640) }},
		{"delete", os.Remove},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			f, cleanup := newDownloadFixture(t)
			defer cleanup()
			server := NewServer(Config{SessionCookie: "session"}, f.store.DB, f.store, nil, f.files, nil, nil)
			server.afterDownloadOpenHook = func() error {
				return test.mutate(filepath.Join(f.filesRoot(), filepath.FromSlash(f.artifact.File.StorageKey)))
			}
			tcp := httptest.NewServer(server.Router())
			defer tcp.Close()
			response := downloadOverTCP(t, tcp, f)
			defer response.Body.Close()
			body, err := io.ReadAll(response.Body)
			if err != nil {
				t.Fatal(err)
			}
			if response.StatusCode != http.StatusConflict || !strings.Contains(string(body), "ARTIFACT_FILE_INTEGRITY_FAILED") || strings.Contains(string(body), "replacement-bytes") {
				t.Fatalf("protected-handle %s download = %d/%q", test.name, response.StatusCode, string(body))
			}
			assertInvalidGenerationRows(t, f)
		})
	}
}

func TestDownloadFailsClosedWhenFileChangesAfterFinalVerify(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(string) error
	}{
		{"replace", func(path string) error { return os.WriteFile(path, []byte("replacement-after-final-verify"), 0o640) }},
		{"truncate", func(path string) error { return os.WriteFile(path, []byte("x"), 0o640) }},
		{"delete", os.Remove},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			f, cleanup := newDownloadFixture(t)
			defer cleanup()
			server := NewServer(Config{SessionCookie: "session"}, f.store.DB, f.store, nil, f.files, nil, nil)
			server.beforeDownloadSendHook = func() error {
				return test.mutate(filepath.Join(f.filesRoot(), filepath.FromSlash(f.artifact.File.StorageKey)))
			}
			tcp := httptest.NewServer(server.Router())
			defer tcp.Close()
			response := downloadOverTCP(t, tcp, f)
			defer response.Body.Close()
			body, err := io.ReadAll(response.Body)
			if err != nil {
				t.Fatal(err)
			}
			// The hook runs after the final Verify and before response headers. The
			// handler can therefore only write the already receipt-checked bytes it
			// holds from the protected descriptor, never the replacement/truncate
			// bytes now present at the storage path.
			if response.StatusCode != http.StatusOK || response.Header.Get("Content-Length") != strconv.Itoa(len(f.data)) || string(body) != string(f.data) {
				t.Fatalf("post-final-verify %s response = %d length=%q body=%q", test.name, response.StatusCode, response.Header.Get("Content-Length"), string(body))
			}
		})
	}
}

func TestDownloadHandlesShortWriteAndCompensates(t *testing.T) {
	f, cleanup := newDownloadFixture(t)
	defer cleanup()
	server := NewServer(Config{SessionCookie: "session"}, f.store.DB, f.store, nil, f.files, nil, nil)
	request := httptest.NewRequest(http.MethodGet, "/api/artifacts/"+f.artifact.ID+"/download", nil)
	request.Header.Set("Authorization", "Bearer "+f.token)
	writer := &shortWriteResponseWriter{}
	server.Router().ServeHTTP(writer, request)
	if writer.status != http.StatusOK || len(writer.body) >= len(f.data) {
		t.Fatalf("short write response = status %d bytes %d/%d", writer.status, len(writer.body), len(f.data))
	}
	assertInvalidGenerationRows(t, f)
	if _, err := f.files.Open(f.ctx, f.artifact.File.StorageKey); !os.IsNotExist(err) {
		t.Fatalf("short write left physical artifact: %v", err)
	}
}

func TestDownloadHandlesRealTCPClientDisconnectAndCompensates(t *testing.T) {
	f, cleanup := newDownloadFixture(t)
	defer cleanup()
	server := NewServer(Config{SessionCookie: "session"}, f.store.DB, f.store, nil, f.files, nil, nil)
	entered := make(chan struct{})
	release := make(chan struct{})
	var enteredOnce sync.Once
	server.beforeDownloadSendHook = func() error {
		enteredOnce.Do(func() { close(entered) })
		<-release
		return nil
	}
	writeOrFlushErr := make(chan error, 2)
	server.downloadSendErrorHook = func(err error) { writeOrFlushErr <- err }
	tcp := httptest.NewServer(server.Router())
	defer tcp.Close()
	conn, err := net.Dial("tcp", strings.TrimPrefix(tcp.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := fmt.Fprintf(conn, "GET /api/artifacts/%s/download HTTP/1.1\r\nHost: %s\r\nAuthorization: Bearer %s\r\nConnection: close\r\n\r\n", f.artifact.ID, strings.TrimPrefix(tcp.URL, "http://"), f.token); err != nil {
		t.Fatal(err)
	}
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("download handler did not reach final Verify-to-send window")
	}
	// This is a real client-side TCP RST, not a server Conn wrapper. Linger=0
	// makes Close discard unsent data and reset the established connection.
	if tcpConn, ok := conn.(*net.TCPConn); ok {
		if err := tcpConn.SetLinger(0); err != nil {
			t.Fatal(err)
		}
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}
	close(release)
	select {
	case err := <-writeOrFlushErr:
		if err == nil {
			t.Fatal("server did not surface a TCP send error")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("server did not observe a Write or Flush error after client RST")
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		var status string
		if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM generation_jobs WHERE id=$1`, f.artifact.GenerationJobID).Scan(&status); err != nil {
			t.Fatal(err)
		}
		if status == "FAILED" {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	assertInvalidGenerationRows(t, f)
	if _, err := f.files.Open(f.ctx, f.artifact.File.StorageKey); !os.IsNotExist(err) {
		t.Fatalf("real TCP disconnect left physical artifact: %v", err)
	}
}

type shortWriteResponseWriter struct {
	header http.Header
	status int
	body   []byte
}

func (w *shortWriteResponseWriter) Header() http.Header {
	if w.header == nil {
		w.header = make(http.Header)
	}
	return w.header
}

func (w *shortWriteResponseWriter) WriteHeader(status int) { w.status = status }

func (w *shortWriteResponseWriter) Write(body []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	n := len(body) / 2
	if n == 0 && len(body) > 0 {
		n = 1
	}
	w.body = append(w.body, body[:n]...)
	return n, io.ErrShortWrite
}

func downloadOverTCP(t *testing.T, tcp *httptest.Server, f *downloadFixture) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, tcp.URL+"/api/artifacts/"+f.artifact.ID+"/download", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+f.token)
	response, err := tcp.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return response
}

func assertReadyGenerationRows(t *testing.T, f *downloadFixture) {
	t.Helper()
	var status string
	var artifacts, files, readyActivities int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM generation_jobs WHERE id=$1`, f.artifact.GenerationJobID).Scan(&status); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE id=$1`, f.artifact.ID).Scan(&artifacts); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, f.artifact.File.StorageKey).Scan(&files); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='ARTIFACT_READY'`, f.artifact.MissionID).Scan(&readyActivities); err != nil {
		t.Fatal(err)
	}
	if status != "SUCCEEDED" || artifacts != 1 || files != 1 || readyActivities != 1 {
		t.Fatalf("ready worker chain = status %s artifacts %d files %d ready %d", status, artifacts, files, readyActivities)
	}
}

func assertInvalidGenerationRows(t *testing.T, f *downloadFixture) {
	t.Helper()
	var status string
	var artifacts, files, ready, invalid int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM generation_jobs WHERE id=$1`, f.artifact.GenerationJobID).Scan(&status); err != nil {
		t.Fatal(err)
	}
	for _, query := range []struct {
		query string
		arg   any
		out   *int
	}{
		{`SELECT count(*) FROM artifacts WHERE id=$1`, f.artifact.ID, &artifacts},
		{`SELECT count(*) FROM file_objects WHERE storage_key=$1`, f.artifact.File.StorageKey, &files},
		{`SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='ARTIFACT_READY'`, f.artifact.MissionID, &ready},
		{`SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='ARTIFACT_INVALID'`, f.artifact.MissionID, &invalid},
	} {
		if err := f.store.DB.QueryRow(f.ctx, query.query, query.arg).Scan(query.out); err != nil {
			t.Fatal(err)
		}
	}
	if status != "FAILED" || artifacts != 0 || files != 0 || ready != 0 || invalid != 1 {
		t.Fatalf("invalid worker chain = status %s artifacts %d files %d ready %d invalid %d", status, artifacts, files, ready, invalid)
	}
}

func TestDownloadRevalidatesReadyArtifactAndFailsClosedOnPhysicalDamage(t *testing.T) {
	cases := []struct {
		name      string
		breakFile func(string) error
	}{
		{name: "replace", breakFile: func(path string) error { return os.WriteFile(path, []byte("replacement-bytes"), 0o640) }},
		{name: "truncate", breakFile: func(path string) error { return os.WriteFile(path, []byte("x"), 0o640) }},
		{name: "missing", breakFile: os.Remove},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			f, cleanup := newDownloadFixture(t)
			defer cleanup()
			path := filepath.Join(f.filesRoot(), filepath.FromSlash(f.artifact.File.StorageKey))
			if err := test.breakFile(path); err != nil {
				t.Fatal(err)
			}
			server := NewServer(Config{SessionCookie: "session"}, f.store.DB, f.store, nil, f.files, nil, nil)
			req := httptest.NewRequest(http.MethodGet, "/api/artifacts/"+f.artifact.ID+"/download", nil)
			req.Header.Set("Authorization", "Bearer "+f.token)
			recorder := httptest.NewRecorder()
			server.Router().ServeHTTP(recorder, req)
			if recorder.Code != http.StatusConflict {
				t.Fatalf("download status = %d, body=%s", recorder.Code, recorder.Body.String())
			}
			var response map[string]map[string]string
			if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil || response["error"]["code"] != "ARTIFACT_FILE_INTEGRITY_FAILED" {
				t.Fatalf("download error = %s", recorder.Body.String())
			}
			if strings.Contains(recorder.Body.String(), "replacement-bytes") || strings.Contains(recorder.Body.String(), "x") {
				t.Fatal("damaged physical bytes were exposed")
			}
			var jobStatus string
			if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM generation_jobs WHERE id=$1`, f.artifact.GenerationJobID).Scan(&jobStatus); err != nil {
				t.Fatal(err)
			}
			var artifacts, files, ready, invalid int
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM artifacts WHERE id=$1`, f.artifact.ID).Scan(&artifacts); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE storage_key=$1`, f.artifact.File.StorageKey).Scan(&files); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='ARTIFACT_READY'`, f.artifact.MissionID).Scan(&ready); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='ARTIFACT_INVALID'`, f.artifact.MissionID).Scan(&invalid); err != nil {
				t.Fatal(err)
			}
			if jobStatus != "FAILED" || artifacts != 0 || files != 0 || ready != 0 || invalid != 1 {
				t.Fatalf("download integrity reconciliation = job %s artifacts %d files %d ready %d invalid %d", jobStatus, artifacts, files, ready, invalid)
			}
			visible, err := f.store.Artifacts(f.ctx, f.ownerID, f.artifact.MissionID)
			if err != nil || len(visible) != 0 {
				t.Fatalf("damaged artifact remained visible = %d/%v", len(visible), err)
			}
		})
	}
}

func TestDownloadInvalidationPreservesSharedStorageKey(t *testing.T) {
	f, cleanup := newDownloadFixture(t)
	defer cleanup()
	var fileID int64
	if err := f.store.DB.QueryRow(f.ctx, `SELECT file_object_id FROM artifacts WHERE id=$1`, f.artifact.ID).Scan(&fileID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.DB.Exec(f.ctx, `INSERT INTO mission_files(mission_id,file_object_id,role,parse_status,uploaded_by) VALUES($1,$2,'FINAL_PPTX','READY',$3)`, f.artifact.MissionID, fileID, f.ownerID); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(f.filesRoot(), filepath.FromSlash(f.artifact.File.StorageKey))
	if err := os.WriteFile(path, []byte("shared-reference-corruption"), 0o640); err != nil {
		t.Fatal(err)
	}
	server := NewServer(Config{SessionCookie: "session"}, f.store.DB, f.store, nil, f.files, nil, nil)
	tcp := httptest.NewServer(server.Router())
	defer tcp.Close()
	response := downloadOverTCP(t, tcp, f)
	defer response.Body.Close()
	if response.StatusCode != http.StatusConflict {
		t.Fatalf("shared key corrupt download status = %d", response.StatusCode)
	}
	if _, err := f.files.Open(f.ctx, f.artifact.File.StorageKey); err != nil {
		t.Fatalf("shared storage key was removed: %v", err)
	}
	var remaining int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM file_objects WHERE id=$1`, fileID).Scan(&remaining); err != nil {
		t.Fatal(err)
	}
	if remaining != 1 {
		t.Fatalf("shared file object remaining = %d", remaining)
	}
}

func TestAgentWorkerStageMismatchIsVisibleThroughTeacherAPIs(t *testing.T) {
	cases := []struct {
		name   string
		seed   string
		target string
	}{
		{"MESSAGE-to-QUESTION", `{"type":"MESSAGE","content":"seed message"}`, `{"type":"QUESTION","question":"target question","questionType":"TEXT","options":[]}`},
		{"MESSAGE-to-PLAN_DRAFT", `{"type":"MESSAGE","content":"seed message"}`, `{"type":"PLAN_DRAFT","markdown":"# target draft","structuredPlan":{"slides":[{"title":"Target"}]}}`},
		{"QUESTION-to-MESSAGE", `{"type":"QUESTION","question":"seed question","questionType":"TEXT","options":[]}`, `{"type":"MESSAGE","content":"target message"}`},
		{"QUESTION-to-PLAN_DRAFT", `{"type":"QUESTION","question":"seed question","questionType":"TEXT","options":[]}`, `{"type":"PLAN_DRAFT","markdown":"# target draft","structuredPlan":{"slides":[{"title":"Target"}]}}`},
		{"PLAN_DRAFT-to-MESSAGE", `{"type":"PLAN_DRAFT","markdown":"# seed draft","structuredPlan":{"slides":[{"title":"Seed"}]}}`, `{"type":"MESSAGE","content":"target message"}`},
		{"PLAN_DRAFT-to-QUESTION", `{"type":"PLAN_DRAFT","markdown":"# seed draft","structuredPlan":{"slides":[{"title":"Seed"}]}}`, `{"type":"QUESTION","question":"target question","questionType":"TEXT","options":[]}`},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			f, cleanup := newDownloadFixture(t)
			defer cleanup()
			crypt, err := crypto.New([]byte("01234567890123456789012345678901"))
			if err != nil {
				t.Fatal(err)
			}
			encrypted, err := crypt.Encrypt("synthetic-provider-key")
			if err != nil {
				t.Fatal(err)
			}
			connection, err := f.store.CreateConnection(f.ctx, f.ownerID, model.ModelConnection{Name: "API stage " + uuid.NewString(), Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://provider.example.test", ModelID: "test-model", KeyHint: "test"}, encrypted)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := f.store.DB.Exec(f.ctx, `UPDATE model_connections SET enabled=true,verification_status='VERIFIED' WHERE id=$1`, connection.ID); err != nil {
				t.Fatal(err)
			}
			if _, err := f.store.DB.Exec(f.ctx, `UPDATE missions SET selected_model_connection_id=$1 WHERE id=$2`, connection.ID, f.artifact.MissionID); err != nil {
				t.Fatal(err)
			}
			var messageID int64
			if err := f.store.DB.QueryRow(f.ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','stage matrix') RETURNING id`, f.artifact.MissionID).Scan(&messageID); err != nil {
				t.Fatal(err)
			}
			runID := uuid.NewString()
			snapshot, _ := json.Marshal(map[string]any{"connectionId": connection.ID, "protocol": connection.Protocol, "baseUrl": connection.BaseURL, "modelId": connection.ModelID, "encryptedApiKey": encrypted})
			if _, err := f.store.DB.Exec(f.ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_connection_id,model_identity_snapshot,status) VALUES($1,$2,$3,$4,$5,'QUEUED')`, runID, f.artifact.MissionID, messageID, connection.ID, snapshot); err != nil {
				t.Fatal(err)
			}
			runtimeA := &agent.Runtime{Store: f.store, Crypto: crypt, Models: model.NewClient(time.Second, 1<<20), MaxToolCalls: 1}
			runtimeA.SetChatForTest(func(context.Context, model.ResolvedConnection, model.ChatRequest) (model.ChatResponse, error) {
				return model.ChatResponse{Content: test.seed, RawStatus: 200}, nil
			})
			agent.RunOnce(f.ctx, f.store, runtimeA, time.Second)
			if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET status='RUNNING',lease_token=$1,lease_expires_at=clock_timestamp()-interval '1 second',finished_at=NULL WHERE id=$2`, uuid.NewString(), runID); err != nil {
				t.Fatal(err)
			}
			runtimeB := &agent.Runtime{Store: f.store, Crypto: crypt, Models: model.NewClient(time.Second, 1<<20), MaxToolCalls: 1}
			runtimeB.SetChatForTest(func(context.Context, model.ResolvedConnection, model.ChatRequest) (model.ChatResponse, error) {
				return model.ChatResponse{Content: test.target, RawStatus: 200}, nil
			})
			agent.RunOnce(f.ctx, f.store, runtimeB, time.Second)
			api := httptest.NewServer(NewServer(Config{SessionCookie: "session"}, f.store.DB, f.store, crypt, f.files, nil, runtimeB).Router())
			defer api.Close()
			for _, endpoint := range []string{"messages", "questions", "planning/current", "planning/history", "agent-runs"} {
				req, err := http.NewRequest(http.MethodGet, api.URL+"/api/missions/"+strconv.FormatInt(f.artifact.MissionID, 10)+"/"+endpoint, nil)
				if err != nil {
					t.Fatal(err)
				}
				req.Header.Set("Authorization", "Bearer "+f.token)
				response, err := api.Client().Do(req)
				if err != nil {
					t.Fatal(err)
				}
				body, readErr := io.ReadAll(response.Body)
				closeErr := response.Body.Close()
				if readErr != nil || closeErr != nil || response.StatusCode != http.StatusOK {
					t.Fatalf("API %s = %d read=%v close=%v body=%s", endpoint, response.StatusCode, readErr, closeErr, string(body))
				}
				switch endpoint {
				case "messages":
					var items []model.Message
					if err := json.Unmarshal(body, &items); err != nil {
						t.Fatalf("messages JSON: %v", err)
					}
					for _, want := range stageVisibleMessageContents(test.seed, test.target) {
						matches := 0
						for _, item := range items {
							if item.Content == want && item.Role == "ASSISTANT" {
								matches++
							}
						}
						if matches != 1 {
							t.Fatalf("messages target %q matches=%d items=%+v", want, matches, items)
						}
					}
					for _, item := range items {
						if item.Role == "ASSISTANT" && (item.OwnerUserID != f.ownerID || item.AgentRunID == nil || *item.AgentRunID != runID || item.OutputStage == "" || item.ReferenceType == "" || item.ReferenceID == "") {
							t.Fatalf("message API omitted exact output identity: %+v", item)
						}
					}
				case "questions":
					var items []model.Question
					if err := json.Unmarshal(body, &items); err != nil {
						t.Fatalf("questions JSON: %v", err)
					}
					wants := stageVisibleQuestionContents(test.seed, test.target)
					if len(items) != len(wants) {
						t.Fatalf("question API count = %d, want %d: %+v", len(items), len(wants), items)
					}
					for _, want := range wants {
						matches := 0
						for _, item := range items {
							if item.AgentRunID == runID && item.MissionID == f.artifact.MissionID && item.Text == want {
								matches++
							}
						}
						if matches != 1 {
							t.Fatalf("question API content/owner reference %q matches=%d: %+v", want, matches, items)
						}
					}
					for _, item := range items {
						if item.OwnerUserID != f.ownerID || item.AgentRunID != runID || item.OutputStage != database.AgentOutputStageQuestion || item.ReferenceType != "QUESTION" || item.ReferenceID != item.ID {
							t.Fatalf("question API omitted exact output identity: %+v", item)
						}
					}
				case "planning/current":
					var item model.PlanningDraft
					if err := json.Unmarshal(body, &item); err != nil {
						t.Fatalf("planning/current JSON: %v", err)
					}
					wantMarkdown := "download"
					wantRunID := ""
					if strings.Contains(test.target, `"type":"PLAN_DRAFT"`) {
						wantMarkdown = "# target draft"
						wantRunID = runID
					} else if strings.Contains(test.seed, `"type":"PLAN_DRAFT"`) {
						wantMarkdown = "# seed draft"
						wantRunID = runID
					}
					if item.ID == "" || item.MissionID != f.artifact.MissionID || item.Markdown != wantMarkdown || (wantRunID != "" && item.CreatedByAgentRunID != wantRunID) {
						t.Fatalf("planning current content/owner reference = %+v", item)
					}
					if item.OwnerUserID != f.ownerID || (wantRunID != "" && (item.OutputStage != database.AgentOutputStagePlan || item.ReferenceType != "PLANNING_DRAFT" || item.ReferenceID != item.ID)) {
						t.Fatalf("planning current omitted exact output identity = %+v", item)
					}
				case "planning/history":
					var items []model.PlanningDraft
					if err := json.Unmarshal(body, &items); err != nil {
						t.Fatalf("planning/history JSON: %v", err)
					}
					wantCount := 1
					if strings.Contains(test.target, `"type":"PLAN_DRAFT"`) || strings.Contains(test.seed, `"type":"PLAN_DRAFT"`) {
						wantCount = 2
					}
					if len(items) != wantCount {
						t.Fatalf("planning history count = %d, want %d: %+v", len(items), wantCount, items)
					}
					for _, item := range items {
						if item.MissionID != f.artifact.MissionID || ((item.Markdown == "# target draft" || item.Markdown == "# seed draft") && item.CreatedByAgentRunID != runID) {
							t.Fatalf("planning history content/owner reference = %+v", items)
						}
						if item.OwnerUserID != f.ownerID || ((item.Markdown == "# target draft" || item.Markdown == "# seed draft") && (item.OutputStage != database.AgentOutputStagePlan || item.ReferenceType != "PLANNING_DRAFT" || item.ReferenceID != item.ID)) {
							t.Fatalf("planning history omitted exact output identity = %+v", items)
						}
					}
				case "agent-runs":
					var items []model.AgentRun
					if err := json.Unmarshal(body, &items); err != nil {
						t.Fatalf("agent-runs JSON: %v", err)
					}
					matches := 0
					wantRunStatus := "COMPLETED"
					if strings.Contains(test.target, `"type":"QUESTION"`) {
						wantRunStatus = "WAITING_INPUTS"
					}
					for _, item := range items {
						if item.ID == runID && item.MissionID == f.artifact.MissionID && item.OwnerUserID == f.ownerID && item.Status == wantRunStatus && item.ModelConnectionID != nil && *item.ModelConnectionID == connection.ID {
							matches++
						}
					}
					if matches != 1 {
						t.Fatalf("agent-runs target owner/reference matches=%d items=%+v", matches, items)
					}
				}
			}
			var status string
			wantStatus := "COMPLETED"
			if strings.Contains(test.target, `"type":"QUESTION"`) {
				wantStatus = "WAITING_INPUTS"
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM agent_runs WHERE id=$1`, runID).Scan(&status); err != nil || status != wantStatus {
				t.Fatalf("stage mismatch run status = %s/%v, want %s", status, err, wantStatus)
			}
			var outputText string
			if strings.Contains(test.target, `"type":"MESSAGE"`) {
				if err := f.store.DB.QueryRow(f.ctx, `SELECT content FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='MESSAGE'`, f.artifact.MissionID, runID).Scan(&outputText); err != nil || outputText != "target message" {
					t.Fatalf("message output content/reference = %q/%v", outputText, err)
				}
			} else if strings.Contains(test.target, `"type":"QUESTION"`) {
				if err := f.store.DB.QueryRow(f.ctx, `SELECT text FROM questions WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION'`, f.artifact.MissionID, runID).Scan(&outputText); err != nil || outputText != "target question" {
					t.Fatalf("question output content/reference = %q/%v", outputText, err)
				}
			} else {
				if err := f.store.DB.QueryRow(f.ctx, `SELECT markdown FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage='PLAN_DRAFT'`, f.artifact.MissionID, runID).Scan(&outputText); err != nil || outputText != "# target draft" {
					t.Fatalf("planning output content/reference = %q/%v", outputText, err)
				}
			}
			assertAgentOutputActivityReferences(t, f, runID, wantStatus)
			other, err := f.store.CreateUser(f.ctx, "Other teacher", "other-"+uuid.NewString()+"@example.test", "test-password-hash", model.RoleTeacher)
			if err != nil {
				t.Fatal(err)
			}
			defer func() {
				if _, err := f.store.DB.Exec(f.ctx, `DELETE FROM users WHERE id=$1`, other.ID); err != nil {
					t.Errorf("cleanup other teacher: %v", err)
				}
			}()
			otherToken, err := auth.NewToken()
			if err != nil {
				t.Fatal(err)
			}
			if err := f.store.CreateSession(f.ctx, other.ID, auth.TokenHash(otherToken), time.Now().Add(time.Hour)); err != nil {
				t.Fatal(err)
			}
			for _, endpoint := range []string{"messages", "questions", "planning/current", "planning/history", "agent-runs"} {
				otherRequest, err := http.NewRequest(http.MethodGet, api.URL+"/api/missions/"+strconv.FormatInt(f.artifact.MissionID, 10)+"/"+endpoint, nil)
				if err != nil {
					t.Fatal(err)
				}
				otherRequest.Header.Set("Authorization", "Bearer "+otherToken)
				otherResponse, err := api.Client().Do(otherRequest)
				if err != nil {
					t.Fatal(err)
				}
				otherBody, readErr := io.ReadAll(otherResponse.Body)
				otherCloseErr := otherResponse.Body.Close()
				if readErr != nil || otherCloseErr != nil || otherResponse.StatusCode != http.StatusNotFound {
					t.Fatalf("cross-owner %s API = %d read=%v close=%v body=%s", endpoint, otherResponse.StatusCode, readErr, otherCloseErr, string(otherBody))
				}
			}
		})
	}
}

// assertAgentOutputActivityReferences compares every production output row to
// its public activity reference. Counts and idempotency alone are insufficient:
// the event type, reference type and reference id must name the exact output.
func assertAgentOutputActivityReferences(t *testing.T, f *downloadFixture, runID, finalStatus string) {
	t.Helper()
	rows, err := f.store.DB.Query(f.ctx, `SELECT event_type,reference_type,reference_id,idempotency_key FROM activity_events WHERE mission_id=$1 AND idempotency_key LIKE $2 ORDER BY id`, f.artifact.MissionID, "agent-run:"+runID+":%")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	seen := map[string]bool{}
	for rows.Next() {
		var eventType, referenceType, referenceID, key string
		if err := rows.Scan(&eventType, &referenceType, &referenceID, &key); err != nil {
			t.Fatal(err)
		}
		switch {
		case strings.HasSuffix(key, ":message"):
			var id int64
			err = f.store.DB.QueryRow(f.ctx, `SELECT id FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='MESSAGE'`, f.artifact.MissionID, runID).Scan(&id)
			if err != nil || eventType != "MESSAGE_CREATED" || referenceType != "MESSAGE" || referenceID != strconv.FormatInt(id, 10) {
				t.Fatalf("message activity %s = %s/%s/%s query=%v", key, eventType, referenceType, referenceID, err)
			}
			seen[key] = true
		case strings.HasSuffix(key, ":question-message"):
			var id int64
			err = f.store.DB.QueryRow(f.ctx, `SELECT id FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION_MESSAGE'`, f.artifact.MissionID, runID).Scan(&id)
			if err != nil || eventType != "MESSAGE_CREATED" || referenceType != "MESSAGE" || referenceID != strconv.FormatInt(id, 10) {
				t.Fatalf("question-message activity %s = %s/%s/%s query=%v", key, eventType, referenceType, referenceID, err)
			}
			seen[key] = true
		case strings.HasSuffix(key, ":question"):
			var id string
			err = f.store.DB.QueryRow(f.ctx, `SELECT id FROM questions WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION'`, f.artifact.MissionID, runID).Scan(&id)
			if err != nil || eventType != "QUESTION_CREATED" || referenceType != "QUESTION" || referenceID != id {
				t.Fatalf("question activity %s = %s/%s/%s query=%v", key, eventType, referenceType, referenceID, err)
			}
			seen[key] = true
		case strings.HasSuffix(key, ":plan-draft"):
			var id string
			err = f.store.DB.QueryRow(f.ctx, `SELECT id FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage='PLAN_DRAFT'`, f.artifact.MissionID, runID).Scan(&id)
			if err != nil || eventType != "PLAN_DRAFT_CREATED" || referenceType != "PLANNING_DRAFT" || referenceID != id {
				t.Fatalf("plan activity %s = %s/%s/%s query=%v", key, eventType, referenceType, referenceID, err)
			}
			seen[key] = true
		case strings.HasSuffix(key, ":final:COMPLETED"), strings.HasSuffix(key, ":final:WAITING_INPUTS"):
			wantEventType := "AGENT_RUN_COMPLETED"
			if strings.HasSuffix(key, ":final:WAITING_INPUTS") {
				wantEventType = "AGENT_RUN_WAITING_INPUTS"
			}
			if eventType != wantEventType || referenceType != "AGENT_RUN" || referenceID != runID {
				t.Fatalf("final activity %s = %s/%s/%s, want %s", key, eventType, referenceType, referenceID, wantEventType)
			}
			seen[key] = true
		default:
			t.Fatalf("unexpected agent activity key %q", key)
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if len(seen) < 2 {
		t.Fatalf("insufficient exact output activities: %+v", seen)
	}
}

func (f *downloadFixture) filesRoot() string {
	return filepath.Join(f.root, "stored")
}

func stageVisibleMessageContents(outputs ...string) []string {
	var out []string
	for _, raw := range outputs {
		var envelope struct {
			Type     string `json:"type"`
			Content  string `json:"content"`
			Question string `json:"question"`
		}
		if json.Unmarshal([]byte(raw), &envelope) != nil {
			continue
		}
		switch envelope.Type {
		case "MESSAGE":
			out = append(out, envelope.Content)
		case "QUESTION":
			out = append(out, envelope.Question)
		}
	}
	return out
}

func stageVisibleQuestionContents(outputs ...string) []string {
	var out []string
	for _, raw := range outputs {
		var envelope struct {
			Type     string `json:"type"`
			Question string `json:"question"`
		}
		if json.Unmarshal([]byte(raw), &envelope) == nil && envelope.Type == "QUESTION" {
			out = append(out, envelope.Question)
		}
	}
	return out
}
