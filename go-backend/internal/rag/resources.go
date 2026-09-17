package rag

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"lessonforge.local/backend/internal/model"
)

var ErrResourceMappingNotConfigured = errors.New("RAG_RESOURCE_MAPPING_NOT_CONFIGURED")

type ResourceStore interface {
	MissionOwner(context.Context, int64) (int64, error)
	GetMission(context.Context, int64, int64) (model.Mission, error)
	MissionFiles(context.Context, int64, int64) ([]model.MissionFile, error)
	RAGProjectBinding(context.Context, int64, int64) (int64, bool, error)
	SaveRAGProjectBinding(context.Context, int64, int64, int64) error
	RAGMaterialBinding(context.Context, int64, int64) (model.RAGMaterialBinding, bool, error)
	SaveRAGMaterialBinding(context.Context, int64, int64, int64, int64, int64, string) error
}

type ResourceStorage interface {
	Open(context.Context, string) (*os.File, error)
}

type EmbeddingConnectionStore interface {
	ResolveEmbeddingConnection(context.Context, int64) (model.ModelConnection, string, error)
}

type EmbeddingAuditStore interface {
	RecordModelAudit(context.Context, uuid.UUID, int64, int64, int64, model.ModelConnection, string, int, time.Duration) error
	MarkConnectionUsed(context.Context, int64, int64) error
}

// MissionResourceBinder owns only the cross-system identity mapping. It does
// not infer Java IDs from Mission/File IDs and only records IDs returned by
// the Java API.
type MissionResourceBinder struct {
	Client    *Client
	Store     ResourceStore
	Storage   ResourceStorage
	Models    *model.Client
	Crypto    CredentialDecryptor
	Embedding EmbeddingConnectionStore
}

type CredentialDecryptor interface {
	Decrypt(string) (string, error)
}

func (b *MissionResourceBinder) Search(ctx context.Context, missionID int64, query string, fileIDs []int64) ([]Snippet, error) {
	if b == nil || b.Client == nil || b.Store == nil || b.Storage == nil {
		return nil, ErrResourceMappingNotConfigured
	}
	owner, err := b.Store.MissionOwner(ctx, missionID)
	if err != nil {
		return nil, err
	}
	ctx = withActorUserID(ctx, owner)
	files, err := b.Store.MissionFiles(ctx, owner, missionID)
	if err != nil {
		return nil, err
	}
	allowed := make(map[int64]struct{}, len(fileIDs))
	for _, id := range fileIDs {
		allowed[id] = struct{}{}
	}
	eligibleFiles := make([]model.MissionFile, 0, len(files))
	allowedRAGMaterials := make(map[int64]struct{})
	for _, file := range files {
		if file.ParseStatus != "READY" || (file.Role != "MATERIAL" && file.Role != "TEACHING_PLAN") || (file.Provenance != "TEACHER" && file.Provenance != "MATERIAL") {
			continue
		}
		if len(allowed) > 0 {
			// Runtime addresses a MissionFile, while some legacy callers and
			// stored bindings still carry the underlying FileObject ID. Accept
			// either identity only after the owner-scoped MissionFiles query
			// above has established their relationship.
			if _, missionFileAllowed := allowed[file.ID]; !missionFileAllowed {
				if _, fileObjectAllowed := allowed[file.FileObject.ID]; !fileObjectAllowed {
					continue
				}
			}
		}
		eligibleFiles = append(eligibleFiles, file)
	}
	// Do not create an external Java project for a Mission that has no
	// searchable material yet. This keeps a not-ready Mission side-effect free
	// and avoids leaving an unusable cross-system resource behind.
	if len(eligibleFiles) == 0 {
		return []Snippet{}, nil
	}
	projectID, err := b.ensureProject(ctx, owner, missionID)
	if err != nil {
		return nil, err
	}
	for _, file := range eligibleFiles {
		ragMaterialID, err := b.ensureMaterial(ctx, owner, projectID, file)
		if err != nil {
			return nil, err
		}
		allowedRAGMaterials[ragMaterialID] = struct{}{}
	}
	// Java's endpoint is project-scoped and has no material filter. Never pass
	// its complete response to the Agent without proving that every hit belongs
	// to a READY, owner-authorized MissionFile selected above.
	if len(allowedRAGMaterials) == 0 {
		return []Snippet{}, nil
	}
	var queryVector []float64
	var embeddingFallbackReason string
	if b.Embedding != nil || b.Models != nil || b.Crypto != nil {
		queryVector, embeddingFallbackReason = b.embedQuery(ctx, owner, missionID, strings.TrimSpace(query))
	}
	return b.Client.searchJava(ctx, projectID, missionID, strings.TrimSpace(query), allowedRAGMaterials, queryVector, embeddingFallbackReason)
}

