package rag

import (
	"context"
	"encoding/json"
	"errors"
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
	parserpkg "lessonforge.local/backend/internal/parser"
)

type failOnceResourceStore struct {
	*fakeResourceStore
	saveCalls    int
	statusCalls  []string
	parseResults []model.ParseResult
}

func (s *failOnceResourceStore) SaveRAGMaterialBinding(ctx context.Context, owner, missionID, missionFileID, ragProjectID, ragMaterialID int64, sourceSHA256 string) error {
	s.saveCalls++
	if s.saveCalls == 1 {
		return errors.New("RAG_LOCAL_BINDING_PERSIST_FAILED")
	}
	return s.fakeResourceStore.SaveRAGMaterialBinding(ctx, owner, missionID, missionFileID, ragProjectID, ragMaterialID, sourceSHA256)
}

func (s *failOnceResourceStore) PendingMissionFiles(context.Context, int) ([]model.MissionFile, error) {
	return s.files, nil
}

func (s *failOnceResourceStore) SetMissionFileParseStatus(_ context.Context, owner, missionFileID int64, status string) error {
	s.statusCalls = append(s.statusCalls, strconv.FormatInt(owner, 10)+":"+strconv.FormatInt(missionFileID, 10)+":"+status)
	return nil
}

func (s *failOnceResourceStore) SaveMissionFileParseResult(_ context.Context, _ int64, _ int64, result model.ParseResult) error {
	s.parseResults = append(s.parseResults, result)
	return nil
}

func TestLessonForgeParserRetryReusesJavaMaterialAfterGoBindingFailure(t *testing.T) {
	content := []byte("TCP three-way handshake")
	const (
		missionID     int64 = 7
		missionFileID int64 = 9
		ownerID       int64 = 101
		fileObjectID  int64 = 19
		projectID     int64 = 44
		materialID    int64 = 55
	)
	filePath := filepath.Join(t.TempDir(), "textbook.pdf")
	if err := os.WriteFile(filePath, content, 0o640); err != nil {
		t.Fatal(err)
	}

	store := &failOnceResourceStore{fakeResourceStore: &fakeResourceStore{
		mission: model.Mission{ID: missionID, OwnerTeacherID: ownerID, Title: "TCP lesson"},
		files: []model.MissionFile{{
			ID: missionFileID, MissionID: missionID, OwnerUserID: ownerID,
			Role: "MATERIAL", Provenance: "TEACHER", ParseStatus: "PENDING",
			FileObject: model.FileObject{ID: fileObjectID, OriginalName: "textbook.pdf", MimeType: "application/pdf", Size: int64(len(content)), SHA256: "sha-material", StorageKey: "uploads/19"},
		}},
	}}

	var uploadCalls int
	var parseCalls int
	var indexCalls int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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
			uploadCalls++
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
			if string(body) != string(content) || header.Filename != "textbook.pdf" ||
				r.FormValue("missionId") != strconv.FormatInt(missionID, 10) ||
				r.FormValue("missionFileId") != strconv.FormatInt(missionFileID, 10) ||
				r.FormValue("ownerUserId") != strconv.FormatInt(ownerID, 10) ||
				r.FormValue("actorUserId") != strconv.FormatInt(ownerID, 10) ||
				r.FormValue("sourceSha256") != "sha-material" ||
				r.FormValue("sourceSize") != strconv.Itoa(len(content)) {
				t.Errorf("retry changed cross-service identity or body")
			}
			writeEnvelope(map[string]any{
				"bindingId": 71, "missionId": missionID, "missionFileId": missionFileID,
				"projectId": projectID, "materialId": materialID, "sourceSha256": "sha-material",
				"sourceSize": len(content), "originalFilename": "textbook.pdf", "bindingStatus": "BOUND",
			})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/internal/lessonforge/projects/44/materials/55/parse":
			parseCalls++
			writeEnvelope(javaParseResult{
				ParseStatus: "SUCCEEDED", Summary: "TCP connection setup", Keywords: []string{"TCP"},
				ApplicableTeachingStages: []string{"EXPLAIN"}, ExtractedTextPreview: "A SYN is sent first.",
				PageCount: intPtr(3), Sections: []string{"handshake"},
			})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/internal/lessonforge/projects/44/materials/55/index":
			indexCalls++
			writeEnvelope(map[string]any{"indexed": true})
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client := NewJavaClient(server.URL, time.Second, 1<<20)
	adapter := NewLessonForgeParserAdapter(client, store)
	file := store.files[0]
	worker := &parserpkg.Worker{Store: store, Storage: fakeResourceStorage{path: filePath}, Adapter: adapter}
	if err := worker.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("first worker pass error = %v", err)
	}
	if store.saveCalls != 1 || len(store.statusCalls) != 0 || len(store.parseResults) != 0 {
		t.Fatalf("first worker pass state save=%d status=%v results=%v, want pending retry", store.saveCalls, store.statusCalls, store.parseResults)
	}
	if err := worker.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("retry worker pass error = %v", err)
	}
	if len(store.parseResults) != 1 || store.parseResults[0].Summary != "TCP connection setup" || store.parseResults[0].PageCount == nil || *store.parseResults[0].PageCount != 3 {
		t.Fatalf("retry parse result = %#v", store.parseResults)
	}
	if uploadCalls != 2 || parseCalls != 2 || indexCalls != 2 || store.saveCalls != 2 {
		t.Fatalf("retry calls upload=%d parse=%d index=%d save=%d, want 2 each", uploadCalls, parseCalls, indexCalls, store.saveCalls)
	}
	if store.material.RAGProjectID != projectID || store.material.RAGMaterialID != materialID || store.material.SourceSHA256 != "sha-material" {
		t.Fatalf("recovered local binding = %#v", store.material)
	}
	if strings.TrimSpace(file.FileObject.SHA256) != store.material.SourceSHA256 {
		t.Fatalf("binding source SHA = %q, file source SHA = %q", store.material.SourceSHA256, file.FileObject.SHA256)
	}
}
