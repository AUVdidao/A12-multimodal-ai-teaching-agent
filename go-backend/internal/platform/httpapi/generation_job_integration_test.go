package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"lessonforge.local/backend/internal/auth"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/platform/database"
)

func TestCreateGenerationJobHTTPIsExplicitAndOwnerBound(t *testing.T) {
	dsn := os.Getenv("LESSONFORGE_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("LESSONFORGE_TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	pool, err := database.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if err := database.Migrate(ctx, pool, filepath.Join("..", "..", "..", "migrations")); err != nil {
		t.Fatal(err)
	}
	unlock, err := database.AcquireIntegrationTestLock(ctx, pool)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(unlock)
	store := database.NewStore(pool)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Generation HTTP owner", "generation-http-owner-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	other, err := store.CreateUser(ctx, "Generation HTTP other", "generation-http-other-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	var missionID, messageID, fileObjectID, missionFileID int64
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title) VALUES($1,'SELF_CREATED','Generation HTTP fixture') RETURNING id`, owner.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','generation HTTP fixture') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_identity_snapshot,status) VALUES($1,$2,$3,'{"fixture":true}'::jsonb,'COMPLETED')`, runID, missionID, messageID); err != nil {
		t.Fatal(err)
	}
	storageKey := "generation-http-template/" + suffix + ".pptx"
	if err := pool.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,'fixture.pptx','application/vnd.openxmlformats-officedocument.presentationml.presentation',12,$3) RETURNING id`, owner.ID, storageKey, strings.Repeat("b", 64)).Scan(&fileObjectID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO mission_files(mission_id,file_object_id,role,provenance,parse_status,uploaded_by) VALUES($1,$2,'TEMPLATE','TEACHER','READY',$3) RETURNING id`, missionID, fileObjectID, owner.ID).Scan(&missionFileID); err != nil {
		t.Fatal(err)
	}
	specID := uuid.NewString()
	draftID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id) VALUES($1,$2,1,'fixture','{"slides":[{"title":"fixture"}]}'::jsonb,$3)`, draftID, missionID, runID); err != nil {
		t.Fatal(err)
	}
	binding, err := json.Marshal(map[string]any{
		"bindingKind": "LESSONFORGE_UPSTREAM_TEMPLATE_BINDING", "contractVersion": "lessonforge-upstream-template-v1", "missionId": missionID,
		"missionFileId": missionFileID, "fileObjectId": fileObjectID, "ownerUserId": owner.ID,
		"templateStorageKey": storageKey, "templateFileSha256": strings.Repeat("b", 64),
		"templateOriginalName": "fixture.pptx", "templateMimeType": "application/vnd.openxmlformats-officedocument.presentationml.presentation", "templateFileSize": 12,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `INSERT INTO locked_specifications(id,mission_id,source_draft_id,version,specification_json,template_binding_json,content_hash) VALUES($1,$2,$3,1,'{"slides":[{"title":"fixture"}]}'::jsonb,$4,$5)`, specID, missionID, draftID, binding, strings.Repeat("c", 64)); err != nil {
		t.Fatal(err)
	}
	ownerToken, err := auth.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if err := store.CreateSession(ctx, owner.ID, auth.TokenHash(ownerToken), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	otherToken, err := auth.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if err := store.CreateSession(ctx, other.ID, auth.TokenHash(otherToken), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		triggerDisabled := false
		if _, err := pool.Exec(cleanupCtx, `ALTER TABLE locked_specifications DISABLE TRIGGER locked_specifications_immutable_trg`); err == nil {
			triggerDisabled = true
		}
		defer func() {
			if triggerDisabled {
				_, _ = pool.Exec(cleanupCtx, `ALTER TABLE locked_specifications ENABLE TRIGGER locked_specifications_immutable_trg`)
			}
		}()
		_, _ = pool.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, missionID)
		_, _ = pool.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1 OR id=$2`, owner.ID, other.ID)
	})

	server := httptest.NewServer(NewServer(Config{SessionCookie: "session"}, pool, store, nil, nil, nil, nil).Router())
	t.Cleanup(server.Close)
	post := func(token string, version int) (int, map[string]any) {
		t.Helper()
		body, err := json.Marshal(map[string]any{"specificationId": specID, "specificationVersion": version})
		if err != nil {
			t.Fatal(err)
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/missions/"+strconv.FormatInt(missionID, 10)+"/generation-jobs", bytes.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "application/json")
		response, err := server.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		var payload map[string]any
		if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
			data, _ := io.ReadAll(response.Body)
			t.Fatalf("decode generation response %d/%s: %v", response.StatusCode, data, err)
		}
		return response.StatusCode, payload
	}
	status, payload := post(ownerToken, 1)
	if status != http.StatusCreated || payload["created"] != true {
		t.Fatalf("first generation response = %d/%v", status, payload)
	}
	job := payload["generationJob"].(map[string]any)
	if job["id"] == "" || job["specificationId"] != specID || job["specificationVersion"] != float64(1) || job["status"] != "QUEUED" {
		t.Fatalf("first generation job = %v", job)
	}
	status, payload = post(ownerToken, 1)
	if status != http.StatusOK || payload["created"] != false || payload["generationJob"].(map[string]any)["id"] != job["id"] {
		t.Fatalf("duplicate generation response = %d/%v", status, payload)
	}
	status, payload = post(ownerToken, 2)
	if status != http.StatusConflict || payload["error"].(map[string]any)["code"] != "GENERATION_SPECIFICATION_VERSION_MISMATCH" {
		t.Fatalf("version response = %d/%v", status, payload)
	}
	status, payload = post(otherToken, 1)
	if status != http.StatusNotFound || payload["error"].(map[string]any)["code"] != "GENERATION_MISSION_NOT_FOUND" {
		t.Fatalf("cross-owner response = %d/%v", status, payload)
	}
	if _, err := pool.Exec(ctx, `UPDATE generation_jobs SET status='FAILED',finished_at=now() WHERE id=$1`, job["id"]); err != nil {
		t.Fatal(err)
	}
	status, payload = post(ownerToken, 1)
	if status != http.StatusCreated || payload["created"] != true || payload["generationJob"].(map[string]any)["id"] == job["id"] {
		t.Fatalf("terminal retry response = %d/%v", status, payload)
	}
	get, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL+"/api/missions/"+strconv.FormatInt(missionID, 10)+"/generation-jobs", nil)
	if err != nil {
		t.Fatal(err)
	}
	get.Header.Set("Authorization", "Bearer "+ownerToken)
	response, err := server.Client().Do(get)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var jobs []model.GenerationJob
	if response.StatusCode != http.StatusOK || json.NewDecoder(response.Body).Decode(&jobs) != nil || len(jobs) != 2 || jobs[0].SpecificationVersion != 1 {
		t.Fatalf("generation jobs response = %d/%v", response.StatusCode, jobs)
	}
}