func (b *MissionResourceBinder) embedQuery(ctx context.Context, owner, missionID int64, query string) ([]float64, string) {
	if b.Embedding == nil || b.Models == nil || b.Crypto == nil {
		return nil, "EMBEDDING_NOT_CONFIGURED"
	}
	connection, encrypted, err := b.Embedding.ResolveEmbeddingConnection(ctx, owner)
	if err != nil {
		return nil, safeEmbeddingError(err)
	}
	apiKey, err := b.Crypto.Decrypt(encrypted)
	if err != nil {
		return nil, "EMBEDDING_CREDENTIAL_UNAVAILABLE"
	}
	resolved := model.ResolvedConnection{ID: connection.ID, OwnerUserID: owner, Provider: connection.Provider, Protocol: connection.Protocol, BaseURL: connection.BaseURL, ModelID: connection.ModelID, Capabilities: connection.Capabilities, CapabilityVerification: connection.CapabilityVerification, APIKey: apiKey}
	started := time.Now()
	vectors, status, embedErr := b.Models.EmbedBatch(ctx, resolved, []string{query})
	if audit, ok := b.Store.(EmbeddingAuditStore); ok {
		_ = audit.RecordModelAudit(ctx, uuid.New(), owner, missionID, connection.ID, connection, "RAG_EMBEDDING_QUERY", status, time.Since(started))
		if embedErr == nil {
			_ = audit.MarkConnectionUsed(ctx, owner, connection.ID)
		}
	}
	if embedErr != nil {
		return nil, safeEmbeddingError(embedErr)
	}
	if len(vectors) != 1 || len(vectors[0]) == 0 || connection.Capabilities.EmbeddingDimension != len(vectors[0]) {
		return nil, "EMBEDDING_DIMENSION_MISMATCH"
	}
	return vectors[0], ""
}

func safeEmbeddingError(err error) string {
	if err == nil {
		return ""
	}
	if httpErr, ok := err.(*model.HTTPError); ok {
		return fmt.Sprintf("EMBEDDING_HTTP_%d", httpErr.Status)
	}
	code := strings.TrimSpace(strings.SplitN(err.Error(), ":", 2)[0])
	if code == "" || len(code) > 80 {
		return "EMBEDDING_FAILED"
	}
	return code
}

func (b *MissionResourceBinder) Read(ctx context.Context, missionID, fileID int64, locator string) (string, error) {
	if b == nil || b.Client == nil || b.Store == nil || b.Storage == nil {
		return "", ErrResourceMappingNotConfigured
	}
	if fileID <= 0 {
		return "", errors.New("RAG_FILE_ID_INVALID")
	}
	owner, err := b.Store.MissionOwner(ctx, missionID)
	if err != nil {
		return "", err
	}
	ctx = withActorUserID(ctx, owner)
	files, err := b.Store.MissionFiles(ctx, owner, missionID)
	if err != nil {
		return "", err
	}
	var target *model.MissionFile
	for index := range files {
		file := &files[index]
		if (file.ID == fileID || file.FileObject.ID == fileID) && file.ParseStatus == "READY" &&
			(file.Role == "MATERIAL" || file.Role == "TEACHING_PLAN") &&
			(file.Provenance == "TEACHER" || file.Provenance == "MATERIAL") {
			target = file
			break
		}
	}
	if target == nil {
		return "", errors.New("RAG_FILE_NOT_AUTHORIZED")
	}
	projectID, err := b.ensureProject(ctx, owner, missionID)
	if err != nil {
		return "", err
	}
	materialID, err := b.ensureMaterial(ctx, owner, projectID, *target)
	if err != nil {
		return "", err
	}
	return b.Client.readJava(ctx, projectID, missionID, materialID, locator, MaterialIdentity{
		MissionID:     target.MissionID,
		MissionFileID: target.ID,
		OwnerUserID:   owner,
		ActorUserID:   owner,
		SourceSHA256:  target.FileObject.SHA256,
		SourceSize:    target.FileObject.Size,
	})
}

