package pptengine

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestV2ClientUsesFrozenComposeThenExecuteEndpoints(t *testing.T) {
	var mu sync.Mutex
	var paths []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("read request: %v", err)
		}
		if r.Method != http.MethodPost || r.Header.Get("Content-Type") != "application/json" || len(bytes.TrimSpace(body)) == 0 {
			t.Errorf("unexpected request: method=%s content-type=%q body=%q", r.Method, r.Header.Get("Content-Type"), body)
		}
		mu.Lock()
		paths = append(paths, r.URL.Path)
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/internal/v1/compose-plan" {
			_, _ = w.Write([]byte(`{"contractVersion":"2.0.0","planContractVersion":"2.0.0","plan":{"slides":[]}}`))
			return
		}
		if r.URL.Path == "/internal/v2/execute" {
			_, _ = w.Write([]byte(`{"contractVersion":"2.0.0","status":"FAILED","artifacts":[],"feedback":[]}`))
			return
		}
		http.NotFound(w, r)
	}))
	defer server.Close()

	client := NewClient(server.URL, "", time.Second)
	compose, err := client.ComposePlan(context.Background(), json.RawMessage(`{"contractVersion":"2.0.0"}`))
	if err != nil {
		t.Fatal(err)
	}
	if string(compose.Plan) != `{"slides":[]}` {
		t.Fatalf("compose plan = %s", compose.Plan)
	}
	if _, err := client.ExecuteV2(context.Background(), json.RawMessage(`{"plan":{"slides":[]}}`)); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	want := []string{"/internal/v1/compose-plan", "/internal/v2/execute"}
	if len(paths) != len(want) || paths[0] != want[0] || paths[1] != want[1] {
		t.Fatalf("request paths = %#v, want %#v", paths, want)
	}
}

func TestReadArtifactVerifiesSharedEngineOutput(t *testing.T) {
	root := t.TempDir()
	data := []byte("non-empty pptx test payload")
	path := filepath.Join(root, "run-1", "generation-result.pptx")
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o640); err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(data)
	client := NewClient("http://engine", root, time.Second)
	got, err := client.ReadArtifact(context.Background(), ArtifactReceipt{
		ArtifactID: "artifact-1",
		StorageKey: "run-1/generation-result.pptx",
		SHA256:     stringHash(hash),
		Size:       int64(len(data)),
	}, 1024)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(data) {
		t.Fatalf("artifact = %q", got)
	}
	_, err = client.ReadArtifact(context.Background(), ArtifactReceipt{StorageKey: "../outside", SHA256: stringHash(hash)}, 1024)
	if err == nil {
		t.Fatal("path traversal unexpectedly succeeded")
	}
}

func TestReadArtifactRequiresExplicitBridge(t *testing.T) {
	client := NewClient("http://engine", "", time.Second)
	_, err := client.ReadArtifact(context.Background(), ArtifactReceipt{StorageKey: "run/file.pptx"}, 1024)
	if !errors.Is(err, ErrArtifactBridgeNotConfigured) {
		t.Fatalf("error = %v", err)
	}
}

func TestReadArtifactRejectsZeroReceiptSizeForNonEmptyFile(t *testing.T) {
	root := t.TempDir()
	data := []byte("non-empty artifact")
	path := filepath.Join(root, "artifact.pptx")
	if err := os.WriteFile(path, data, 0o640); err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(data)
	_, err := NewClient("http://engine", root, time.Second).ReadArtifact(context.Background(), ArtifactReceipt{
		StorageKey: "artifact.pptx",
		SHA256:     stringHash(hash),
		Size:       0,
	}, 1024)
	if err == nil || err.Error() != "PPT_ENGINE_ARTIFACT_SIZE_MISMATCH" {
		t.Fatalf("error = %v", err)
	}
}

func TestReadArtifactRejectsSymlinkOutsideArtifactRoot(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	data := []byte("outside artifact")
	outsideFile := filepath.Join(outside, "outside.pptx")
	if err := os.WriteFile(outsideFile, data, 0o640); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "linked.pptx")
	if err := os.Symlink(outsideFile, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	hash := sha256.Sum256(data)
	_, err := NewClient("http://engine", root, time.Second).ReadArtifact(context.Background(), ArtifactReceipt{StorageKey: "linked.pptx", SHA256: stringHash(hash), Size: int64(len(data))}, 1024)
	if err == nil {
		t.Fatal("artifact symlink escaped the configured root")
	}
}

func stringHash(hash [32]byte) string {
	const hex = "0123456789abcdef"
	result := make([]byte, 64)
	for i, value := range hash {
		result[i*2] = hex[value>>4]
		result[i*2+1] = hex[value&0x0f]
	}
	return string(result)
}
