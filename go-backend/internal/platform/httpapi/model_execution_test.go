package httpapi

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestModelExecutionRoutesRequireDedicatedServiceBearer(t *testing.T) {
	server := NewServer(Config{ModelExecutionBearerToken: "bridge-secret"}, nil, nil, nil, nil, nil, nil)
	for _, route := range []string{"/internal/model-execution-leases", "/internal/model-executions/multimodal"} {
		for _, authorization := range []string{"", "Bearer wrong", "bridge-secret"} {
			t.Run(route+"/"+authorization, func(t *testing.T) {
				request := httptest.NewRequest(http.MethodPost, route, strings.NewReader(`{}`))
				if authorization != "" {
					request.Header.Set("Authorization", authorization)
				}
				response := httptest.NewRecorder()
				server.Router().ServeHTTP(response, request)
				if response.Code != http.StatusUnauthorized {
					t.Fatalf("status = %d, want 401 for %q", response.Code, authorization)
				}
			})
		}
	}
}

func TestModelExecutionLeaseRejectsUnboundManifestBeforeDatabaseAccess(t *testing.T) {
	server := NewServer(Config{ModelExecutionBearerToken: "bridge-secret"}, nil, nil, nil, nil, nil, nil)
	request := httptest.NewRequest(http.MethodPost, "/internal/model-execution-leases", strings.NewReader(`{"purpose":"TEMPLATE_ANALYZER"}`))
	request.Header.Set("Authorization", "Bearer bridge-secret")
	response := httptest.NewRecorder()
	server.Router().ServeHTTP(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", response.Code)
	}
	if !strings.Contains(response.Body.String(), "MODEL_EXECUTION_MANIFEST_INVALID") {
		t.Fatalf("response = %s", response.Body.String())
	}
}
