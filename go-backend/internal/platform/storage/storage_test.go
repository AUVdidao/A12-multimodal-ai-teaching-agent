package storage

import (
	"bytes"
	"context"
	"mime/multipart"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestSafePathNeverEscapesRoot(t *testing.T) {
	root := t.TempDir()
	service, err := New(root, 1024)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.Open(context.Background(), "../outside"); err == nil {
		t.Fatal("parent traversal unexpectedly accepted")
	}
	if _, err := service.Open(context.Background(), filepath.Join(root, "outside")); err == nil {
		t.Fatal("absolute path unexpectedly accepted")
	}
	if err := os.WriteFile(filepath.Join(root, "safe.txt"), []byte("ok"), 0o640); err != nil {
		t.Fatal(err)
	}
	file, err := service.Open(context.Background(), "safe.txt")
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestSafePathRejectsSymlinkEscape(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	service, err := New(root, 1024)
	if err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "linked")
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if _, err := service.Open(context.Background(), "linked/secret.pptx"); err == nil {
		t.Fatal("symlink escape unexpectedly accepted")
	}
}

func TestSaveMultipartInfersKnownMimeWhenClientUsesGenericMime(t *testing.T) {
	root := t.TempDir()
	service, err := New(root, 1024)
	if err != nil {
		t.Fatal(err)
	}

	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	part, err := writer.CreateFormFile("file", "textbook.pdf")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write([]byte("pdf")); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest("POST", "/api/uploads", body)
	request.Header.Set("Content-Type", writer.FormDataContentType())
	if err := request.ParseMultipartForm(1024); err != nil {
		t.Fatal(err)
	}

	saved, err := service.SaveMultipart(context.Background(), request.MultipartForm.File["file"][0])
	if err != nil {
		t.Fatal(err)
	}
	if saved.MimeType != "application/pdf" {
		t.Fatalf("saved MIME = %q, want application/pdf", saved.MimeType)
	}
}

func TestNormalizedMultipartMimeInfersOfficeAndTextFormats(t *testing.T) {
	tests := map[string]string{
		"lesson.docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
		"lesson.md":   "text/markdown",
		"lesson.pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
		"lesson.xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
	}
	for name, want := range tests {
		if got := normalizedMultipartMime(name, "application/octet-stream"); got != want {
			t.Errorf("normalizedMultipartMime(%q) = %q, want %q", name, got, want)
		}
	}
}
