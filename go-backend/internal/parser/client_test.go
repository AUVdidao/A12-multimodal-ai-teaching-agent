package parser

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"lessonforge.local/backend/internal/model"
)

func testFile() model.MissionFile {
	return model.MissionFile{ID: 7, MissionID: 11, OwnerUserID: 11, FileObject: model.FileObject{OriginalName: "material.txt", SHA256: strings.Repeat("a", 64)}}
}

func TestClientAcceptsExistingParserResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || !strings.HasPrefix(r.Header.Get("Content-Type"), "multipart/form-data;") {
			t.Fatalf("unexpected parser request")
		}
		if err := r.ParseMultipartForm(1024 * 1024); err != nil {
			t.Fatalf("parse multipart request: %v", err)
		}
		if got := r.FormValue("fileType"); got != "TXT" {
			t.Fatalf("fileType form field = %q", got)
		}
		if got := r.URL.Query().Get("fileType"); got != "" {
			t.Fatalf("fileType must not be query-only, got %q", got)
		}
		file, header, err := r.FormFile("file")
		if err != nil {
			t.Fatalf("file part missing: %v", err)
		}
		defer file.Close()
		if header.Filename != "material.txt" {
			t.Fatalf("file name = %q", header.Filename)
		}
		content, err := io.ReadAll(file)
		if err != nil || string(content) != "source" {
			t.Fatalf("file content = %q, err = %v", content, err)
		}
		w.Header().Set("Content-Type", "application/json")
		if _, err := w.Write([]byte(`{"summary":"parsed","keywords":[],"teachingStages":["概念讲解"],"analysisText":"parsed","extractedText":"source","pageCount":null,"sections":[]}`)); err != nil {
			t.Errorf("write parser fixture response: %v", err)
		}
	}))
	defer server.Close()
	result, err := NewClient(server.URL, time.Second).Parse(context.Background(), testFile(), strings.NewReader("source"))
	if err != nil {
		t.Fatal(err)
	}
	if result.Summary != "parsed" || result.ExtractedText != "source" {
		t.Fatalf("parsed result = %#v", result)
	}
	if sections, ok := result.Sections.([]any); !ok || len(sections) != 0 {
		t.Fatalf("sections = %#v", result.Sections)
	}
}

func TestClientRejectsMalformedParserResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if _, err := w.Write([]byte(`{"ok":true}`)); err != nil {
			t.Errorf("write parser fixture response: %v", err)
		}
	}))
	defer server.Close()
	if _, err := NewClient(server.URL, time.Second).Parse(context.Background(), testFile(), strings.NewReader("source")); err == nil {
		t.Fatal("parser accepted an unverified success response")
	}
}

func TestClientTimeoutIsExplicit(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(100 * time.Millisecond)
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	_, err := NewClient(server.URL, time.Second).Parse(ctx, testFile(), strings.NewReader("source"))
	if err == nil || (!strings.Contains(err.Error(), "PARSER_TIMEOUT") && !errors.Is(err, context.DeadlineExceeded)) {
		t.Fatalf("error = %v", err)
	}
}

func TestParserFileTypeUsesExtensionAndLegacyWordMime(t *testing.T) {
	cases := []struct {
		name string
		file model.MissionFile
		want string
	}{
		{name: "legacy word extension", file: model.MissionFile{FileObject: model.FileObject{OriginalName: "lesson.doc"}}, want: "WORD"},
		{name: "docx extension", file: model.MissionFile{FileObject: model.FileObject{OriginalName: "lesson.docx"}}, want: "DOCX"},
		{name: "legacy word mime", file: model.MissionFile{FileObject: model.FileObject{OriginalName: "lesson", MimeType: "application/msword"}}, want: "WORD"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			if got := parserFileType(test.file); got != test.want {
				t.Fatalf("parserFileType = %q, want %q", got, test.want)
			}
		})
	}
}

func TestClientReportsParserHTTPStatus(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = w.Write([]byte(`{"code":"PARSER_INPUT_INVALID"}`))
	}))
	defer server.Close()
	if _, err := NewClient(server.URL, time.Second).Parse(context.Background(), testFile(), strings.NewReader("source")); err == nil || err.Error() != "PARSER_HTTP_422" {
		t.Fatalf("error = %v, want PARSER_HTTP_422", err)
	}
}

func TestClientRejectsResponseOverConfiguredBound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"summary":"` + strings.Repeat("x", 128) + `","keywords":[],"teachingStages":[]}`))
	}))
	defer server.Close()
	if _, err := NewClientWithMaxResponseBytes(server.URL, time.Second, 64).Parse(context.Background(), testFile(), strings.NewReader("source")); err == nil || err.Error() != "PARSER_RESPONSE_INVALID" {
		t.Fatalf("error = %v, want bounded parser response rejection", err)
	}
}
