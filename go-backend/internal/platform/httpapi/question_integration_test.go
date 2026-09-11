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

func TestQuestionsHTTPReadsLatestAnswerAndEnforcesMissionOwnership(t *testing.T) {
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
	owner, err := store.CreateUser(ctx, "Question owner", "question-owner-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	other, err := store.CreateUser(ctx, "Other teacher", "question-other-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	connection, err := store.CreateConnection(ctx, owner.ID, model.ModelConnection{Name: "Question fixture " + suffix, Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://provider.invalid", ModelID: "fixture-model", KeyHint: "test"}, "synthetic-provider-key")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE model_connections SET enabled=true,verification_status='VERIFIED' WHERE id=$1`, connection.ID); err != nil {
		t.Fatal(err)
	}
	var missionID, emptyMissionID, messageID int64
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title,selected_model_connection_id) VALUES($1,'SELF_CREATED','Question fixture',$2) RETURNING id`, owner.ID, connection.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title) VALUES($1,'SELF_CREATED','Empty question fixture') RETURNING id`, owner.ID).Scan(&emptyMissionID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','start') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_connection_id,model_identity_snapshot,status) VALUES($1,$2,$3,$4,'{}'::jsonb,'COMPLETED')`, runID, missionID, messageID, connection.ID); err != nil {
		t.Fatal(err)
	}
	questionID := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO questions(id,mission_id,agent_run_id,output_stage,text,question_type,options_json) VALUES($1,$2,$3,'QUESTION','请选择年级','SINGLE_CHOICE','["初中","高中"]'::jsonb)`, questionID, missionID, runID); err != nil {
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
		for _, id := range []int64{missionID, emptyMissionID} {
			if _, err := pool.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, id); err != nil {
				t.Errorf("cleanup mission %d: %v", id, err)
			}
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

	response := request(http.MethodGet, "/api/missions/"+strconv.FormatInt(missionID, 10)+"/questions", ownerToken, nil)
	var questions []model.Question
	if response.StatusCode != http.StatusOK || json.NewDecoder(response.Body).Decode(&questions) != nil {
		response.Body.Close()
		t.Fatalf("initial questions status = %d", response.StatusCode)
	}
	response.Body.Close()
	if len(questions) != 1 || questions[0].LatestAnswer != nil {
		t.Fatalf("initial question projection = %+v", questions)
	}

	response = request(http.MethodPost, "/api/questions/"+questionID+"/answers", ownerToken, strings.NewReader(`{"selectedValues":["大学"],"textAnswer":""}`))
	invalidBody, _ := io.ReadAll(response.Body)
	response.Body.Close()
	if response.StatusCode != http.StatusBadRequest || !strings.Contains(string(invalidBody), "QUESTION_ANSWER_INVALID") {
		t.Fatalf("invalid choice status/body = %d/%s", response.StatusCode, invalidBody)
	}
	var invalidAnswers int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM question_answers WHERE question_id=$1`, questionID).Scan(&invalidAnswers); err != nil {
		t.Fatal(err)
	}
	if invalidAnswers != 0 {
		t.Fatalf("invalid answer rows = %d, want 0", invalidAnswers)
	}

	response = request(http.MethodPost, "/api/questions/"+questionID+"/answers", ownerToken, strings.NewReader(`{"selectedValues":["高中"],"textAnswer":""}`))
	var accepted map[string]string
	if response.StatusCode != http.StatusAccepted || json.NewDecoder(response.Body).Decode(&accepted) != nil {
		response.Body.Close()
		t.Fatalf("answer status = %d", response.StatusCode)
	}
	response.Body.Close()
	if accepted["answerId"] == "" || accepted["agentRunId"] == "" {
		t.Fatalf("answer response = %#v", accepted)
	}

	response = request(http.MethodGet, "/api/missions/"+strconv.FormatInt(missionID, 10)+"/questions", ownerToken, nil)
	if response.StatusCode != http.StatusOK {
		response.Body.Close()
		t.Fatalf("answered questions status = %d", response.StatusCode)
	}
	questions = nil
	if err := json.NewDecoder(response.Body).Decode(&questions); err != nil {
		response.Body.Close()
		t.Fatal(err)
	}
	response.Body.Close()
	if len(questions) != 1 || questions[0].LatestAnswer == nil || questions[0].LatestAnswer.ID != accepted["answerId"] || len(questions[0].LatestAnswer.SelectedValues) != 1 || questions[0].LatestAnswer.SelectedValues[0] != "高中" || questions[0].LatestAnswer.TextAnswer != "" {
		t.Fatalf("answered question projection = %+v", questions)
	}
	var answeredEvents int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='QUESTION_ANSWERED' AND reference_type='QUESTION' AND reference_id=$2`, missionID, questionID).Scan(&answeredEvents); err != nil {
		t.Fatal(err)
	}
	if answeredEvents != 1 {
		t.Fatalf("question answered activity events = %d, want 1", answeredEvents)
	}

	response = request(http.MethodGet, "/api/missions/"+strconv.FormatInt(emptyMissionID, 10)+"/questions", ownerToken, nil)
	var empty []model.Question
	if response.StatusCode != http.StatusOK || json.NewDecoder(response.Body).Decode(&empty) != nil {
		response.Body.Close()
		t.Fatalf("empty questions status = %d", response.StatusCode)
	}
	response.Body.Close()
	if empty == nil || len(empty) != 0 {
		t.Fatalf("empty questions response = %#v", empty)
	}

	response = request(http.MethodGet, "/api/missions/"+strconv.FormatInt(missionID, 10)+"/questions", otherToken, nil)
	response.Body.Close()
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("cross-teacher questions status = %d, want 404", response.StatusCode)
	}
}
