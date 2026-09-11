package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"lessonforge.local/backend/internal/model"
)

func TestGenerateRouteIsNotAUserFacingGenerationEntryPoint(t *testing.T) {
	server := NewServer(Config{SessionCookie: "lessonforge-session"}, nil, nil, nil, nil, nil, nil)
	request := httptest.NewRequest(http.MethodPost, "/api/specifications/spec-1/generate", nil)
	response := httptest.NewRecorder()

	server.Router().ServeHTTP(response, request)

	if response.Code != http.StatusNotFound {
		t.Fatalf("removed generate route status = %d, want %d", response.Code, http.StatusNotFound)
	}
}

func TestSafeErrorCodeClassifiesProviderHTTPStatuses(t *testing.T) {
	tests := []struct {
		status int
		want   string
	}{
		{status: 401, want: "AUTHENTICATION_FAILED"},
		{status: 403, want: "AUTHENTICATION_FAILED"},
		{status: 402, want: "INSUFFICIENT_BALANCE"},
		{status: 404, want: "MODEL_NOT_FOUND"},
		{status: 408, want: "MODEL_TIMEOUT"},
		{status: 429, want: "RATE_LIMITED"},
		{status: 400, want: "MODEL_REQUEST_REJECTED"},
		{status: 502, want: "MODEL_PROVIDER_ERROR"},
	}
	for _, tt := range tests {
		if got := safeErrorCode(&model.HTTPError{Status: tt.status, Body: "provider-code"}); got != tt.want {
			t.Errorf("safeErrorCode(%d) = %q, want %q", tt.status, got, tt.want)
		}
	}
	if got := safeErrorCode(model.ErrTimeout); got != "MODEL_TIMEOUT" {
		t.Errorf("safeErrorCode(timeout) = %q, want MODEL_TIMEOUT", got)
	}
}
