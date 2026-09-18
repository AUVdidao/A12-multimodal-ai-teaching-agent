package httpapi

import (
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

// TestUpstreamHTTPIsolationCoversMissionFilesDraftAndApproval proves the
// teacher boundary at the HTTP layer without creating a Generation Job or
// invoking any downstream PPT Engine code.
func TestUpstreamHTTPIsolationCoversMissionFilesDraftAndApproval(t *testing.T) {
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
	owner, err := store.CreateUser(ctx, "Upstream owner", "upstream-owner-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	other, err := store.CreateUser(ctx, "Other teacher", "upstream-other-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	var missionID, missionFileID, messageID int64
	var draftID string
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title) VALUES($1,'SELF_CREATED','Upstream isolation') RETURNING id`, owner.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,'lesson.md','text/markdown',12,$3) RETURNING id`, owner.ID, "upstream/"+suffix+".md", strings.Repeat("a", 64)).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO mission_files(mission_id,file_object_id,role,provenance,parse_status,uploaded_by) VALUES($1,$2,'MATERIAL','MATERIAL','READY',$3) RETURNING id`, missionID, messageID, owner.ID).Scan(&missionFileID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','approve upstream draft') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	runIDString := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_identity_snapshot,status) VALUES($1,$2,$3,'{"source":"upstream-isolation"}'::jsonb,'COMPLETED')`, runIDString, missionID, messageID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id,output_stage) VALUES($1,$2,1,'# draft','{"slides":[]}'::jsonb,$3,'PLAN_DRAFT') RETURNING id`, uuid.NewString(), missionID, runIDString).Scan(&draftID); err != nil {
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
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, missionID); err != nil {
			t.Errorf("cleanup mission: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM file_objects WHERE owner_user_id=$1`, owner.ID); err != nil {
			t.Errorf("cleanup file objects: %v", err)
		}
		for _, id := range []int64{owner.ID, other.ID} {
			if _, err := pool.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, id); err != nil {
				t.Errorf("cleanup user %d: %v", id, err)
			}
		}
	})

	server := httptest.NewServer(NewServer(Config{SessionCookie: "session"}, pool, store, nil, nil, nil, nil).Router())
	t.Cleanup(server.Close)
	request := func(method, path, token string, body io.Reader) *http.Response {
		req, err := http.NewRequestWithContext(ctx, method, server.URL+path, body)
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer "+token)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		response, err := server.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return response
	}

	missionPath := "/api/missions/" + strconv.FormatInt(missionID, 10)
	response := request(http.MethodGet, missionPath+"/files", ownerToken, nil)
	var files []model.MissionFile
	if response.StatusCode != http.StatusOK || json.NewDecoder(response.Body).Decode(&files) != nil {
		response.Body.Close()
		t.Fatalf("owner mission files status = %d", response.StatusCode)
	}
	response.Body.Close()
	if len(files) != 1 || files[0].ID != missionFileID || files[0].MissionID != missionID || files[0].FileObject.SHA256 != strings.Repeat("a", 64) {
		t.Fatalf("owner mission files projection = %+v", files)
	}

	response = request(http.MethodGet, missionPath+"/planning/current", ownerToken, nil)
	var draft model.PlanningDraft
	if response.StatusCode != http.StatusOK || json.NewDecoder(response.Body).Decode(&draft) != nil {
		response.Body.Close()
		t.Fatalf("owner current draft status = %d", response.StatusCode)
	}
	response.Body.Close()
	if draft.ID != draftID || draft.MissionID != missionID || draft.OwnerUserID != owner.ID || draft.Version != 1 || draft.Markdown != "# draft" {
		t.Fatalf("owner draft projection = %+v", draft)
	}

	for _, path := range []string{missionPath, missionPath + "/files", missionPath + "/planning/current", missionPath + "/planning/history"} {
		response = request(http.MethodGet, path, otherToken, nil)
		body, readErr := io.ReadAll(response.Body)
		closeErr := response.Body.Close()
		if readErr != nil || closeErr != nil || response.StatusCode != http.StatusNotFound {
			t.Fatalf("cross-owner %s = %d read=%v close=%v body=%s", path, response.StatusCode, readErr, closeErr, string(body))
		}
	}

	response = request(http.MethodPost, "/api/planning/"+draftID+"/approve", otherToken, strings.NewReader(`{}`))
	body, readErr := io.ReadAll(response.Body)
	closeErr := response.Body.Close()
	if readErr != nil || closeErr != nil || response.StatusCode != http.StatusBadRequest {
		t.Fatalf("cross-owner approve = %d read=%v close=%v body=%s", response.StatusCode, readErr, closeErr, string(body))
	}
	var rejected map[string]map[string]string
	if err := json.Unmarshal(body, &rejected); err != nil || rejected["error"]["code"] != "NOT_FOUND" {
		t.Fatalf("cross-owner approve error = %s", string(body))
	}
	var locked, jobs int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM locked_specifications WHERE mission_id=$1`, missionID).Scan(&locked); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM generation_jobs WHERE mission_id=$1`, missionID).Scan(&jobs); err != nil {
		t.Fatal(err)
	}
	if locked != 0 || jobs != 0 {
		t.Fatalf("cross-owner approval side effects = locked %d generation_jobs %d", locked, jobs)
	}
}