func (b *MissionResourceBinder) ensureProject(ctx context.Context, owner, missionID int64) (int64, error) {
	if id, found, err := b.Store.RAGProjectBinding(ctx, owner, missionID); err != nil {
		return 0, err
	} else if found && id > 0 {
		return id, nil
	}
	mission, err := b.Store.GetMission(ctx, owner, missionID)
	if err != nil {
		return 0, err
	}
	marker := "lessonforge:mission:" + strconv.FormatInt(missionID, 10)
	projects, err := b.Client.ListProjects(ctx)
	if err != nil {
		return 0, err
	}
	for _, project := range projects {
		if strings.TrimSpace(project.Description) == marker && project.ID > 0 {
			if saveErr := b.Store.SaveRAGProjectBinding(ctx, owner, missionID, project.ID); saveErr != nil {
				if id, found, readErr := b.Store.RAGProjectBinding(ctx, owner, missionID); readErr == nil && found {
					return id, nil
				}
				return 0, saveErr
			}
			return project.ID, nil
		}
	}
	project, err := b.Client.CreateProject(ctx, "LessonForge Mission "+strconv.FormatInt(missionID, 10), "LessonForge", mission.Title, marker)
	if err != nil {
		return 0, err
	}
	if project.ID <= 0 {
		return 0, errors.New("RAG_PROJECT_RESPONSE_INVALID")
	}
	if err := b.Store.SaveRAGProjectBinding(ctx, owner, missionID, project.ID); err != nil {
		if id, found, readErr := b.Store.RAGProjectBinding(ctx, owner, missionID); readErr == nil && found {
			return id, nil
		}
		return 0, err
	}
	return project.ID, nil
}

func (b *MissionResourceBinder) ensureMaterial(ctx context.Context, owner, projectID int64, file model.MissionFile) (int64, error) {
	if binding, found, err := b.Store.RAGMaterialBinding(ctx, owner, file.ID); err != nil {
		return 0, err
	} else if found {
		if binding.RAGProjectID != projectID || binding.SourceSHA256 != file.FileObject.SHA256 {
			return 0, errors.New("RAG_MATERIAL_BINDING_SOURCE_CONFLICT")
		}
		if binding.RAGMaterialID <= 0 {
			return 0, errors.New("RAG_MATERIAL_BINDING_INVALID")
		}
		return binding.RAGMaterialID, nil
	}
	marker := "lessonforge:mission-file:" + strconv.FormatInt(file.ID, 10) + ":" + file.FileObject.SHA256
	handle, err := b.Storage.Open(ctx, file.FileObject.StorageKey)
	if err != nil {
		return 0, fmt.Errorf("RAG_SOURCE_FILE_UNAVAILABLE: %w", err)
	}
	material, err := b.Client.UploadLessonForgeMaterial(ctx, projectID, file.MissionID, file.ID, owner, owner, file.FileObject.OriginalName, file.FileObject.MimeType, marker, file.FileObject.Size, file.FileObject.SHA256, handle)
	closeErr := handle.Close()
	if err != nil {
		return 0, err
	}
	if closeErr != nil {
		return 0, fmt.Errorf("RAG_SOURCE_FILE_CLOSE_FAILED: %w", closeErr)
	}
	if material.ID <= 0 {
		return 0, errors.New("RAG_MATERIAL_RESPONSE_INVALID")
	}
	identity := MaterialIdentity{
		MissionID:     file.MissionID,
		MissionFileID: file.ID,
		OwnerUserID:   owner,
		ActorUserID:   owner,
		SourceSHA256:  file.FileObject.SHA256,
		SourceSize:    file.FileObject.Size,
	}
	if err := b.Client.ParseMaterial(ctx, projectID, material.ID, identity); err != nil {
		return 0, err
	}
	if err := b.Client.IndexMaterial(ctx, projectID, material.ID, identity); err != nil {
		return 0, err
	}
	if err := b.Store.SaveRAGMaterialBinding(ctx, owner, file.MissionID, file.ID, projectID, material.ID, file.FileObject.SHA256); err != nil {
		return 0, err
	}
	return material.ID, nil
}
