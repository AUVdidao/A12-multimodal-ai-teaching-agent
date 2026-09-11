package rag

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestSearchRequiresExplicitContractPath(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		called = true
	}))
	defer server.Close()

	_, err := NewClient(server.URL, "", "/internal/v1/read-material").Search(context.Background(), 7, "query", nil)
	if !errors.Is(err, ErrNotConfigured) {
		t.Fatalf("Search error = %v, want ErrNotConfigured", err)
	}
	if called {
		t.Fatal("Search called an endpoint without an explicit RAG contract path")
	}
}

func TestReadRequiresExplicitContractPath(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		called = true
	}))
	defer server.Close()

	_, err := NewClient(server.URL, "/explicit-search", "").Read(context.Background(), 7, 9, "page-1")
	if !errors.Is(err, ErrNotConfigured) {
		t.Fatalf("Read error = %v, want ErrNotConfigured", err)
	}
	if called {
		t.Fatal("Read called an endpoint without an explicit RAG contract path")
	}
}

func TestLegacyWorkflowPathIsBlockedEvenWhenExplicitlyConfigured(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		called = true
	}))
	defer server.Close()

	variants := []string{
		legacyWorkflowPath,
		"/" + legacyWorkflowPath + "/",
		" /api/ai-workflow/knowledge-retrieval?x=1 ",
		legacyWorkflowPath + "#fragment",
		legacyWorkflowPath + "?x=1#fragment",
		"/api/ai-workflow//knowledge-retrieval",
		"/api/ai-workflow/%6bnowledge-retrieval",
	}
	for _, variant := range variants {
		t.Run(variant, func(t *testing.T) {
			if !isLegacyWorkflowPath(variant) {
				t.Fatalf("variant %q was not recognized as the legacy path", variant)
			}
			_, err := NewClient(server.URL, variant, "/explicit-read").Search(context.Background(), 7, "query", nil)
			if !errors.Is(err, ErrLegacyWorkflowPath) {
				t.Fatalf("Search error = %v, want ErrLegacyWorkflowPath", err)
			}
			if called {
				t.Fatal("Search called the legacy Workflow/Kimi path")
			}

			_, err = NewClient(server.URL, "/explicit-search", variant).Read(context.Background(), 7, 9, "page-1")
			if !errors.Is(err, ErrLegacyWorkflowPath) {
				t.Fatalf("Read error = %v, want ErrLegacyWorkflowPath", err)
			}
			if called {
				t.Fatal("Read called the legacy Workflow/Kimi path")
			}
		})
	}
}

func TestExplicitRAGTimeoutIsClassified(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(100 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	_, err := NewClient(server.URL, "/explicit-search", "/explicit-read").Search(ctx, 7, "query", nil)
	if err == nil || err.Error() != "RAG_TIMEOUT" {
		t.Fatalf("Search timeout error = %v, want RAG_TIMEOUT", err)
	}
}
