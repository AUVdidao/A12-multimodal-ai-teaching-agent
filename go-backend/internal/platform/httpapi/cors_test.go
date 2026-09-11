package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCORSAllowsElectronFileOriginAndRejectsUnknownOrigin(t *testing.T) {
	server := &Server{cfg: Config{CORSOrigins: []string{"null", "http://127.0.0.1:4173"}}}
	handler := server.cors(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) }))

	allowed := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodOptions, "/api/missions", nil)
	request.Header.Set("Origin", "null")
	handler.ServeHTTP(allowed, request)
	if allowed.Code != http.StatusNoContent || allowed.Header().Get("Access-Control-Allow-Origin") != "null" {
		t.Fatalf("allowed CORS response = %d, origin=%q", allowed.Code, allowed.Header().Get("Access-Control-Allow-Origin"))
	}

	rejected := httptest.NewRecorder()
	request = httptest.NewRequest(http.MethodOptions, "/api/missions", nil)
	request.Header.Set("Origin", "https://attacker.example")
	handler.ServeHTTP(rejected, request)
	if rejected.Code != http.StatusForbidden {
		t.Fatalf("rejected CORS response = %d", rejected.Code)
	}
}
