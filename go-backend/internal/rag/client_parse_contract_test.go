package rag

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestValidateJavaParseResultRejectsLargeObjectOIDPlaceholder(t *testing.T) {
	result := javaParseResult{
		ParseStatus:              "SUCCEEDED",
		Summary:                  "69497",
		Keywords:                 []string{},
		ApplicableTeachingStages: []string{},
		ExtractedTextPreview:     "69496",
	}
	if err := validateJavaParseResult(result); err == nil || err.Error() != "RAG_PARSE_RESULT_PLACEHOLDER" {
		t.Fatalf("validation error = %v, want RAG_PARSE_RESULT_PLACEHOLDER", err)
	}
}

func TestParseMaterialRejectsLargeObjectOIDPlaceholder(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"code":    0,
			"message": "ok",
			"data": javaParseResult{
				ParseStatus:              "SUCCEEDED",
				Summary:                  "69497",
				Keywords:                 []string{},
				ApplicableTeachingStages: []string{},
				ExtractedTextPreview:     "69496",
			},
		})
	}))
	defer server.Close()

	client := NewJavaClient(server.URL, time.Second, 1<<20)
	err := client.ParseMaterial(context.Background(), 15, 16, MaterialIdentity{
		MissionID: 78, MissionFileID: 21, OwnerUserID: 58, ActorUserID: 58,
		SourceSHA256: "a", SourceSize: 1,
	})
	if err == nil || err.Error() != "RAG_PARSE_RESULT_PLACEHOLDER" {
		t.Fatalf("ParseMaterial error = %v, want RAG_PARSE_RESULT_PLACEHOLDER", err)
	}
}
