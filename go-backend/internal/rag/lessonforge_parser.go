package rag

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/google/uuid"
	"lessonforge.local/backend/internal/model"
)

// LessonForgeParserAdapter is the production parser adapter for a
// Mission-first deployment. The Go service remains authoritative for Mission,
// MissionFile, owner and source identity; Java owns the Parser/Material/Index
// pipeline behind its explicit internal contract.
//
// It intentionally has the same method set as parser.Adapter without
// importing the parser package, keeping the dependency direction acyclic.
type LessonForgeParserAdapter struct {
	Client           *Client
	Store            ResourceStore
	Models           *model.Client
	Embedding        EmbeddingConnectionStore
	Crypto           CredentialDecryptor
	RequireEmbedding bool
}

func NewLessonForgeParserAdapter(client *Client, store ResourceStore) *LessonForgeParserAdapter {
	return &LessonForgeParserAdapter{Client: client, Store: store}
}

func (a *LessonForgeParserAdapter) ConfigureEmbedding(store EmbeddingConnectionStore, models *model.Client, crypt CredentialDecryptor, required bool) {
	a.Embedding = store
	a.Models = models
	a.Crypto = crypt
	a.RequireEmbedding = required
}

func (a *LessonForgeParserAdapter) Configured() bool {
	return a != nil && a.Client != nil && a.Client.baseURL != "" && a.Store != nil
}

func (a *LessonForgeParserAdapter) Parse(ctx context.Context, file model.MissionFile, content io.Reader) (model.ParseResult, error) {
	if !a.Configured() {
		return model.ParseResult{}, ErrNotConfigured
	}
	if file.ID <= 0 || file.MissionID <= 0 || file.OwnerUserID <= 0 || file.FileObject.ID <= 0 || file.FileObject.Size <= 0 || strings.TrimSpace(file.FileObject.SHA256) == "" || content == nil {
		return model.ParseResult{}, errors.New("RAG_LESSONFORGE_PARSE_INPUT_INVALID")
	}

	owner, err := a.Store.MissionOwner(ctx, file.MissionID)
	if err != nil {
		return model.ParseResult{}, err
	}
	if owner != file.OwnerUserID {
		return model.ParseResult{}, errors.New("RAG_MISSION_FILE_OWNER_MISMATCH")
	}
	mission, err := a.Store.GetMission(ctx, owner, file.MissionID)
	if err != nil {
		return model.ParseResult{}, err
	}
	if mission.ID != file.MissionID || mission.OwnerTeacherID != owner {
		return model.ParseResult{}, errors.New("RAG_MISSION_IDENTITY_MISMATCH")
	}
	missionFiles, err := a.Store.MissionFiles(ctx, owner, file.MissionID)
	if err != nil {
		return model.ParseResult{}, err
	}
	if !containsAuthorizedMissionFile(missionFiles, file) {
		return model.ParseResult{}, errors.New("RAG_MISSION_FILE_NOT_AUTHORIZED")
	}

	ctx = withActorUserID(ctx, owner)
	binder := &MissionResourceBinder{Client: a.Client, Store: a.Store}
	projectID, err := binder.ensureProject(ctx, owner, file.MissionID)
	if err != nil {
		return model.ParseResult{}, err
	}
	material, err := a.Client.UploadLessonForgeMaterial(ctx, projectID, file.MissionID, file.ID, owner, owner, file.FileObject.OriginalName, file.FileObject.MimeType, "lessonforge:mission-file:"+fmt.Sprint(file.ID)+":"+file.FileObject.SHA256, file.FileObject.Size, file.FileObject.SHA256, content)
	if err != nil {
		return model.ParseResult{}, err
	}
	identity := MaterialIdentity{
		MissionID:     file.MissionID,
		MissionFileID: file.ID,
		OwnerUserID:   owner,
		ActorUserID:   owner,
		SourceSHA256:  file.FileObject.SHA256,
		SourceSize:    file.FileObject.Size,
	}
	parsed, err := a.Client.ParseMaterialResult(ctx, projectID, material.ID, identity)
	if err != nil {
		return model.ParseResult{}, err
	}
	var chunks []IndexedChunk
	if a.Embedding != nil || a.RequireEmbedding {
		chunks, err = a.Client.IndexMaterialWithChunks(ctx, projectID, material.ID, identity)
		if err != nil {
			return model.ParseResult{}, err
		}
		if err := a.embedAndPersist(ctx, owner, file.MissionID, projectID, material.ID, identity, chunks); err != nil {
			return model.ParseResult{}, err
		}
	} else if err := a.Client.IndexMaterial(ctx, projectID, material.ID, identity); err != nil {
		return model.ParseResult{}, err
	}
	if err := a.Store.SaveRAGMaterialBinding(ctx, owner, file.MissionID, file.ID, projectID, material.ID, file.FileObject.SHA256); err != nil {
		if isPermanentRAGBindingError(err) {
			return model.ParseResult{}, err
		}
		return model.ParseResult{}, fmt.Errorf("%w: %v", model.ErrRetryableRAGBinding, err)
	}
	return parsed, nil
}

