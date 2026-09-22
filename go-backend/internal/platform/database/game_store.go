package database

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"lessonforge.local/backend/internal/model"
)

const (
	GameStatusQueued      = "QUEUED"
	GameStatusRunning     = "RUNNING"
	GameStatusSucceeded   = "SUCCEEDED"
	GameStatusFailed      = "FAILED"
	GameStatusCancelled   = "CANCELLED"
	GameArtifactReady     = "READY"
	GameArtifactPublished = "PUBLISHED"
)

var (
	ErrGameMissionNotFound       = errors.New("GAME_MISSION_NOT_FOUND")
	ErrGameSourceNotReady        = errors.New("GAME_SOURCE_NOT_READY")
	ErrGameJobNotFound           = errors.New("GAME_JOB_NOT_FOUND")
	ErrGameArtifactNotFound      = errors.New("GAME_ARTIFACT_NOT_FOUND")
	ErrGameSourceMaterialMissing = errors.New("GAME_MATERIAL_SOURCE_REQUIRED")
	ErrGameTypeUnsupported       = errors.New("GAME_TYPE_UNSUPPORTED")
)

type GameJobRequestResult struct {
	Job            model.GameJob
	Spec           model.LockedSpecification
	Artifact       model.Artifact
	MaterialFileID int64
}

// CreateGameJob snapshots the latest immutable specification and the latest
// successful PPT artifact. The PPT file is only a prerequisite/source anchor;
// the game renderer never edits or appends to it.
func (s *Store) CreateGameJob(ctx context.Context, owner, missionID int64, requestedGameType string) (GameJobRequestResult, error) {
	if owner <= 0 || missionID <= 0 {
		return GameJobRequestResult{}, ErrGameMissionNotFound
	}
	gameType := strings.ToUpper(strings.TrimSpace(requestedGameType))
	if gameType == "" {
		gameType = "TRUE_FALSE"
	}
	switch gameType {
	case "SINGLE_CHOICE", "TRUE_FALSE", "MATCHING", "RUNNER":
	default:
		return GameJobRequestResult{}, ErrGameTypeUnsupported
	}
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return GameJobRequestResult{}, err
	}
	defer func() { s.recordCleanupError("game transaction rollback", tx.Rollback(ctx)) }()
	var missionOwner int64
	if err := tx.QueryRow(ctx, `SELECT owner_teacher_id FROM missions WHERE id=$1 FOR SHARE`, missionID).Scan(&missionOwner); err != nil || missionOwner != owner {
		return GameJobRequestResult{}, ErrGameMissionNotFound
	}
	var spec model.LockedSpecification
	var raw, binding []byte
	if err := tx.QueryRow(ctx, `SELECT id,mission_id,source_draft_id,version,specification_json,template_binding_json,content_hash,created_at FROM locked_specifications WHERE mission_id=$1 ORDER BY version DESC,created_at DESC LIMIT 1`, missionID).Scan(&spec.ID, &spec.MissionID, &spec.SourceDraftID, &spec.Version, &raw, &binding, &spec.ContentHash, &spec.CreatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return GameJobRequestResult{}, ErrGameSourceNotReady
		}
		return GameJobRequestResult{}, err
	}
	if err := json.Unmarshal(raw, &spec.Specification); err != nil {
		return GameJobRequestResult{}, err
	}
	if len(binding) > 0 && string(binding) != "null" {
		if err := json.Unmarshal(binding, &spec.TemplateBinding); err != nil {
			return GameJobRequestResult{}, err
		}
	}
	var artifact model.Artifact
	if err := tx.QueryRow(ctx, `SELECT a.id,a.mission_id,a.generation_job_id,a.version,a.content_type,a.sha256,a.size_bytes,a.status,a.created_at,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at
		FROM artifacts a JOIN file_objects fo ON fo.id=a.file_object_id JOIN generation_jobs g ON g.id=a.generation_job_id
		WHERE a.mission_id=$1 AND a.status='READY' AND g.status='SUCCEEDED' ORDER BY a.version DESC,a.created_at DESC LIMIT 1`, missionID).Scan(&artifact.ID, &artifact.MissionID, &artifact.GenerationJobID, &artifact.Version, &artifact.ContentType, &artifact.SHA256, &artifact.Size, &artifact.Status, &artifact.CreatedAt, &artifact.File.ID, &artifact.File.OriginalName, &artifact.File.MimeType, &artifact.File.Size, &artifact.File.SHA256, &artifact.File.StorageKey, &artifact.File.CreatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return GameJobRequestResult{}, ErrGameSourceNotReady
		}
		return GameJobRequestResult{}, err
	}
	var materialFileID int64
	if err := tx.QueryRow(ctx, `SELECT mf.id
		FROM mission_files mf JOIN missions m ON m.id=mf.mission_id
		WHERE mf.mission_id=$1 AND m.owner_teacher_id=$2 AND mf.role IN ('MATERIAL','TEACHING_PLAN')
		AND mf.provenance IN ('MATERIAL','TEACHER') AND mf.parse_status='READY'
		ORDER BY CASE WHEN mf.role='MATERIAL' THEN 0 ELSE 1 END,mf.created_at,mf.id LIMIT 1`, missionID, owner).Scan(&materialFileID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return GameJobRequestResult{}, ErrGameSourceMaterialMissing
		}
		return GameJobRequestResult{}, err
	}
	jobID := uuid.NewString()
	started := time.Now().UTC()
	if _, err := tx.Exec(ctx, `INSERT INTO game_jobs(id,mission_id,source_specification_id,source_artifact_id,game_type,status,created_at,started_at) VALUES($1,$2,$3,$4,$5,$6,now(),$7)`, jobID, missionID, spec.ID, artifact.ID, gameType, GameStatusRunning, started); err != nil {
		return GameJobRequestResult{}, err
	}
	if err := addActivityTx(ctx, tx, missionID, "GAME_GENERATION_REQUESTED", "互动小游戏生成已开始", "GAME_JOB", jobID); err != nil {
		return GameJobRequestResult{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return GameJobRequestResult{}, err
	}
	return GameJobRequestResult{Job: model.GameJob{ID: jobID, MissionID: missionID, SourceSpecificationID: spec.ID, SourceSpecificationVersion: spec.Version, SourceArtifactID: artifact.ID, GameType: gameType, Status: GameStatusRunning, CreatedAt: started, StartedAt: &started}, Spec: spec, Artifact: artifact, MaterialFileID: materialFileID}, nil
}

