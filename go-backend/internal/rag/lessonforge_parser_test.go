package rag

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"lessonforge.local/backend/internal/model"
)

func TestLessonForgeParserAdapterRunsUploadParseIndexAndReturnsServerResult(t *testing.T) {
	content := []byte("TCP three-way handshake")
	digest := sha256.Sum256(content)
	sha := hex.EncodeToString(digest[:])
	filePath := filepath.Join(t.TempDir(), "textbook.pdf")
	if err := os.WriteFile(filePath, content, 0o640); err != nil {
		t.Fatal(err)
	}

	const missionID int64 = 7
	const missionFileID int64 = 9
	const ownerID int64 = 101
	const fileObjectID int64 = 19
	const projectID int64 = 44
	const materialID int64 = 55
	store := &fakeResourceStore{
		mission: model.Mission{ID: missionID, OwnerTeacherID: ownerID, Title: "TCP lesson"},
		files: []model.MissionFile{{
			ID: missionFileID, MissionID: missionID, OwnerUserID: ownerID,
			Role: "MATERIAL", Provenance: "TEACHER", ParseStatus: "PENDING",
			FileObject: model.FileObject{ID: fileObjectID, OriginalName: "textbook.pdf", MimeType: "application/pdf", Size: int64(len(content)), SHA256: sha, StorageKey: "uploads/19"},
		}},
	}
	var routes []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		routes = append(routes, r.Method+" "+r.URL.Path)
		if got := r.Header.Get("X-LessonForge-Actor-User-Id"); got != strconv.FormatInt(ownerID, 10) {
			t.Errorf("actor header = %q, want %d", got, ownerID)
		}
		w.Header().Set("Content-Type", "application/json")
		writeEnvelope := func(data any) {
			_ = json.NewEncoder(w).Encode(map[string]any{"code": 0, "message": "ok", "data": data})
		}
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/projects":
			writeEnvelope([]JavaProject{})
		case r.Method == http.MethodPost && r.URL.Path == "/api/projects":
			writeEnvelope(JavaProject{ID: projectID, ProjectName: "LessonForge Mission 7", Description: "lessonforge:mission:7"})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/internal/lessonforge/projects/44/materials":
			if err := r.ParseMultipartForm(1 << 20); err != nil {
				t.Errorf("parse multipart: %v", err)
				return
			}
			file, header, err := r.FormFile("file")
			if err != nil {
				t.Errorf("form file: %v", err)
				return
			}
			defer file.Close()
			body, err := io.ReadAll(file)
			if err != nil {
				t.Errorf("read upload: %v", err)
				return
			}
			if string(body) != string(content) || header.Filename != "textbook.pdf" || r.FormValue("missionId") != "7" || r.FormValue("missionFileId") != "9" || r.FormValue("ownerUserId") != "101" || r.FormValue("actorUserId") != "101" || r.FormValue("sourceSha256") != sha || r.FormValue("sourceSize") != strconv.Itoa(len(content)) {
				t.Errorf("unexpected LessonForge intake identity or body")
			}
			if got := header.Header.Get("Content-Type"); got != "application/pdf" {
				t.Errorf("uploaded part content type = %q, want application/pdf", got)
			}
			writeEnvelope(map[string]any{"bindingId": 71, "missionId": missionID, "missionFileId": missionFileID, "projectId": projectID, "materialId": materialID, "sourceSha256": sha, "sourceSize": len(content), "originalFilename": "textbook.pdf", "bindingStatus": "BOUND"})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/internal/lessonforge/projects/44/materials/55/parse":
			var identity MaterialIdentity
			if err := json.NewDecoder(r.Body).Decode(&identity); err != nil {
				t.Errorf("decode parse identity: %v", err)
			}
			if identity.MissionID != missionID || identity.MissionFileID != missionFileID || identity.OwnerUserID != ownerID || identity.ActorUserID != ownerID || identity.SourceSHA256 != sha || identity.SourceSize != int64(len(content)) {
				t.Errorf("parse identity = %#v", identity)
			}
			writeEnvelope(javaParseResult{ParseStatus: "SUCCEEDED", Summary: "TCP connection setup", Keywords: []string{"TCP", "SYN"}, ApplicableTeachingStages: []string{"EXPLAIN"}, ExtractedTextPreview: "A SYN is sent first.", PageCount: intPtr(3), Sections: []string{"handshake"}})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/internal/lessonforge/projects/44/materials/55/index":
			var identity MaterialIdentity
			if err := json.NewDecoder(r.Body).Decode(&identity); err != nil {
				t.Errorf("decode index identity: %v", err)
			}
			if identity.MissionID != missionID || identity.MissionFileID != missionFileID || identity.OwnerUserID != ownerID || identity.ActorUserID != ownerID || identity.SourceSHA256 != sha || identity.SourceSize != int64(len(content)) {
				t.Errorf("index identity = %#v", identity)
			}
			writeEnvelope(map[string]any{"indexed": true})
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client := NewJavaClient(server.URL, time.Second, 1<<20)
	adapter := NewLessonForgeParserAdapter(client, store)
	got, err := adapter.Parse(context.Background(), store.files[0], bytesReader(content))
	if err != nil {
		t.Fatal(err)
	}
	if got.Summary != "TCP connection setup" || got.ExtractedText != "A SYN is sent first." || got.PageCount == nil || *got.PageCount != 3 {
		t.Fatalf("parse result = %#v", got)
	}
	if store.projectID != projectID || store.projectSaves != 1 || store.material.RAGProjectID != projectID || store.material.RAGMaterialID != materialID || store.materialSaves != 1 {
		t.Fatalf("mapping state = %#v", store)
	}
	wantRoutes := []string{
		"GET /api/projects",
		"POST /api/projects",
		"POST /api/v1/internal/lessonforge/projects/44/materials",
		"POST /api/v1/internal/lessonforge/projects/44/materials/55/parse",
		"POST /api/v1/internal/lessonforge/projects/44/materials/55/index",
	}
	if strings.Join(routes, "\n") != strings.Join(wantRoutes, "\n") {
		t.Fatalf("routes = %v, want %v", routes, wantRoutes)
	}
}

func bytesReader(content []byte) io.Reader {
	return strings.NewReader(string(content))
}

func intPtr(value int) *int {
	return &value
}