func (a *LessonForgeParserAdapter) embedAndPersist(ctx context.Context, owner, missionID, projectID, materialID int64, identity MaterialIdentity, chunks []IndexedChunk) error {
	if a.Embedding == nil || a.Models == nil || a.Crypto == nil {
		return errors.New("RAG_EMBEDDING_NOT_CONFIGURED")
	}
	connection, encrypted, err := a.Embedding.ResolveEmbeddingConnection(ctx, owner)
	if err != nil {
		return fmt.Errorf("RAG_EMBEDDING_CONNECTION_UNAVAILABLE: %w", err)
	}
	if !connection.Capabilities.SupportsEmbeddings || connection.Capabilities.EmbeddingDimension <= 0 || connection.CapabilityVerification.SupportsEmbeddings != model.CapabilityVerified {
		return errors.New("RAG_EMBEDDING_CONNECTION_NOT_VERIFIED")
	}
	apiKey, err := a.Crypto.Decrypt(encrypted)
	if err != nil {
		return errors.New("RAG_EMBEDDING_CREDENTIAL_UNAVAILABLE")
	}
	resolved := model.ResolvedConnection{ID: connection.ID, OwnerUserID: owner, Provider: connection.Provider, Protocol: connection.Protocol, BaseURL: connection.BaseURL, ModelID: connection.ModelID, Capabilities: connection.Capabilities, CapabilityVerification: connection.CapabilityVerification, APIKey: apiKey}
	audit, _ := a.Store.(EmbeddingAuditStore)
	embeddings := make([]ChunkEmbedding, 0, len(chunks))
	for start := 0; start < len(chunks); start += 32 {
		end := start + 32
		if end > len(chunks) {
			end = len(chunks)
		}
		inputs := make([]string, 0, end-start)
		for _, chunk := range chunks[start:end] {
			if strings.TrimSpace(chunk.Content) == "" {
				return errors.New("RAG_EMBEDDING_INPUT_EMPTY")
			}
			inputs = append(inputs, chunk.Content)
		}
		started := time.Now()
		vectors, status, embedErr := a.Models.EmbedBatch(ctx, resolved, inputs)
		if audit != nil {
			_ = audit.RecordModelAudit(ctx, uuid.New(), owner, missionID, connection.ID, connection, "RAG_EMBEDDING_INDEX", status, time.Since(started))
		}
		if embedErr != nil {
			return fmt.Errorf("RAG_EMBEDDING_REQUEST_FAILED: %w", embedErr)
		}
		if len(vectors) != len(inputs) {
			return errors.New("RAG_EMBEDDING_COUNT_MISMATCH")
		}
		for offset, vector := range vectors {
			if len(vector) != connection.Capabilities.EmbeddingDimension {
				return errors.New("RAG_EMBEDDING_DIMENSION_MISMATCH")
			}
			embeddings = append(embeddings, ChunkEmbedding{ChunkID: chunks[start+offset].ChunkID, Vector: vector})
		}
	}
	if audit != nil {
		if err := audit.MarkConnectionUsed(ctx, owner, connection.ID); err != nil {
			return fmt.Errorf("RAG_EMBEDDING_USAGE_AUDIT_FAILED: %w", err)
		}
	}
	_, err = a.Client.PersistMaterialEmbeddings(ctx, projectID, materialID, EmbeddingIdentity{MaterialIdentity: identity, ModelConnectionID: connection.ID, ModelID: connection.ModelID, Dimension: connection.Capabilities.EmbeddingDimension}, embeddings)
	return err
}

func isPermanentRAGBindingError(err error) bool {
	if err == nil {
		return false
	}
	code := strings.ToUpper(strings.TrimSpace(strings.SplitN(err.Error(), ":", 2)[0]))
	switch code {
	case "RAG_MATERIAL_BINDING_INVALID", "RAG_MATERIAL_BINDING_CONFLICT", "RAG_MISSION_FILE_OWNER_MISMATCH", "RAG_MISSION_IDENTITY_MISMATCH", "RAG_MISSION_FILE_NOT_AUTHORIZED":
		return true
	default:
		return false
	}
}

func containsAuthorizedMissionFile(files []model.MissionFile, expected model.MissionFile) bool {
	for _, candidate := range files {
		if candidate.ID == expected.ID &&
			candidate.MissionID == expected.MissionID &&
			candidate.OwnerUserID == expected.OwnerUserID &&
			candidate.FileObject.ID == expected.FileObject.ID &&
			candidate.FileObject.Size == expected.FileObject.Size &&
			strings.EqualFold(candidate.FileObject.SHA256, expected.FileObject.SHA256) &&
			candidate.FileObject.StorageKey == expected.FileObject.StorageKey {
			return true
		}
	}
	return false
}
