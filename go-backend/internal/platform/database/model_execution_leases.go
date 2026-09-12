package database

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"lessonforge.local/backend/internal/model"
)

// ModelExecutionLeaseInput is the non-secret execution manifest supplied by
// the Java analysis service. Go verifies the referenced Mission file and
// preview bytes before issuing a one-use lease.
type ModelExecutionLeaseInput struct {
	OwnerUserID        int64
	MissionID          int64
	MissionFileID      int64
	Purpose            string
	AnalysisRunID      string
	SourceVersionID    int64
	RenderedSlideSetID int64
	ProcessingRunID    int64
	SourceSHA256       string
	InputSHA256        string
	PromptSHA256       string
	PreviewStorageKey  string
	PreviewSHA256      string
	PreviewSizeBytes   int64
	PreviewMediaType   string
	Nonce              string
	ModelConnectionID  int64
}

type ModelExecutionLease struct {
	ID                 uuid.UUID
	OwnerUserID        int64
	MissionID          int64
	ModelConnectionID  int64
	MissionFileID      int64
	Purpose            string
	AnalysisRunID      string
	SourceVersionID    int64
	RenderedSlideSetID int64
	ProcessingRunID    int64
	SourceSHA256       string
	InputSHA256        string
	PromptSHA256       string
	PreviewStorageKey  string
	PreviewSHA256      string
	PreviewSizeBytes   int64
	PreviewMediaType   string
	NonceHash          string
	ExpiresAt          time.Time
	ConsumedAt         *time.Time
}

var (
	ErrModelExecutionLeaseInvalid        = errors.New("MODEL_EXECUTION_LEASE_INVALID")
	ErrModelExecutionIdempotencyConflict = errors.New("MODEL_EXECUTION_IDEMPOTENCY_CONFLICT")
	ErrModelExecutionLeaseNotReusable    = errors.New("MODEL_EXECUTION_LEASE_NOT_REUSABLE")
)

func nonceHash(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func (s *Store) CreateModelExecutionLease(ctx context.Context, input ModelExecutionLeaseInput, expiresAt time.Time) (ModelExecutionLease, bool, error) {
	var lease ModelExecutionLease
	hash := nonceHash(input.Nonce)
	row := s.DB.QueryRow(ctx, `
		INSERT INTO model_execution_leases(
			id,owner_user_id,mission_id,model_connection_id,mission_file_id,purpose,analysis_run_id,
			source_version_id,rendered_slide_set_id,processing_run_id,source_sha256,input_sha256,
			prompt_sha256,preview_storage_key,preview_sha256,preview_size_bytes,preview_media_type,
			nonce_hash,expires_at
		) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19)
		ON CONFLICT (mission_id,purpose,analysis_run_id) DO NOTHING
		RETURNING id,owner_user_id,mission_id,model_connection_id,mission_file_id,purpose,analysis_run_id,
		          source_version_id,rendered_slide_set_id,processing_run_id,source_sha256,input_sha256,
		          prompt_sha256,preview_storage_key,preview_sha256,preview_size_bytes,preview_media_type,
		          nonce_hash,expires_at,consumed_at`,
		uuid.New(), input.OwnerUserID, input.MissionID, input.ModelConnectionID, input.MissionFileID,
		input.Purpose, input.AnalysisRunID, input.SourceVersionID, input.RenderedSlideSetID,
		input.ProcessingRunID, strings.ToLower(input.SourceSHA256), strings.ToLower(input.InputSHA256),
		strings.ToLower(input.PromptSHA256), input.PreviewStorageKey, strings.ToLower(input.PreviewSHA256),
		input.PreviewSizeBytes, input.PreviewMediaType, hash, expiresAt,
	)
	if err := scanModelExecutionLease(row, &lease); err == nil {
		return lease, true, nil
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return ModelExecutionLease{}, false, err
	}

	if err := scanModelExecutionLease(s.DB.QueryRow(ctx, `
		SELECT id,owner_user_id,mission_id,model_connection_id,mission_file_id,purpose,analysis_run_id,
		       source_version_id,rendered_slide_set_id,processing_run_id,source_sha256,input_sha256,
		       prompt_sha256,preview_storage_key,preview_sha256,preview_size_bytes,preview_media_type,
		       nonce_hash,expires_at,consumed_at
		FROM model_execution_leases
		WHERE mission_id=$1 AND purpose=$2 AND analysis_run_id=$3`, input.MissionID, input.Purpose, input.AnalysisRunID), &lease); err != nil {
		return ModelExecutionLease{}, false, err
	}
	if !sameModelExecutionLease(input, lease, hash) {
		return ModelExecutionLease{}, false, ErrModelExecutionIdempotencyConflict
	}
	if lease.ConsumedAt != nil || !lease.ExpiresAt.After(time.Now().UTC()) {
		return ModelExecutionLease{}, false, ErrModelExecutionLeaseNotReusable
	}
	return lease, false, nil
}

func (s *Store) ConsumeModelExecutionLease(ctx context.Context, id uuid.UUID, nonce string) (ModelExecutionLease, error) {
	var lease ModelExecutionLease
	err := scanModelExecutionLease(s.DB.QueryRow(ctx, `
		UPDATE model_execution_leases
		SET consumed_at=now()
		WHERE id=$1 AND nonce_hash=$2 AND consumed_at IS NULL AND expires_at>now()
		RETURNING id,owner_user_id,mission_id,model_connection_id,mission_file_id,purpose,analysis_run_id,
		          source_version_id,rendered_slide_set_id,processing_run_id,source_sha256,input_sha256,
		          prompt_sha256,preview_storage_key,preview_sha256,preview_size_bytes,preview_media_type,
		          nonce_hash,expires_at,consumed_at`, id, nonceHash(nonce)), &lease)
	if errors.Is(err, pgx.ErrNoRows) {
		return ModelExecutionLease{}, ErrModelExecutionLeaseInvalid
	}
	if err != nil {
		return ModelExecutionLease{}, err
	}
	return lease, nil
}

// CleanupExpiredModelExecutionLeases removes only leases that are outside the
// replay/idempotency retention window. Keeping recent consumed or expired rows
// lets the service reject a replay deterministically instead of treating it as
// a new capability request.
func (s *Store) CleanupExpiredModelExecutionLeases(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `
		DELETE FROM model_execution_leases
		WHERE expires_at < now() - interval '1 day'
		   OR (consumed_at IS NOT NULL AND consumed_at < now() - interval '1 day')`)
	return err
}

