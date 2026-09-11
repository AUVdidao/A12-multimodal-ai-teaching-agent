package rag

import (
	"bytes"
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
)

type fakeResourceStore struct {
	mission        model.Mission
	files          []model.MissionFile
	projectID      int64
	material       model.RAGMaterialBinding
	materialByFile map[int64]model.RAGMaterialBinding
	projectSaves   int
	materialSaves  int
}

func (s *fakeResourceStore) MissionOwner(context.Context, int64) (int64, error) {
	return s.mission.OwnerTeacherID, nil
}

func (s *fakeResourceStore) GetMission(context.Context, int64, int64) (model.Mission, error) {
	return s.mission, nil
}

func (s *fakeResourceStore) MissionFiles(context.Context, int64, int64) ([]model.MissionFile, error) {
	return s.files, nil
}

func (s *fakeResourceStore) RAGProjectBinding(context.Context, int64, int64) (int64, bool, error) {
	return s.projectID, s.projectID > 0, nil
}

func (s *fakeResourceStore) SaveRAGProjectBinding(context.Context, int64, int64, int64) error {
	s.projectSaves++
	s.projectID = 44
	return nil
}

func (s *fakeResourceStore) RAGMaterialBinding(_ context.Context, _ int64, missionFileID int64) (model.RAGMaterialBinding, bool, error) {
	if binding, ok := s.materialByFile[missionFileID]; ok {
		return binding, true, nil
	}
	return s.material, s.material.RAGMaterialID > 0, nil
}

func (s *fakeResourceStore) SaveRAGMaterialBinding(_ context.Context, _, _, _, ragProjectID, ragMaterialID int64, sourceSHA256 string) error {
	s.materialSaves++
	s.material = model.RAGMaterialBinding{RAGProjectID: ragProjectID, RAGMaterialID: ragMaterialID, SourceSHA256: sourceSHA256}
	return nil
}

type fakeResourceStorage struct{ path string }

func (s fakeResourceStorage) Open(context.Context, string) (*os.File, error) {
	return os.Open(s.path)
}

func TestJavaSearchCreatesExplicitMappingsAndUsesReturnedIDs(t *testing.T) {
	content := []byte("TCP three-way handshake")
	filePath := filepath.Join(t.TempDir(), "textbook.pdf")
	if err := os.WriteFile(filePath, content, 0o640); err != nil {
		t.Fatal(err)
	}
	missionID := int64(7)
	ownerID := int64(101)
	store := &fakeResourceStore{
		mission: model.Mission{ID: missionID, OwnerTeacherID: ownerID, Title: "TCP lesson"},
		files: []model.MissionFile{{
			ID: 9, MissionID: missionID, OwnerUserID: ownerID, Role: "MATERIAL", Provenance: "TEACHER", ParseStatus: "READY",
			FileObject: model.FileObject{ID: 19, OriginalName: "textbook.pdf", MimeType: "application/pdf", Size: int64(len(content)), SHA256: "sha-material", StorageKey: "uploads/19"},
		}},
	}
	var searchBody map[string]any
	var routes []string
	actorHeaderValid := true
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-LessonForge-Actor-User-Id") != "101" {
			actorHeaderValid = false
		}
		routes = append(routes, r.Method+" "+r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		writeEnvelope := func(data any) {
			_ = json.NewEncoder(w).Encode(map[string]any{"code": 0, "message": "ok", "data": data})
		}
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/projects":
			writeEnvelope([]JavaProject{})
		case r.Method == http.MethodPost && r.URL.Path == "/api/projects":
			writeEnvelope(JavaProject{ID: 44, ProjectName: "LessonForge Mission 7", Description: "lessonforge:mission:7"})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/internal/lessonforge/projects/44/materials":
			if err := r.ParseMultipartForm(1 << 20); err != nil {
				t.Fatalf("parse multipart: %v", err)
			}
			file, header, err := r.FormFile("file")
			if err != nil {
				t.Fatalf("form file: %v", err)
			}
			defer file.Close()
			body, err := io.ReadAll(file)
			if err != nil {
				t.Fatalf("read upload: %v", err)
			}
			if string(body) != string(content) || header.Filename != "textbook.pdf" || r.FormValue("description") != "lessonforge:mission-file:9:sha-material" {
				t.Fatalf("unexpected upload filename/body/marker: %q %q %q", header.Filename, body, r.FormValue("description"))
			}
			if r.FormValue("missionId") != "7" || r.FormValue("missionFileId") != "9" || r.FormValue("ownerUserId") != "101" || r.FormValue("actorUserId") != "101" || r.FormValue("sourceSha256") != "sha-material" || r.FormValue("sourceSize") != strconv.Itoa(len(content)) {
				t.Fatalf("unexpected LessonForge identity fields: mission=%q file=%q owner=%q actor=%q sha=%q size=%q", r.FormValue("missionId"), r.FormValue("missionFileId"), r.FormValue("ownerUserId"), r.FormValue("actorUserId"), r.FormValue("sourceSha256"), r.FormValue("sourceSize"))
			}
			writeEnvelope(map[string]any{"bindingId": 71, "missionId": 7, "missionFileId": 9, "projectId": 44, "materialId": 55, "sourceSha256": "sha-material", "sourceSize": len(content), "originalFilename": "textbook.pdf", "bindingStatus": "BOUND"})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/internal/lessonforge/projects/44/materials/55/parse":
			writeEnvelope(map[string]any{})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/internal/lessonforge/projects/44/materials/55/index":
			writeEnvelope(map[string]any{})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/internal/lessonforge/projects/44/knowledge/search":
			if err := json.NewDecoder(r.Body).Decode(&searchBody); err != nil {
				t.Fatalf("decode search body: %v", err)
			}
			writeEnvelope(javaSearchResponse{Hits: []javaKnowledgeHit{{ChunkID: 3, MaterialID: 55, Source: "textbook.pdf", Title: "TCP", Content: "A SYN is sent first.", Score: 0.91}}})
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client := NewJavaClient(server.URL, time.Second, 1<<20)
	client.ConfigureResources(store, fakeResourceStorage{path: filePath})
	got, err := client.Search(context.Background(), missionID, "TCP handshake", []int64{9})
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].Content != "A SYN is sent first." || got[0].Source != "textbook.pdf" {
		t.Fatalf("search result = %#v", got)
	}
	if searchBody["query"] != "TCP handshake" || int(searchBody["limit"].(float64)) != 10 || int(searchBody["missionId"].(float64)) != 7 {
		t.Fatalf("search body = %#v", searchBody)
	}
	if store.projectID != 44 || store.projectSaves != 1 || store.material.RAGMaterialID != 55 || store.materialSaves != 1 {
		t.Fatalf("mapping state = %#v", store)
	}
	if strings.Contains(strings.Join(routes, "\n"), "read") {
		t.Fatal("search path unexpectedly called a read-material endpoint")
	}
	if !actorHeaderValid {
		t.Fatal("Java RAG request did not carry the owner actor header")
	}
}