func (s *Store) FinishGameJob(ctx context.Context, owner int64, jobID string, gameType string, spec any, file model.FileObject) (model.GameArtifact, error) {
	if owner <= 0 || strings.TrimSpace(jobID) == "" || file.Size <= 0 || file.SHA256 == "" || file.StorageKey == "" {
		return model.GameArtifact{}, ErrGameJobNotFound
	}
	rawSpec, err := json.Marshal(spec)
	if err != nil {
		return model.GameArtifact{}, err
	}
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return model.GameArtifact{}, err
	}
	defer func() { s.recordCleanupError("game finish rollback", tx.Rollback(ctx)) }()
	var missionID int64
	var status string
	if err := tx.QueryRow(ctx, `SELECT g.mission_id,g.status FROM game_jobs g JOIN missions m ON m.id=g.mission_id WHERE g.id=$1 AND m.owner_teacher_id=$2 FOR UPDATE`, jobID, owner).Scan(&missionID, &status); err != nil {
		return model.GameArtifact{}, ErrGameJobNotFound
	}
	if status != GameStatusRunning {
		return model.GameArtifact{}, ErrGameJobNotFound
	}
	var version int
	if err := tx.QueryRow(ctx, `SELECT COALESCE(MAX(version),0)+1 FROM game_artifacts WHERE mission_id=$1`, missionID).Scan(&version); err != nil {
		return model.GameArtifact{}, err
	}
	fileID := int64(0)
	if err := tx.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,$3,$4,$5,$6) RETURNING id`, owner, file.StorageKey, file.OriginalName, file.MimeType, file.Size, file.SHA256).Scan(&fileID); err != nil {
		return model.GameArtifact{}, err
	}
	artifactID := uuid.NewString()
	created := time.Now().UTC()
	if _, err := tx.Exec(ctx, `INSERT INTO game_artifacts(id,mission_id,game_job_id,file_object_id,version,game_type,content_type,sha256,size_bytes,status,game_spec_json,created_at) VALUES($1,$2,$3,$4,$5,$6,'text/html','' || $7,$8,'READY',$9,$10)`, artifactID, missionID, jobID, fileID, version, gameType, file.SHA256, file.Size, rawSpec, created); err != nil {
		return model.GameArtifact{}, err
	}
	if _, err := tx.Exec(ctx, `UPDATE game_jobs SET status=$1,game_type=$2,game_spec_json=$3,artifact_id=$4,finished_at=$5 WHERE id=$6`, GameStatusSucceeded, gameType, rawSpec, artifactID, created, jobID); err != nil {
		return model.GameArtifact{}, err
	}
	if err := addActivityTx(ctx, tx, missionID, "GAME_ARTIFACT_READY", "互动小游戏已生成", "GAME_ARTIFACT", artifactID); err != nil {
		return model.GameArtifact{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return model.GameArtifact{}, err
	}
	return model.GameArtifact{ID: artifactID, MissionID: missionID, GameJobID: jobID, Version: version, GameType: gameType, File: model.FileObject{ID: fileID, OriginalName: file.OriginalName, MimeType: file.MimeType, Size: file.Size, SHA256: file.SHA256, StorageKey: file.StorageKey}, ContentType: "text/html", SHA256: file.SHA256, Size: file.Size, Status: GameArtifactReady, GameSpec: spec, CreatedAt: created}, nil
}

func (s *Store) FailGameJob(ctx context.Context, owner int64, jobID, code, message string) error {
	result, err := s.DB.Exec(ctx, `UPDATE game_jobs g SET status=$1,error_code=$2,error_message=$3,finished_at=clock_timestamp() FROM missions m WHERE g.id=$4 AND g.mission_id=m.id AND m.owner_teacher_id=$5 AND g.status=$6`, GameStatusFailed, code, message, jobID, owner, GameStatusRunning)
	if err == nil && result.RowsAffected() == 0 {
		return ErrGameJobNotFound
	}
	return err
}

func (s *Store) GameJobs(ctx context.Context, owner, missionID int64) ([]model.GameJob, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT g.id,g.mission_id,g.source_specification_id,ls.version,g.source_artifact_id,g.game_type,g.status,g.game_spec_json,g.error_code,g.error_message,g.artifact_id,g.created_at,g.started_at,g.finished_at FROM game_jobs g JOIN locked_specifications ls ON ls.id=g.source_specification_id WHERE g.mission_id=$1 ORDER BY g.created_at DESC,g.id DESC`, missionID)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("game jobs rows close", rows)
	var out []model.GameJob
	for rows.Next() {
		job, err := scanGameJob(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, job)
	}
	return out, rows.Err()
}

