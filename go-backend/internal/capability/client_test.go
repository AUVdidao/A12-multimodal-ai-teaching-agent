package capability

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestGetCallsConfiguredCapabilityEndpoint(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/capability" || r.Header.Get("Content-Type") != "application/json" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL)
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("read capability request: %v", err)
			return
		}
		var input map[string]any
		if err := json.Unmarshal(body, &input); err != nil || input["missionId"] != float64(7) {
			t.Fatalf("unexpected body: %s", body)
		}
		w.Header().Set("Content-Type", "application/json")
		if _, err := w.Write([]byte(`{"role":"lecture","slots":3}`)); err != nil {
			t.Errorf("write capability fixture response: %v", err)
		}
	}))
	defer server.Close()

	client := NewClient(server.URL+"/capability", time.Second, 1024)
	result, err := client.Get(context.Background(), 7)
	if err != nil {
		t.Fatal(err)
	}
	object, ok := result.(map[string]any)
	if !ok || object["role"] != "lecture" {
		t.Fatalf("result = %#v", result)
	}
}

func TestGetForOwnerUsesEnvelopeAndServiceAuthorization(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer internal-capability-token" {
			t.Fatalf("authorization = %q", r.Header.Get("Authorization"))
		}
		if r.Header.Get("X-LessonForge-Actor-User-Id") != "22" {
			t.Fatalf("actor = %q", r.Header.Get("X-LessonForge-Actor-User-Id"))
		}
		var input map[string]any
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil || input["missionId"] != float64(11) || input["ownerUserId"] != float64(22) {
			t.Fatalf("request body = %#v, err=%v", input, err)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"code": 0, "message": "success", "data": map[string]any{"role": "lecture"}})
	}))
	defer server.Close()
	result, err := NewAuthenticatedClient(server.URL, "internal-capability-token", time.Second, 1024).GetForOwner(context.Background(), 22, 11)
	if err != nil {
		t.Fatal(err)
	}
	object, ok := result.(map[string]any)
	if !ok || object["role"] != "lecture" {
		t.Fatalf("result = %#v", result)
	}
}

func TestGetProfileRequiresStableIdentityAndCompleteNativeProfile(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var input map[string]any
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil || input["templateFileSha256"] != "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {
			t.Fatalf("request body = %#v, err=%v", input, err)
		}
		w.Header().Set("Content-Type", "application/json")
		if _, err := w.Write([]byte(`{"code":0,"message":"success","data":{"missionId":11,"id":2,"templateId":"lesson","templateFileSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sourceVersionId":9,"version":2,"engineNativeProfile":{"contractVersion":"1.0.0","profileId":"profile-2","projectId":"99","ownerUserId":"22","templateId":"lesson","templateVersion":3,"profileVersion":2,"status":"READY","pageSize":{},"spatialProfile":{},"templatePageReferences":[],"components":[],"textFitPolicy":{},"executionStatus":"EXECUTION_READY","sourceVersionId":9,"sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","parserSnapshotChecksum":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}}`)); err != nil {
			t.Errorf("write capability fixture response: %v", err)
		}
	}))
	defer server.Close()
	profile, err := NewClient(server.URL, time.Second, 4096).GetProfile(context.Background(), 22, 11, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	if err != nil || profile["templateId"] != "lesson" || profile["templateFileSha256"] != "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {
		t.Fatalf("profile=%#v err=%v", profile, err)
	}

	incomplete := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if _, err := w.Write([]byte(`{"templateId":"lesson"}`)); err != nil {
			t.Errorf("write incomplete capability fixture response: %v", err)
		}
	}))
	defer incomplete.Close()
	if _, err := NewClient(incomplete.URL, time.Second, 1024).GetProfile(context.Background(), 22, 11, ""); !errors.Is(err, ErrProfileIncomplete) {
		t.Fatalf("incomplete profile error = %v", err)
	}
}

func TestAuthenticatedClientFailsClosedWithoutBearer(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		called = true
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()
	_, err := NewAuthenticatedClient(server.URL, "", time.Second, 1024).GetForOwner(context.Background(), 22, 11)
	if !errors.Is(err, ErrAuthorizationNotConfigured) {
		t.Fatalf("missing bearer error = %v", err)
	}
	if called {
		t.Fatal("request was sent without internal authorization")
	}
}

func TestGetProfileRejectsMismatchedNativeAndTemplateDigest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"code":0,"message":"success","data":{"templateId":"lesson","templateFileSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","templateFileVersion":"3","templateProfileVersion":"2","contractVersion":"1.0.0","profileId":"profile-2","projectId":"11","ownerUserId":"22","templateVersion":3,"profileVersion":2,"status":"READY","pageSize":{},"spatialProfile":{},"templatePageReferences":[],"components":[],"textFitPolicy":{},"executionStatus":"EXECUTION_READY","sourceVersionId":9,"sourceSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","parserSnapshotChecksum":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}}`))
	}))
	defer server.Close()
	if _, err := NewClient(server.URL, time.Second, 4096).GetProfile(context.Background(), 22, 11, ""); !errors.Is(err, ErrProfileIncomplete) {
		t.Fatalf("mismatched digest error = %v", err)
	}
}