func TestJavaReadUsesAuthorizedMissionFileAndReturnedMaterialID(t *testing.T) {
	const missionID int64 = 7
	const ownerID int64 = 101
	const sourceSize int64 = 10
	sourceSHA := strings.Repeat("a", 64)
	store := &fakeResourceStore{
		mission:        model.Mission{ID: missionID, OwnerTeacherID: ownerID, Title: "TCP lesson"},
		projectID:      44,
		files:          []model.MissionFile{{ID: 9, MissionID: missionID, OwnerUserID: ownerID, Role: "MATERIAL", Provenance: "TEACHER", ParseStatus: "READY", FileObject: model.FileObject{ID: 19, OriginalName: "textbook.pdf", Size: sourceSize, SHA256: sourceSHA, StorageKey: "uploads/19"}}},
		materialByFile: map[int64]model.RAGMaterialBinding{9: {MissionID: missionID, MissionFileID: 9, RAGProjectID: 44, RAGMaterialID: 55, SourceSHA256: sourceSHA}},
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-LessonForge-Actor-User-Id") != "101" {
			t.Fatalf("actor header = %q", r.Header.Get("X-LessonForge-Actor-User-Id"))
		}
		if r.Method != http.MethodGet || r.URL.Path != "/api/v1/internal/lessonforge/projects/44/knowledge/missions/7/materials/55/read" || r.URL.Query().Get("locator") != "chunk:2" {
			t.Fatalf("unexpected read request: %s %s", r.Method, r.URL.String())
		}
		query := r.URL.Query()
		if query.Get("missionId") != "7" || query.Get("missionFileId") != "9" || query.Get("ownerUserId") != "101" || query.Get("actorUserId") != "101" || query.Get("sourceSha256") != sourceSHA || query.Get("sourceSize") != "10" {
			t.Fatalf("read identity query = %v", query)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"code": 0, "message": "ok", "data": map[string]any{"content": "A SYN is sent first."}})
	}))
	defer server.Close()

	client := NewJavaClient(server.URL, time.Second, 1024)
	client.ConfigureResources(store, fakeResourceStorage{})
	content, err := client.Read(context.Background(), missionID, 19, "chunk:2")
	if err != nil || content != "A SYN is sent first." {
		t.Fatalf("read content=%q err=%v", content, err)
	}
	if _, err := client.Read(context.Background(), missionID, 999, "chunk:2"); err == nil {
		t.Fatal("unauthorized material unexpectedly read")
	}
}

