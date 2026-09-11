package generation

import (
	"errors"
	"testing"

	"lessonforge.local/backend/internal/pptengine"
)

func TestSafeCodeDoesNotExposeEngineDetails(t *testing.T) {
	if got := safeCode(pptengine.ErrNotConfigured); got != "PPT_ENGINE_NOT_CONFIGURED" {
		t.Fatalf("safe code = %q", got)
	}
	if got := safeCode(errors.New("secret provider response")); got != "PPT_ENGINE_REQUEST_FAILED" {
		t.Fatalf("safe code = %q", got)
	}
}