func sameModelExecutionLease(input ModelExecutionLeaseInput, lease ModelExecutionLease, hash string) bool {
	return (lease.OwnerUserID == input.OwnerUserID && lease.MissionID == input.MissionID &&
		lease.ModelConnectionID == input.ModelConnectionID && lease.MissionFileID == input.MissionFileID &&
		lease.Purpose == input.Purpose && lease.AnalysisRunID == input.AnalysisRunID &&
		lease.SourceVersionID == input.SourceVersionID && lease.RenderedSlideSetID == input.RenderedSlideSetID &&
		lease.ProcessingRunID == input.ProcessingRunID && strings.EqualFold(lease.SourceSHA256, input.SourceSHA256) &&
		strings.EqualFold(lease.InputSHA256, input.InputSHA256) && strings.EqualFold(lease.PromptSHA256, input.PromptSHA256) &&
		lease.PreviewStorageKey == input.PreviewStorageKey && strings.EqualFold(lease.PreviewSHA256, input.PreviewSHA256) &&
		lease.PreviewSizeBytes == input.PreviewSizeBytes && lease.PreviewMediaType == input.PreviewMediaType &&
		lease.NonceHash == hash)
}

type modelExecutionLeaseScanner interface {
	Scan(...any) error
}

func scanModelExecutionLease(row modelExecutionLeaseScanner, lease *ModelExecutionLease) error {
	return row.Scan(
		&lease.ID, &lease.OwnerUserID, &lease.MissionID, &lease.ModelConnectionID, &lease.MissionFileID,
		&lease.Purpose, &lease.AnalysisRunID, &lease.SourceVersionID, &lease.RenderedSlideSetID,
		&lease.ProcessingRunID, &lease.SourceSHA256, &lease.InputSHA256, &lease.PromptSHA256,
		&lease.PreviewStorageKey, &lease.PreviewSHA256, &lease.PreviewSizeBytes, &lease.PreviewMediaType,
		&lease.NonceHash, &lease.ExpiresAt, &lease.ConsumedAt,
	)
}

// ResolveMissionTemplateFile is the Go-owned source identity check for the
// analyzer. The Java project/source ids are opaque audit coordinates; the
// mission file and source SHA are verified against the Go Mission database.
func (s *Store) ResolveMissionTemplateFile(ctx context.Context, owner, missionID, missionFileID int64, sourceSHA256 string) (model.FileObject, error) {
	var file model.FileObject
	err := s.DB.QueryRow(ctx, `
		SELECT f.id,f.original_name,f.mime_type,f.size_bytes,f.sha256,f.storage_key,f.created_at
		FROM mission_files mf
		JOIN missions m ON m.id=mf.mission_id
		JOIN file_objects f ON f.id=mf.file_object_id
		WHERE mf.id=$1 AND mf.mission_id=$2 AND m.owner_teacher_id=$3
		  AND m.selected_model_connection_id IS NOT NULL
		  AND mf.role='TEMPLATE' AND mf.parse_status='READY'
		  AND f.owner_user_id=$3 AND lower(f.sha256)=lower($4)`, missionFileID, missionID, owner, sourceSHA256).Scan(
		&file.ID, &file.OriginalName, &file.MimeType, &file.Size, &file.SHA256, &file.StorageKey, &file.CreatedAt,
	)
	return file, err
}