func TestJavaSearchDropsHitsOutsideAuthorizedMissionMaterialScope(t *testing.T) {
	const missionID int64 = 7
	const ownerID int64 = 101
	store := &fakeResourceStore{
		mission:   model.Mission{ID: missionID, OwnerTeacherID: ownerID, Title: "TCP lesson"},
		projectID: 44,
		files: []model.MissionFile{
			{ID: 9, MissionID: missionID, OwnerUserID: ownerID, Role: "MATERIAL", Provenance: "TEACHER", ParseStatus: "READY", FileObject: model.FileObject{ID: 19, OriginalName: "textbook.pdf", Size: 10, SHA256: "sha-19", StorageKey: "uploads/19"}},
			{ID: 10, MissionID: missionID, OwnerUserID: ownerID, Role: "MATERIAL", Provenance: "TEACHER", ParseStatus: "READY", FileObject: model.FileObject{ID: 20, OriginalName: "other.pdf", Size: 10, SHA256: "sha-20", StorageKey: "uploads/20"}},
		},
		materialByFile: map[int64]model.RAGMaterialBinding{
			9:  {MissionID: missionID, MissionFileID: 9, RAGProjectID: 44, RAGMaterialID: 55, SourceSHA256: "sha-19"},
			10: {MissionID: missionID, MissionFileID: 10, RAGProjectID: 44, RAGMaterialID: 56, SourceSHA256: "sha-20"},
		},
	}
	searchCalls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && r.URL.Path == "/api/v1/internal/lessonforge/projects/44/knowledge/search" {
			searchCalls++
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"code": 0, "message": "ok", "data": javaSearchResponse{Hits: []javaKnowledgeHit{
				{ChunkID: 1, MaterialID: 55, Source: "textbook.pdf", Title: "allowed", Content: "allowed", Score: 0.9},
				{ChunkID: 2, MaterialID: 56, Source: "other.pdf", Title: "foreign", Content: "foreign", Score: 0.99},
				{ChunkID: 3, MaterialID: 0, Source: "unknown", Title: "unknown", Content: "unknown", Score: 1},
			}}})
			return
		}
		http.NotFound(w, r)
	}))
	defer server.Close()

	client := NewJavaClient(server.URL, time.Second, 1<<20)
	client.ConfigureResources(store, fakeResourceStorage{})
	got, err := client.Search(context.Background(), missionID, "TCP", []int64{19})
	if err != nil {
		t.Fatal(err)
	}
	if searchCalls != 1 {
		t.Fatalf("knowledge search calls = %d", searchCalls)
	}
	if len(got) != 1 || got[0].Content != "allowed" {
		t.Fatalf("scoped search result = %#v", got)
	}
}

func TestJavaSearchDoesNotQueryProjectWhenMissionHasNoReadyMaterial(t *testing.T) {
	const missionID int64 = 7
	store := &fakeResourceStore{
		mission:   model.Mission{ID: missionID, OwnerTeacherID: 101, Title: "TCP lesson"},
		projectID: 44,
		files:     []model.MissionFile{{ID: 9, MissionID: missionID, Role: "MATERIAL", Provenance: "TEACHER", ParseStatus: "PENDING", FileObject: model.FileObject{ID: 19, OriginalName: "textbook.pdf", Size: 10, SHA256: "sha-19"}}},
	}
	searchCalls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/internal/lessonforge/projects/44/knowledge/search" {
			searchCalls++
		}
		http.NotFound(w, r)
	}))
	defer server.Close()

	client := NewJavaClient(server.URL, time.Second, 1<<20)
	client.ConfigureResources(store, fakeResourceStorage{})
	got, err := client.Search(context.Background(), missionID, "TCP", nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 0 || searchCalls != 0 {
		t.Fatalf("empty ready scope result=%#v searchCalls=%d", got, searchCalls)
	}
}

func TestJavaClientInjectsExplicitServiceBearerToken(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		if got := r.Header.Get("Authorization"); got != "Bearer service-token" {
			t.Fatalf("Authorization = %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"code": 0, "message": "ok", "data": []JavaProject{}})
	}))
	defer server.Close()

	client := NewJavaClient(server.URL, time.Second, 1<<20)
	client.SetServiceBearerToken(" service-token ")
	if _, err := client.ListProjects(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !called {
		t.Fatal("Java request was not sent")
	}
}

func TestJavaClientTimeoutIsClassified(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(100 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	client := NewJavaClient(server.URL, 10*time.Millisecond, 1<<20)
	if _, err := client.ListProjects(context.Background()); err == nil || err.Error() != "RAG_TIMEOUT" {
		t.Fatalf("Java RAG timeout error = %v, want RAG_TIMEOUT", err)
	}
}

func TestJavaClientClassifiesConfirmedRequirementSummaryConflict(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"code":    409,
			"message": "A confirmed requirement summary is required before material upload",
		})
	}))
	defer server.Close()

	client := NewJavaClient(server.URL, time.Second, 1<<20)
	if _, err := client.ListProjects(context.Background()); !errors.Is(err, ErrRequirementSummaryRequired) {
		t.Fatalf("ListProjects error = %v, want ErrRequirementSummaryRequired", err)
	}
	if _, err := client.UploadMaterial(context.Background(), 44, "lesson.pdf", "application/pdf", "", 3, bytes.NewReader([]byte("pdf"))); !errors.Is(err, ErrRequirementSummaryRequired) {
		t.Fatalf("UploadMaterial error = %v, want ErrRequirementSummaryRequired", err)
	}
}