func (s *Store) GameArtifacts(ctx context.Context, owner, missionID int64) ([]model.GameArtifact, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT a.id,a.mission_id,a.game_job_id,a.version,a.game_type,a.content_type,a.sha256,a.size_bytes,a.status,a.game_spec_json,a.published_at,a.created_at,a.public_token,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at FROM game_artifacts a JOIN file_objects fo ON fo.id=a.file_object_id WHERE a.mission_id=$1 AND a.status IN ('READY','PUBLISHED') ORDER BY a.version DESC,a.created_at DESC`, missionID)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("game artifacts rows close", rows)
	var out []model.GameArtifact
	for rows.Next() {
		artifact, err := scanGameArtifactWithToken(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, artifact)
	}
	return out, rows.Err()
}

func (s *Store) GameArtifact(ctx context.Context, owner int64, artifactID string) (model.GameArtifact, error) {
	var artifact model.GameArtifact
	var raw []byte
	err := s.DB.QueryRow(ctx, `SELECT a.id,a.mission_id,a.game_job_id,a.version,a.game_type,a.content_type,a.sha256,a.size_bytes,a.status,a.game_spec_json,a.published_at,a.created_at,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at FROM game_artifacts a JOIN file_objects fo ON fo.id=a.file_object_id JOIN missions m ON m.id=a.mission_id WHERE a.id=$1 AND a.status IN ('READY','PUBLISHED') AND m.owner_teacher_id=$2`, artifactID, owner).Scan(gameArtifactArgs(&artifact, &raw)...)
	if err != nil {
		return artifact, ErrGameArtifactNotFound
	}
	if len(raw) > 0 && json.Unmarshal(raw, &artifact.GameSpec) != nil {
		return artifact, errors.New("GAME_SPEC_DECODE_FAILED")
	}
	return artifact, nil
}

func (s *Store) PublishGameArtifact(ctx context.Context, owner int64, artifactID string) (model.GameArtifact, error) {
	token := uuid.NewString() + uuid.NewString()
	var artifact model.GameArtifact
	var raw []byte
	err := s.DB.QueryRow(ctx, `UPDATE game_artifacts a SET status='PUBLISHED',public_token=$1,published_at=clock_timestamp() FROM missions m WHERE a.id=$2 AND a.mission_id=m.id AND m.owner_teacher_id=$3 AND a.status IN ('READY','PUBLISHED') RETURNING a.id,a.mission_id,a.game_job_id,a.version,a.game_type,a.content_type,a.sha256,a.size_bytes,a.status,a.game_spec_json,a.published_at,a.created_at,(SELECT fo.id FROM file_objects fo WHERE fo.id=a.file_object_id),(SELECT fo.original_name FROM file_objects fo WHERE fo.id=a.file_object_id),(SELECT fo.mime_type FROM file_objects fo WHERE fo.id=a.file_object_id),(SELECT fo.size_bytes FROM file_objects fo WHERE fo.id=a.file_object_id),(SELECT fo.sha256 FROM file_objects fo WHERE fo.id=a.file_object_id),(SELECT fo.storage_key FROM file_objects fo WHERE fo.id=a.file_object_id),(SELECT fo.created_at FROM file_objects fo WHERE fo.id=a.file_object_id)`, token, artifactID, owner).Scan(gameArtifactArgs(&artifact, &raw)...)
	if err != nil {
		return artifact, ErrGameArtifactNotFound
	}
	artifact.PublicToken = token
	if len(raw) > 0 && json.Unmarshal(raw, &artifact.GameSpec) != nil {
		return artifact, errors.New("GAME_SPEC_DECODE_FAILED")
	}
	return artifact, nil
}

func (s *Store) PublicGameArtifact(ctx context.Context, token string) (model.GameArtifact, error) {
	var artifact model.GameArtifact
	var raw []byte
	err := s.DB.QueryRow(ctx, `SELECT a.id,a.mission_id,a.game_job_id,a.version,a.game_type,a.content_type,a.sha256,a.size_bytes,a.status,a.game_spec_json,a.published_at,a.created_at,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at FROM game_artifacts a JOIN file_objects fo ON fo.id=a.file_object_id WHERE a.public_token=$1 AND a.status='PUBLISHED'`, token).Scan(gameArtifactArgs(&artifact, &raw)...)
	if err != nil {
		return artifact, ErrGameArtifactNotFound
	}
	artifact.PublicToken = token
	if len(raw) > 0 && json.Unmarshal(raw, &artifact.GameSpec) != nil {
		return artifact, errors.New("GAME_SPEC_DECODE_FAILED")
	}
	return artifact, nil
}

type scanner interface{ Scan(...any) error }

func scanGameJob(row scanner) (model.GameJob, error) {
	var job model.GameJob
	var raw []byte
	var artifactID, errorCode, errorMessage *string
	err := row.Scan(&job.ID, &job.MissionID, &job.SourceSpecificationID, &job.SourceSpecificationVersion, &job.SourceArtifactID, &job.GameType, &job.Status, &raw, &errorCode, &errorMessage, &artifactID, &job.CreatedAt, &job.StartedAt, &job.FinishedAt)
	job.ArtifactID = artifactID
	if errorCode != nil {
		job.ErrorCode = *errorCode
	}
	if errorMessage != nil {
		job.ErrorMessage = *errorMessage
	}
	if err == nil && len(raw) > 0 {
		err = json.Unmarshal(raw, &job.GameSpec)
	}
	return job, err
}
func gameArtifactArgs(artifact *model.GameArtifact, raw *[]byte) []any {
	return []any{&artifact.ID, &artifact.MissionID, &artifact.GameJobID, &artifact.Version, &artifact.GameType, &artifact.ContentType, &artifact.SHA256, &artifact.Size, &artifact.Status, raw, &artifact.PublishedAt, &artifact.CreatedAt, &artifact.File.ID, &artifact.File.OriginalName, &artifact.File.MimeType, &artifact.File.Size, &artifact.File.SHA256, &artifact.File.StorageKey, &artifact.File.CreatedAt}
}
func scanGameArtifact(row scanner) (model.GameArtifact, error) {
	var artifact model.GameArtifact
	var raw []byte
	err := row.Scan(gameArtifactArgs(&artifact, &raw)...)
	if err == nil && len(raw) > 0 {
		err = json.Unmarshal(raw, &artifact.GameSpec)
	}
	return artifact, err
}

func scanGameArtifactWithToken(row scanner) (model.GameArtifact, error) {
	var artifact model.GameArtifact
	var raw []byte
	var publicToken *string
	args := gameArtifactArgs(&artifact, &raw)
	args = append(args[:12], append([]any{&publicToken}, args[12:]...)...)
	err := row.Scan(args...)
	if publicToken != nil {
		artifact.PublicToken = *publicToken
		artifact.AccessURL = "/games/" + *publicToken
	}
	if err == nil && len(raw) > 0 {
		err = json.Unmarshal(raw, &artifact.GameSpec)
	}
	return artifact, err
}
