package httpapi

import (
	"bufio"
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

type sseTestEvent struct {
	id    int64
	event string
	data  map[string]any
}

func readSSETestEvent(t *testing.T, reader *bufio.Reader) sseTestEvent {
	t.Helper()
	var event sseTestEvent
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			t.Fatalf("read SSE line: %v", err)
		}
		line = strings.TrimRight(line, "\r\n")
		if line == "" {
			if event.id == 0 || event.event == "" || event.data == nil {
				t.Fatalf("incomplete SSE event: %+v", event)
			}
			return event
		}
		if strings.HasPrefix(line, "id: ") {
			id, err := strconv.ParseInt(strings.TrimPrefix(line, "id: "), 10, 64)
			if err != nil {
				t.Fatalf("invalid SSE id %q: %v", line, err)
			}
			event.id = id
		} else if strings.HasPrefix(line, "event: ") {
			event.event = strings.TrimPrefix(line, "event: ")
		} else if strings.HasPrefix(line, "data: ") {
			if err := json.Unmarshal([]byte(strings.TrimPrefix(line, "data: ")), &event.data); err != nil {
				t.Fatalf("invalid SSE data: %v", err)
			}
		}
	}
}

func TestEventsHTTPStreamsCursorReconnectAndOwnerIsolation(t *testing.T) {
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
	owner, err := store.CreateUser(ctx, "SSE owner", "sse-owner-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	other, err := store.CreateUser(ctx, "SSE other", "sse-other-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	var missionID, otherMissionID int64
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title) VALUES($1,'SELF_CREATED','SSE mission') RETURNING id`, owner.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title) VALUES($1,'SELF_CREATED','Other SSE mission') RETURNING id`, other.ID).Scan(&otherMissionID); err != nil {
		t.Fatal(err)
	}
	if err := store.AddActivity(ctx, missionID, "MISSION_CREATED", "Mission created", "MISSION", strconv.FormatInt(missionID, 10)); err != nil {
		t.Fatal(err)
	}
	if err := store.AddActivity(ctx, missionID, "MESSAGE_CREATED", "Teacher sent a message", "MESSAGE", "message-1"); err != nil {
		t.Fatal(err)
	}
	if err := store.AddActivity(ctx, otherMissionID, "MESSAGE_CREATED", "Other teacher message", "MESSAGE", "other-message"); err != nil {
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
		for _, id := range []int64{missionID, otherMissionID} {
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
	missionPath := "/api/missions/" + strconv.FormatInt(missionID, 10) + "/events"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL+missionPath, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+ownerToken)
	response, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || !strings.HasPrefix(response.Header.Get("Content-Type"), "text/event-stream") {
		response.Body.Close()
		t.Fatalf("initial SSE response = %d/%q", response.StatusCode, response.Header.Get("Content-Type"))
	}
	reader := bufio.NewReader(response.Body)
	first := readSSETestEvent(t, reader)
	second := readSSETestEvent(t, reader)
	response.Body.Close()
	if first.event != "MISSION_CREATED" || second.event != "MESSAGE_CREATED" || first.id >= second.id {
		t.Fatalf("initial SSE events = %+v, %+v", first, second)
	}

	if err := store.AddActivity(ctx, missionID, "PLAN_DRAFT_CREATED", "Draft created", "PLANNING_DRAFT", "draft-1"); err != nil {
		t.Fatal(err)
	}
	reconnectURL := server.URL + missionPath + "?after=" + strconv.FormatInt(second.id, 10)
	reconnectReq, err := http.NewRequestWithContext(ctx, http.MethodGet, reconnectURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	reconnectReq.Header.Set("Authorization", "Bearer "+ownerToken)
	reconnect, err := server.Client().Do(reconnectReq)
	if err != nil {
		t.Fatal(err)
	}
	if reconnect.StatusCode != http.StatusOK {
		reconnect.Body.Close()
		t.Fatalf("reconnect SSE status = %d", reconnect.StatusCode)
	}
	next := readSSETestEvent(t, bufio.NewReader(reconnect.Body))
	reconnect.Body.Close()
	if next.event != "PLAN_DRAFT_CREATED" || next.id <= second.id {
		t.Fatalf("cursor reconnect event = %+v, last=%d", next, second.id)
	}

	crossReq, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL+missionPath, nil)
	if err != nil {
		t.Fatal(err)
	}
	crossReq.Header.Set("Authorization", "Bearer "+otherToken)
	cross, err := server.Client().Do(crossReq)
	if err != nil {
		t.Fatal(err)
	}
	cross.Body.Close()
	if cross.StatusCode != http.StatusNotFound {
		t.Fatalf("cross-owner SSE status = %d, want 404", cross.StatusCode)
	}

	invalidReq, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL+missionPath+"?after=-1", nil)
	if err != nil {
		t.Fatal(err)
	}
	invalidReq.Header.Set("Authorization", "Bearer "+ownerToken)
	invalid, err := server.Client().Do(invalidReq)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(invalid.Body)
	invalid.Body.Close()
	if invalid.StatusCode != http.StatusBadRequest || !strings.Contains(string(body), "EVENT_CURSOR_INVALID") {
		t.Fatalf("invalid cursor response = %d/%s", invalid.StatusCode, body)
	}
}