func TestApproveDraftHTTPAllowsMissingTemplateAndRecordsDefaultGenerationMode(t *testing.T) {
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
	owner, err := store.CreateUser(ctx, "Default layout HTTP owner", "default-layout-http-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	var missionID, messageID int64
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title) VALUES($1,'SELF_CREATED','Default layout HTTP') RETURNING id`, owner.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','approve without template over HTTP') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_identity_snapshot,status) VALUES($1,$2,$3,'{"source":"default-layout-http"}'::jsonb,'COMPLETED')`, runID, missionID, messageID); err != nil {
		t.Fatal(err)
	}
	draftID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id,output_stage) VALUES($1,$2,1,'# default layout','{"slides":[{"title":"Introduction"}]}'::jsonb,$3,'PLAN_DRAFT')`, draftID, missionID, runID); err != nil {
		t.Fatal(err)
	}
	token, err := auth.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if err := store.CreateSession(ctx, owner.ID, auth.TokenHash(token), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		if _, err := pool.Exec(cleanupCtx, `ALTER TABLE locked_specifications DISABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
			t.Errorf("disable immutable trigger for HTTP default-layout cleanup: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, missionID); err != nil {
			t.Errorf("cleanup HTTP default-layout mission: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `ALTER TABLE locked_specifications ENABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
			t.Errorf("restore immutable trigger after HTTP default-layout cleanup: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, owner.ID); err != nil {
			t.Errorf("cleanup HTTP default-layout owner: %v", err)
		}
	})

	server := httptest.NewServer(NewServer(Config{SessionCookie: "session"}, pool, store, nil, nil, nil, nil).Router())
	t.Cleanup(server.Close)
	request := func(method, path string, body io.Reader) *http.Response {
		t.Helper()
		req, err := http.NewRequestWithContext(ctx, method, server.URL+path, body)
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer "+token)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		response, err := server.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return response
	}

	response := request(http.MethodPost, "/api/planning/"+draftID+"/approve", strings.NewReader(`{}`))
	if response.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		t.Fatalf("approve without template status = %d body=%s", response.StatusCode, body)
	}
	var approved struct {
		Locked model.LockedSpecification `json:"lockedSpecification"`
	}
	if err := json.NewDecoder(response.Body).Decode(&approved); err != nil {
		response.Body.Close()
		t.Fatal(err)
	}
	response.Body.Close()
	if approved.Locked.ID == "" || approved.Locked.TemplateBinding != nil {
		t.Fatalf("HTTP default-layout locked specification = %#v", approved.Locked)
	}

	response = request(http.MethodPost, "/api/missions/"+strconv.FormatInt(missionID, 10)+"/generation-jobs", strings.NewReader(`{"specificationId":"`+approved.Locked.ID+`","specificationVersion":1,"fallbackPolicy":"AUTO"}`))
	if response.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		t.Fatalf("default-layout generation status = %d body=%s", response.StatusCode, body)
	}
	var generation struct {
		Job model.GenerationJob `json:"generationJob"`
	}
	if err := json.NewDecoder(response.Body).Decode(&generation); err != nil {
		response.Body.Close()
		t.Fatal(err)
	}
	response.Body.Close()
	if generation.Job.GenerationMode != database.GenerationModeSystemDefault || !containsHTTPString(generation.Job.FallbackReasons, "TEMPLATE_NOT_PROVIDED") {
		t.Fatalf("HTTP default-layout generation job = %+v", generation.Job)
	}
	var summary string
	if err := pool.QueryRow(ctx, `SELECT summary FROM activity_events WHERE mission_id=$1 AND event_type='PLAN_APPROVED'`, missionID).Scan(&summary); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(summary, "system default template") || !strings.Contains(summary, "not provided") {
		t.Fatalf("HTTP approval audit summary = %q", summary)
	}
}

func containsHTTPString(values []string, target string) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}
