package httpapi

import (
	"bufio"
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

// TestCreateMissionHTTPPersistsOrderedActivityAndBindsUpload proves the
// public New Mission path writes the same auditable sequence as the database
// transaction: mission, selected connection, bound context file, and first
// teacher message. It intentionally leaves the run waiting for parsed inputs
// and does not invoke a provider or any downstream generation code.
func TestCreateMissionHTTPPersistsOrderedActivityAndBindsUpload(t *testing.T) {
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
	owner, err := store.CreateUser(ctx, "Mission creator", "mission-creator-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	other, err := store.CreateUser(ctx, "Other mission teacher", "mission-other-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	connection, err := store.CreateConnection(ctx, owner.ID, model.ModelConnection{
		Name:     "HTTP activity connection " + suffix,
		Protocol: "OPENAI_COMPATIBLE",
		BaseURL:  "https://provider.example.test/v1",
		ModelID:  "teacher-model",
		KeyHint:  "abcd",
	}, "encrypted-test-key")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE model_connections SET enabled=true,verification_status='VERIFIED' WHERE id=$1`, connection.ID); err != nil {
		t.Fatal(err)
	}
	uploadID, err := store.CreateUpload(ctx, owner.ID, model.FileObject{
		OriginalName: "physics-material.pdf",
		MimeType:     "application/pdf",
		Size:         2048,
		SHA256:       strings.Repeat("b", 64),
		StorageKey:   "mission-activity/" + suffix + ".pdf",
	}, time.Now().Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	var fileID int64
	if err := pool.QueryRow(ctx, `SELECT file_object_id FROM uploads WHERE id=$1`, uploadID).Scan(&fileID); err != nil {
		t.Fatal(err)
	}
	var missionID int64
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, missionID); err != nil && missionID != 0 {
			t.Errorf("cleanup mission: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM uploads WHERE id=$1`, uploadID); err != nil {
			t.Errorf("cleanup upload: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM file_objects WHERE id=$1`, fileID); err != nil {
			t.Errorf("cleanup file object: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM model_connections WHERE id=$1`, connection.ID); err != nil {
			t.Errorf("cleanup connection: %v", err)
		}
		for _, id := range []int64{owner.ID, other.ID} {
			if _, err := pool.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, id); err != nil {
				t.Errorf("cleanup user %d: %v", id, err)
			}
		}
	})

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

	server := httptest.NewServer(NewServer(Config{SessionCookie: "session"}, pool, store, nil, nil, nil, nil).Router())
	t.Cleanup(server.Close)
	reqBody, err := json.Marshal(map[string]any{
		"title":             "HTTP activity mission",
		"description":       "Mission created through the public API",
		"message":           "Create a lesson about wave motion",
		"uploadIds":         []string{uploadID},
		"modelConnectionId": connection.ID,
	})
	if err != nil {
		t.Fatal(err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/missions", bytes.NewReader(reqBody))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+ownerToken)
	req.Header.Set("Content-Type", "application/json")
	response, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	var created struct {
		MissionID  int64  `json:"missionId"`
		MessageID  int64  `json:"messageId"`
		AgentRunID string `json:"agentRunId"`
		Status     string `json:"status"`
	}
	if response.StatusCode != http.StatusCreated || json.NewDecoder(response.Body).Decode(&created) != nil {
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		t.Fatalf("create mission response = %d/%s", response.StatusCode, body)
	}
	response.Body.Close()
	missionID = created.MissionID
	if created.MissionID == 0 || created.MessageID == 0 || created.AgentRunID == "" || created.Status != "WAITING_INPUTS" {
		t.Fatalf("create mission projection = %+v", created)
	}

	missionPath := "/api/missions/" + strconv.FormatInt(created.MissionID, 10)
	eventsReq, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL+missionPath+"/events", nil)
	if err != nil {
		t.Fatal(err)
	}
	eventsReq.Header.Set("Authorization", "Bearer "+ownerToken)
	eventsResponse, err := server.Client().Do(eventsReq)
	if err != nil {
		t.Fatal(err)
	}
	if eventsResponse.StatusCode != http.StatusOK || !strings.HasPrefix(eventsResponse.Header.Get("Content-Type"), "text/event-stream") {
		eventsResponse.Body.Close()
		t.Fatalf("mission event stream response = %d/%q", eventsResponse.StatusCode, eventsResponse.Header.Get("Content-Type"))
	}
	eventReader := bufio.NewReader(eventsResponse.Body)
	var streamed []sseTestEvent
	for i := 0; i < 4; i++ {
		streamed = append(streamed, readSSETestEvent(t, eventReader))
	}
	eventsResponse.Body.Close()
	gotTypes := make([]string, 0, len(streamed))
	for _, event := range streamed {
		gotTypes = append(gotTypes, event.event)
	}
	wantTypes := []string{"MISSION_CREATED", "MODEL_CONNECTION_SELECTED", "MISSION_FILE_BOUND", "MESSAGE_CREATED"}
	if strings.Join(gotTypes, ",") != strings.Join(wantTypes, ",") {
		t.Fatalf("HTTP activity event order = %v, want %v", gotTypes, wantTypes)
	}
	if streamed[0].data["missionId"] != float64(created.MissionID) || streamed[1].data["referenceId"] != strconv.FormatInt(connection.ID, 10) {
		t.Fatalf("HTTP activity references = first=%v second=%v", streamed[0].data, streamed[1].data)
	}

	events, err := store.Events(ctx, owner.ID, created.MissionID, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 4 {
		t.Fatalf("stored activity count = %d, want 4", len(events))
	}
	for i, want := range wantTypes {
		if events[i].EventType != want || events[i].MissionID != created.MissionID {
			t.Fatalf("stored activity[%d] = %+v, want type %s", i, events[i], want)
		}
	}
	if events[0].ReferenceID == nil || *events[0].ReferenceID != strconv.FormatInt(created.MissionID, 10) ||
		events[1].ReferenceID == nil || *events[1].ReferenceID != strconv.FormatInt(connection.ID, 10) ||
		events[2].ReferenceID == nil || *events[2].ReferenceID == "" ||
		events[3].ReferenceID == nil || *events[3].ReferenceID != strconv.FormatInt(created.MessageID, 10) {
		t.Fatalf("stored activity references = %+v", events)
	}

	crossReq, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL+missionPath+"/events", nil)
	if err != nil {
		t.Fatal(err)
	}
	crossReq.Header.Set("Authorization", "Bearer "+otherToken)
	crossResponse, err := server.Client().Do(crossReq)
	if err != nil {
		t.Fatal(err)
	}
	crossResponse.Body.Close()
	if crossResponse.StatusCode != http.StatusNotFound {
		t.Fatalf("cross-owner mission events = %d, want 404", crossResponse.StatusCode)
	}
}
