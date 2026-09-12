package database

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"lessonforge.local/backend/internal/missionprogress"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/specification"
	"lessonforge.local/backend/internal/templatebinding"
)

type TemplateProfileResolver interface {
	GetProfile(context.Context, int64, int64, string) (map[string]any, error)
}

const templateProfileResolveTimeout = 5 * time.Second

type Store struct {
	DB              *pgxpool.Pool
	WorkerID        string
	LeaseDuration   time.Duration
	StorageRoot     string
	TemplateProfile TemplateProfileResolver

	// These hooks are deliberately unexported and are only used by same-package
	// PostgreSQL integration tests to coordinate a transaction at its real fence.
	// They are nil on every production Store.
	beforeAgentOutputWriteHook        func()
	beforeAgentOutputCommitHook       func(context.Context, pgx.Tx) error
	beforeGenerationArtifactWriteHook func()
	beforeGenerationClaimHook         func()
	afterGenerationClaimHook          func(model.GenerationJob)
	afterCommitHook                   func(string) error
	cleanupMu                         sync.Mutex
	cleanupErrors                     []string
}

var ErrCommitAmbiguous = model.ErrCommitAmbiguous

var (
	ErrGenerationMissionNotFound         = errors.New("GENERATION_MISSION_NOT_FOUND")
	ErrGenerationSpecificationNotFound   = errors.New("GENERATION_SPECIFICATION_NOT_FOUND")
	ErrGenerationVersionMismatch         = errors.New("GENERATION_SPECIFICATION_VERSION_MISMATCH")
	ErrGenerationTemplateInvalid         = errors.New("GENERATION_TEMPLATE_BINDING_INVALID")
	ErrGenerationTemplateProfileNotReady = errors.New("GENERATION_TEMPLATE_PROFILE_NOT_READY")
	ErrGenerationMaterialsNotReady       = errors.New("GENERATION_MATERIALS_NOT_READY")
	ErrGenerationActiveConflict          = errors.New("GENERATION_ACTIVE_JOB_CONFLICT")
)

type GenerationJobRequestResult struct {
	Job     model.GenerationJob
	Created bool
}

const (
	AgentOutputStageMessage  = "MESSAGE"
	AgentOutputStageQuestion = "QUESTION"
	AgentOutputStagePlan     = "PLAN_DRAFT"
	ArtifactStatusStaged     = "STAGED"
	ArtifactStatusReady      = "READY"
)

type modelIdentitySnapshot struct {
	ConnectionID           int64                        `json:"connectionId"`
	Provider               string                       `json:"provider"`
	Protocol               string                       `json:"protocol"`
	BaseURL                string                       `json:"baseUrl"`
	ModelID                string                       `json:"modelId"`
	Capabilities           model.ModelCapabilities      `json:"capabilities"`
	CapabilityVerification model.CapabilityVerification `json:"capabilityVerification"`
	PromptVersion          string                       `json:"promptVersion"`
	ContextPolicyVersion   string                       `json:"contextPolicyVersion"`
	EncryptedAPIKey        string                       `json:"encryptedApiKey"`
	KeyHint                string                       `json:"keyHint"`
}

func decodeConnectionCapabilities(raw []byte) (model.ModelCapabilities, model.CapabilityVerification, error) {
	capabilities := model.DefaultModelCapabilities()
	verification := model.DefaultCapabilityVerification()
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &capabilities); err != nil {
			return model.ModelCapabilities{}, model.CapabilityVerification{}, err
		}
	}
	return capabilities, verification, nil
}

func decodeCapabilityVerification(raw []byte) (model.CapabilityVerification, error) {
	verification := model.DefaultCapabilityVerification()
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &verification); err != nil {
			return model.CapabilityVerification{}, err
		}
	}
	return model.NormalizeCapabilityVerification(verification), nil
}

func snapshotForTx(ctx context.Context, tx pgx.Tx, owner int64, connectionID *int64, requireActive bool) (modelIdentitySnapshot, error) {
	if connectionID == nil {
		return modelIdentitySnapshot{}, errors.New("MODEL_CONNECTION_REQUIRED")
	}
	var snapshot modelIdentitySnapshot
	query := `SELECT id,provider,protocol,base_url,model_id,capabilities,capability_verification,encrypted_api_key,key_hint FROM model_connections WHERE id=$1 AND owner_user_id=$2`
	if requireActive {
		query += ` AND enabled=true AND verification_status='VERIFIED'`
	}
	var capabilities, verification []byte
	err := tx.QueryRow(ctx, query, *connectionID, owner).Scan(&snapshot.ConnectionID, &snapshot.Provider, &snapshot.Protocol, &snapshot.BaseURL, &snapshot.ModelID, &capabilities, &verification, &snapshot.EncryptedAPIKey, &snapshot.KeyHint)
	if err != nil {
		return modelIdentitySnapshot{}, errors.New("MODEL_CONNECTION_UNAVAILABLE")
	}
	var decodeErr error
	snapshot.Capabilities, snapshot.CapabilityVerification, decodeErr = decodeConnectionCapabilities(capabilities)
	if decodeErr != nil {
		return modelIdentitySnapshot{}, errors.New("MODEL_CONNECTION_UNAVAILABLE")
	}
	snapshot.CapabilityVerification, decodeErr = decodeCapabilityVerification(verification)
	if decodeErr != nil {
		return modelIdentitySnapshot{}, errors.New("MODEL_CONNECTION_UNAVAILABLE")
	}
	return snapshot, nil
}

func waitingSnapshot() map[string]any {
	return map[string]any{
		"source":               "WAITING_INPUTS",
		"promptVersion":        model.PlanAgentPromptVersion,
		"contextPolicyVersion": model.PlanAgentContextPolicyVersion,
	}
}

func runnableAgentSnapshot(identity modelIdentitySnapshot) map[string]any {
	return map[string]any{
		"connectionId":           identity.ConnectionID,
		"provider":               identity.Provider,
		"protocol":               identity.Protocol,
		"baseUrl":                identity.BaseURL,
		"modelId":                identity.ModelID,
		"capabilities":           identity.Capabilities,
		"capabilityVerification": identity.CapabilityVerification,
		"promptVersion":          model.PlanAgentPromptVersion,
		"contextPolicyVersion":   model.PlanAgentContextPolicyVersion,
		"encryptedApiKey":        identity.EncryptedAPIKey,
		"keyHint":                identity.KeyHint,
	}
}

func NewStore(db *pgxpool.Pool) *Store {
	return &Store{DB: db, WorkerID: uuid.NewString(), LeaseDuration: 30 * time.Second}
}

// ConfigureStorageRoot lets immutable generation bindings capture the actual
// file identity from the same storage mount used by the upload service. The
// root is intentionally optional for isolated database tests; production
// wiring always provides it.
func (s *Store) ConfigureStorageRoot(root string) {
	s.StorageRoot = strings.TrimSpace(root)
}

// SetAfterCommitHookForTest exposes only the acknowledgement-loss seam used
// by real PostgreSQL integration tests. Production stores leave it unset.
func (s *Store) SetAfterCommitHookForTest(h func(string) error) {
	s.afterCommitHook = h
}

// SetBeforeAgentOutputCommitHookForTest is restricted to integration tests
// that terminate the real PostgreSQL backend in the acknowledgement window.
// Production leaves this nil.
func (s *Store) SetBeforeAgentOutputCommitHookForTest(h func(context.Context, pgx.Tx) error) {
	s.beforeAgentOutputCommitHook = h
}

func (s *Store) recordCleanupError(operation string, err error) {
	if err == nil || errors.Is(err, pgx.ErrTxClosed) {
		return
	}
	s.cleanupMu.Lock()
	s.cleanupErrors = append(s.cleanupErrors, operation+": "+err.Error())
	s.cleanupMu.Unlock()
}

func (s *Store) closeRows(operation string, rows pgx.Rows) {
	rows.Close()
	s.recordCleanupError(operation, rows.Err())
}

// CleanupErrors keeps deferred cleanup failures observable for callers that
// cannot receive a second return value from a cleanup callback.
func (s *Store) CleanupErrors() []string {
	s.cleanupMu.Lock()
	defer s.cleanupMu.Unlock()
	return append([]string(nil), s.cleanupErrors...)
}

// SetBeforeGenerationArtifactWriteHookForTest coordinates a real generation
// output transaction in PostgreSQL integration tests.
func (s *Store) SetBeforeGenerationArtifactWriteHookForTest(h func()) {
	s.beforeGenerationArtifactWriteHook = h
}

// SetGenerationClaimHooksForTest exposes only claim timing observations for
// same-package PostgreSQL integration tests. Production stores leave them nil.
func (s *Store) SetGenerationClaimHooksForTest(before func(), after func(model.GenerationJob)) {
	s.beforeGenerationClaimHook = before
	s.afterGenerationClaimHook = after
}

func (s *Store) ConfigureWorker(workerID string, leaseDuration time.Duration) {
	if strings.TrimSpace(workerID) != "" {
		s.WorkerID = workerID
	}
	if leaseDuration > 0 {
		s.LeaseDuration = leaseDuration
	}
}

func (s *Store) leaseSettings() (string, float64) {
	owner := s.WorkerID
	if owner == "" {
		owner = uuid.NewString()
	}
	duration := s.LeaseDuration
	if duration <= 0 {
		duration = 30 * time.Second
	}
	return owner, duration.Seconds()
}

func (s *Store) LeaseHeartbeatInterval() time.Duration {
	duration := s.LeaseDuration
	if duration <= 0 {
		duration = 30 * time.Second
	}
	interval := duration / 3
	if interval < 250*time.Millisecond {
		return 250 * time.Millisecond
	}
	return interval
}
func baseURLHost(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	return parsed.Host
}

func (s *Store) CreateUser(ctx context.Context, name, email, passwordHash string, role model.Role) (model.User, error) {
	var u model.User
	err := s.DB.QueryRow(ctx, `INSERT INTO users(name,email,password_hash,role) VALUES($1,$2,$3,$4) RETURNING id,name,email,role`, strings.TrimSpace(name), strings.ToLower(strings.TrimSpace(email)), passwordHash, role).Scan(&u.ID, &u.Name, &u.Email, &u.Role)
	return u, err
}
func (s *Store) UserByEmail(ctx context.Context, email string) (model.User, string, error) {
	var u model.User
	var hash string
	err := s.DB.QueryRow(ctx, `SELECT id,name,email,role,password_hash FROM users WHERE lower(email)=lower($1) AND status='ACTIVE'`, email).Scan(&u.ID, &u.Name, &u.Email, &u.Role, &hash)
	return u, hash, err
}
func (s *Store) UserByID(ctx context.Context, id int64) (model.User, error) {
	var u model.User
	err := s.DB.QueryRow(ctx, `SELECT id,name,email,role FROM users WHERE id=$1 AND status='ACTIVE'`, id).Scan(&u.ID, &u.Name, &u.Email, &u.Role)
	return u, err
}
func (s *Store) CreateSession(ctx context.Context, userID int64, tokenHash string, expires time.Time) error {
	_, err := s.DB.Exec(ctx, `INSERT INTO sessions(user_id,token_hash,expires_at) VALUES($1,$2,$3)`, userID, tokenHash, expires)
	return err
}
func (s *Store) UserBySessionHash(ctx context.Context, tokenHash string) (model.User, error) {
	var u model.User
	err := s.DB.QueryRow(ctx, `SELECT u.id,u.name,u.email,u.role FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=$1 AND s.expires_at>now() AND u.status='ACTIVE'`, tokenHash).Scan(&u.ID, &u.Name, &u.Email, &u.Role)
	return u, err
}
func (s *Store) DeleteSession(ctx context.Context, tokenHash string) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM sessions WHERE token_hash=$1`, tokenHash)
	return err
}

func (s *Store) ListConnections(ctx context.Context, owner int64) ([]model.ModelConnection, error) {
	rows, err := s.DB.Query(ctx, `SELECT id,owner_user_id,name,provider,protocol,base_url,model_id,capabilities,capability_verification,key_hint,enabled,verification_status,last_verified_at,last_used_at,created_at,updated_at FROM model_connections WHERE owner_user_id=$1 ORDER BY created_at,id`, owner)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close list connections", rows)
	var result []model.ModelConnection
	for rows.Next() {
		var c model.ModelConnection
		var capabilities, verification []byte
		if err := rows.Scan(&c.ID, &c.OwnerUserID, &c.Name, &c.Provider, &c.Protocol, &c.BaseURL, &c.ModelID, &capabilities, &verification, &c.KeyHint, &c.Enabled, &c.VerificationStatus, &c.LastVerifiedAt, &c.LastUsedAt, &c.CreatedAt, &c.UpdatedAt); err != nil {
			return nil, err
		}
		var decodeErr error
		c.Capabilities, c.CapabilityVerification, decodeErr = decodeConnectionCapabilities(capabilities)
		if decodeErr != nil {
			return nil, decodeErr
		}
		c.CapabilityVerification, decodeErr = decodeCapabilityVerification(verification)
		if decodeErr != nil {
			return nil, decodeErr
		}
		result = append(result, c)
	}
	return result, rows.Err()
}
func (s *Store) Connection(ctx context.Context, owner, id int64) (model.ModelConnection, string, error) {
	var c model.ModelConnection
	var encrypted string
	var capabilities, verification []byte
	err := s.DB.QueryRow(ctx, `SELECT id,owner_user_id,name,provider,protocol,base_url,model_id,capabilities,capability_verification,key_hint,enabled,verification_status,last_verified_at,last_used_at,created_at,updated_at,encrypted_api_key FROM model_connections WHERE id=$1 AND owner_user_id=$2`, id, owner).Scan(&c.ID, &c.OwnerUserID, &c.Name, &c.Provider, &c.Protocol, &c.BaseURL, &c.ModelID, &capabilities, &verification, &c.KeyHint, &c.Enabled, &c.VerificationStatus, &c.LastVerifiedAt, &c.LastUsedAt, &c.CreatedAt, &c.UpdatedAt, &encrypted)
	if err != nil {
		return c, "", err
	}
	var decodeErr error
	c.Capabilities, c.CapabilityVerification, decodeErr = decodeConnectionCapabilities(capabilities)
	if decodeErr != nil {
		return c, "", decodeErr
	}
	c.CapabilityVerification, decodeErr = decodeCapabilityVerification(verification)
	if decodeErr != nil {
		return c, "", decodeErr
	}
	return c, encrypted, err
}

// ResolveVisionConnection binds one model execution to the Mission's current
// selection while holding a shared row lock on the Mission. Java callers may
// provide an expected connection id, but the Go control plane remains the
// authority: a missing selection, owner mismatch, disabled connection,
// unverified connection, or unverified vision capability all fail closed.
func (s *Store) ResolveVisionConnection(ctx context.Context, owner, missionID int64, expected *int64) (model.ModelConnection, string, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return model.ModelConnection{}, "", err
	}
	defer func() { s.recordCleanupError("vision connection transaction rollback", tx.Rollback(ctx)) }()

	var selected *int64
	if err := tx.QueryRow(ctx, `SELECT selected_model_connection_id FROM missions WHERE id=$1 AND owner_teacher_id=$2 FOR SHARE`, missionID, owner).Scan(&selected); err != nil {
		return model.ModelConnection{}, "", err
	}
	if selected == nil {
		return model.ModelConnection{}, "", errors.New("MODEL_CONNECTION_REQUIRED")
	}
	if expected != nil && *expected != *selected {
		return model.ModelConnection{}, "", errors.New("MODEL_CONNECTION_SELECTION_CHANGED")
	}

	var c model.ModelConnection
	var encrypted string
	var capabilities, verification []byte
	err = tx.QueryRow(ctx, `
		SELECT id,owner_user_id,name,provider,protocol,base_url,model_id,capabilities,
		       capability_verification,key_hint,enabled,verification_status,last_verified_at,
		       last_used_at,created_at,updated_at,encrypted_api_key
		FROM model_connections
		WHERE id=$1 AND owner_user_id=$2 AND enabled=true AND verification_status='VERIFIED'
		FOR SHARE`, *selected, owner).Scan(
		&c.ID, &c.OwnerUserID, &c.Name, &c.Provider, &c.Protocol, &c.BaseURL, &c.ModelID,
		&capabilities, &verification, &c.KeyHint, &c.Enabled, &c.VerificationStatus,
		&c.LastVerifiedAt, &c.LastUsedAt, &c.CreatedAt, &c.UpdatedAt, &encrypted,
	)
	if err != nil {
		return model.ModelConnection{}, "", err
	}
	if c.Capabilities, c.CapabilityVerification, err = decodeConnectionCapabilities(capabilities); err != nil {
		return model.ModelConnection{}, "", err
	}
	if c.CapabilityVerification, err = decodeCapabilityVerification(verification); err != nil {
		return model.ModelConnection{}, "", err
	}
	if !c.Capabilities.SupportsVision || c.CapabilityVerification.SupportsVision != model.CapabilityVerified {
		return model.ModelConnection{}, "", errors.New("MODEL_VISION_NOT_VERIFIED")
	}
	if err := tx.Commit(ctx); err != nil {
		return model.ModelConnection{}, "", err
	}
	return c, encrypted, nil
}

func (s *Store) ConnectionOwnedBy(ctx context.Context, owner, id int64) error {
	var ok bool
	if err := s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM model_connections WHERE id=$1 AND owner_user_id=$2)`, id, owner).Scan(&ok); err != nil {
		return err
	}
	if !ok {
		return pgx.ErrNoRows
	}
	return nil
}
func (s *Store) ConnectionUsable(ctx context.Context, owner, id int64) error {
	var ok bool
	if err := s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM model_connections WHERE id=$1 AND owner_user_id=$2 AND enabled=true AND verification_status='VERIFIED')`, id, owner).Scan(&ok); err != nil {
		return err
	}
	if !ok {
		return pgx.ErrNoRows
	}
	return nil
}
func (s *Store) CreateConnection(ctx context.Context, owner int64, c model.ModelConnection, encrypted string) (model.ModelConnection, error) {
	var out model.ModelConnection
	provider := strings.TrimSpace(c.Provider)
	if provider == "" {
		provider = "CUSTOM"
	}
	capabilities := model.NormalizeCapabilities(c.Capabilities, c.CapabilitiesSet)
	verification := model.NormalizeCapabilityVerification(c.CapabilityVerification)
	capabilitiesJSON, err := json.Marshal(capabilities)
	if err != nil {
		return out, err
	}
	verificationJSON, err := json.Marshal(verification)
	if err != nil {
		return out, err
	}
	var rawCapabilities, rawVerification []byte
	err = s.DB.QueryRow(ctx, `INSERT INTO model_connections(owner_user_id,name,provider,protocol,base_url,model_id,capabilities,capability_verification,encrypted_api_key,key_hint) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING id,owner_user_id,name,provider,protocol,base_url,model_id,capabilities,capability_verification,key_hint,enabled,verification_status,last_verified_at,last_used_at,created_at,updated_at`, owner, c.Name, provider, c.Protocol, c.BaseURL, c.ModelID, capabilitiesJSON, verificationJSON, encrypted, c.KeyHint).Scan(&out.ID, &out.OwnerUserID, &out.Name, &out.Provider, &out.Protocol, &out.BaseURL, &out.ModelID, &rawCapabilities, &rawVerification, &out.KeyHint, &out.Enabled, &out.VerificationStatus, &out.LastVerifiedAt, &out.LastUsedAt, &out.CreatedAt, &out.UpdatedAt)
	if err == nil {
		out.Capabilities, _, err = decodeConnectionCapabilities(rawCapabilities)
		if err == nil {
			out.CapabilityVerification, err = decodeCapabilityVerification(rawVerification)
		}
	}
	return out, err
}
func (s *Store) UpdateConnection(ctx context.Context, owner, id int64, c model.ModelConnection, encrypted, keyHint string, replaceKey bool) (model.ModelConnection, error) {
	var out model.ModelConnection
	provider := strings.TrimSpace(c.Provider)
	if provider == "" {
		provider = "CUSTOM"
	}
	capabilities := model.NormalizeCapabilities(c.Capabilities, c.CapabilitiesSet)
	capabilitiesJSON, err := json.Marshal(capabilities)
	if err != nil {
		return out, err
	}
	verificationJSON, err := json.Marshal(model.DefaultCapabilityVerification())
	if err != nil {
		return out, err
	}
	var rawCapabilities, rawVerification []byte
	// Any editable connection field change invalidates the old capability
	// handshake; the teacher must verify the connection again.
	err = s.DB.QueryRow(ctx, `UPDATE model_connections SET name=$1,provider=$2,protocol=$3,base_url=$4,model_id=$5,capabilities=$6,capability_verification=$7,encrypted_api_key=CASE WHEN $8 THEN $9 ELSE encrypted_api_key END,key_hint=CASE WHEN $8 THEN $10 ELSE key_hint END,verification_status='UNVERIFIED',last_verified_at=NULL,updated_at=now() WHERE id=$11 AND owner_user_id=$12 RETURNING id,owner_user_id,name,provider,protocol,base_url,model_id,capabilities,capability_verification,key_hint,enabled,verification_status,last_verified_at,last_used_at,created_at,updated_at`, c.Name, provider, c.Protocol, c.BaseURL, c.ModelID, capabilitiesJSON, verificationJSON, replaceKey, encrypted, keyHint, id, owner).Scan(&out.ID, &out.OwnerUserID, &out.Name, &out.Provider, &out.Protocol, &out.BaseURL, &out.ModelID, &rawCapabilities, &rawVerification, &out.KeyHint, &out.Enabled, &out.VerificationStatus, &out.LastVerifiedAt, &out.LastUsedAt, &out.CreatedAt, &out.UpdatedAt)
	if err == nil {
		out.Capabilities, _, err = decodeConnectionCapabilities(rawCapabilities)
		if err == nil {
			out.CapabilityVerification, err = decodeCapabilityVerification(rawVerification)
		}
	}
	return out, err
}
func (s *Store) SetConnectionEnabled(ctx context.Context, owner, id int64, enabled bool) (model.ModelConnection, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return model.ModelConnection{}, err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var c model.ModelConnection
	var rawCapabilities, rawVerification []byte
	err = tx.QueryRow(ctx, `UPDATE model_connections SET enabled=$1,updated_at=now() WHERE id=$2 AND owner_user_id=$3 RETURNING id,owner_user_id,name,provider,protocol,base_url,model_id,capabilities,capability_verification,key_hint,enabled,verification_status,last_verified_at,last_used_at,created_at,updated_at`, enabled, id, owner).Scan(&c.ID, &c.OwnerUserID, &c.Name, &c.Provider, &c.Protocol, &c.BaseURL, &c.ModelID, &rawCapabilities, &rawVerification, &c.KeyHint, &c.Enabled, &c.VerificationStatus, &c.LastVerifiedAt, &c.LastUsedAt, &c.CreatedAt, &c.UpdatedAt)
	if err != nil {
		return c, err
	}
	if c.Capabilities, _, err = decodeConnectionCapabilities(rawCapabilities); err != nil {
		return c, err
	}
	if c.CapabilityVerification, err = decodeCapabilityVerification(rawVerification); err != nil {
		return c, err
	}
	if !enabled {
		rows, queryErr := tx.Query(ctx, `SELECT id FROM missions WHERE owner_teacher_id=$1 AND selected_model_connection_id=$2 FOR UPDATE`, owner, id)
		if queryErr != nil {
			return c, queryErr
		}
		var missionIDs []int64
		for rows.Next() {
			var missionID int64
			if err = rows.Scan(&missionID); err != nil {
				rows.Close()
				return c, err
			}
			missionIDs = append(missionIDs, missionID)
		}
		if err = rows.Err(); err != nil {
			rows.Close()
			return c, err
		}
		rows.Close()
		if _, err = tx.Exec(ctx, `UPDATE missions SET selected_model_connection_id=NULL,updated_at=now() WHERE owner_teacher_id=$1 AND selected_model_connection_id=$2`, owner, id); err != nil {
			return c, err
		}
		for _, missionID := range missionIDs {
			if err = addActivityTx(ctx, tx, missionID, "MODEL_CONNECTION_CLEARED", "Model connection disabled and cleared", "MODEL_CONNECTION", ""); err != nil {
				return c, err
			}
		}
		if _, err = tx.Exec(ctx, `UPDATE agent_runs a SET status='CANCELLED',error_code='AGENT_RUN_CONNECTION_DISABLED',error_message='Model connection disabled',finished_at=COALESCE(finished_at,now()),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL FROM missions m WHERE a.mission_id=m.id AND m.owner_teacher_id=$1 AND a.model_connection_id=$2 AND a.status IN ('WAITING_INPUTS','QUEUED','RUNNING')`, owner, id); err != nil {
			return c, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return c, err
	}
	return c, nil
}
func (s *Store) MarkConnectionVerification(ctx context.Context, owner, id int64, status string) error {
	_, err := s.DB.Exec(ctx, `UPDATE model_connections SET verification_status=$1,last_verified_at=now(),updated_at=now() WHERE id=$2 AND owner_user_id=$3`, status, id, owner)
	return err
}
func (s *Store) MarkConnectionCapabilityVerification(ctx context.Context, owner, id int64, checks model.CapabilityCheckResult) error {
	var capabilities model.ModelCapabilities
	var raw []byte
	if err := s.DB.QueryRow(ctx, `SELECT capabilities FROM model_connections WHERE id=$1 AND owner_user_id=$2`, id, owner).Scan(&raw); err != nil {
		return err
	}
	capabilities, _, err := decodeConnectionCapabilities(raw)
	if err != nil {
		return err
	}
	capabilities.SupportsTools = checks.ToolCalling
	capabilities.SupportsJSONMode = checks.JSONMode
	if checks.VisionProbed {
		capabilities.SupportsVision = checks.Vision
	}
	encodedCapabilities, err := json.Marshal(capabilities)
	if err != nil {
		return err
	}
	verification := model.DefaultCapabilityVerification()
	if checks.ToolCalling {
		verification.SupportsTools = model.CapabilityVerified
	} else {
		verification.SupportsTools = model.CapabilityUnsupported
	}
	if checks.JSONMode {
		verification.SupportsJSONMode = model.CapabilityVerified
	} else {
		verification.SupportsJSONMode = model.CapabilityUnsupported
	}
	if checks.VisionProbed {
		if checks.Vision {
			verification.SupportsVision = model.CapabilityVerified
		} else {
			verification.SupportsVision = model.CapabilityUnsupported
		}
	}
	encodedVerification, err := json.Marshal(verification)
	if err != nil {
		return err
	}
	_, err = s.DB.Exec(ctx, `UPDATE model_connections SET capabilities=$1,capability_verification=$2,updated_at=now() WHERE id=$3 AND owner_user_id=$4`, encodedCapabilities, encodedVerification, id, owner)
	return err
}
func (s *Store) MarkConnectionUsed(ctx context.Context, owner, id int64) error {
	_, err := s.DB.Exec(ctx, `UPDATE model_connections SET last_used_at=now(),updated_at=now() WHERE id=$1 AND owner_user_id=$2`, id, owner)
	return err
}
func (s *Store) RecordModelAudit(ctx context.Context, requestID uuid.UUID, actor, mission, connectionID int64, c model.ModelConnection, purpose string, status int, latency time.Duration) error {
	_, err := s.DB.Exec(ctx, `INSERT INTO execution_audits(request_id,actor_user_id,mission_id,model_connection_id,protocol,base_url_host,model_id,purpose,credential_source,http_status,latency_ms) VALUES($1,$2,NULLIF($3,0),$4,$5,$6,$7,$8,'USER_BYOK',$9,$10)`, requestID, actor, mission, connectionID, c.Protocol, baseURLHost(c.BaseURL), c.ModelID, purpose, status, latency.Milliseconds())
	return err
}
func (s *Store) DeleteConnection(ctx context.Context, owner, id int64) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM model_connections WHERE id=$1 AND owner_user_id=$2`, id, owner)
	return err
}

func (s *Store) CreateUpload(ctx context.Context, owner int64, file model.FileObject, expires time.Time) (string, error) {
	id := uuid.NewString()
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	err = tx.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,$3,$4,$5,$6) RETURNING id`, owner, file.StorageKey, file.OriginalName, file.MimeType, file.Size, file.SHA256).Scan(&file.ID)
	if err != nil {
		return "", err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO uploads(id,owner_user_id,file_object_id,expires_at) VALUES($1,$2,$3,$4)`, id, owner, file.ID, expires); err != nil {
		return "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", err
	}
	return id, nil
}
func (s *Store) AddMissionFile(ctx context.Context, owner, missionID int64, file model.FileObject) (int64, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var fileID, missionFileID int64
	var exists bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM missions WHERE id=$1 AND owner_teacher_id=$2)`, missionID, owner).Scan(&exists); err != nil || !exists {
		if err != nil {
			return 0, err
		}
		return 0, pgx.ErrNoRows
	}
	if err := tx.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,$3,$4,$5,$6) RETURNING id`, owner, file.StorageKey, file.OriginalName, file.MimeType, file.Size, file.SHA256).Scan(&fileID); err != nil {
		return 0, err
	}
	role := fileRole(file.OriginalName)
	if err := tx.QueryRow(ctx, `INSERT INTO mission_files(mission_id,file_object_id,role,provenance,uploaded_by) VALUES($1,$2,$3,$4,$5) RETURNING id`, missionID, fileID, role, fileProvenance(role), owner).Scan(&missionFileID); err != nil {
		return 0, err
	}
	if err := addActivityTx(ctx, tx, missionID, "FILE_UPLOADED", "File uploaded", "MISSION_FILE", fmt.Sprint(missionFileID)); err != nil {
		return 0, err
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	return missionFileID, nil
}
func (s *Store) BindUploadIDs(ctx context.Context, tx pgx.Tx, owner, missionID int64, uploadIDs []string) error {
	for _, uploadID := range uploadIDs {
		var fileID int64
		var missionFileID int64
		var originalName string
		if err := tx.QueryRow(ctx, `SELECT file_object_id,fo.original_name FROM uploads u JOIN file_objects fo ON fo.id=u.file_object_id WHERE u.id=$1 AND u.owner_user_id=$2 AND u.status='TEMPORARY' AND u.expires_at>now() FOR UPDATE`, uploadID, owner).Scan(&fileID, &originalName); err != nil {
			return fmt.Errorf("upload %s unavailable: %w", uploadID, err)
		}
		role := fileRole(originalName)
		if err := tx.QueryRow(ctx, `INSERT INTO mission_files(mission_id,file_object_id,role,provenance,uploaded_by) VALUES($1,$2,$3,$4,$5) RETURNING id`, missionID, fileID, role, fileProvenance(role), owner).Scan(&missionFileID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE uploads SET status='BOUND' WHERE id=$1`, uploadID); err != nil {
			return err
		}
		if err := addActivityTxIdempotent(ctx, tx, missionID, "MISSION_FILE_BOUND", "File bound to mission", "MISSION_FILE", fmt.Sprint(missionFileID), "mission-file:"+fmt.Sprint(missionFileID)+":bound"); err != nil {
			return err
		}
	}
	return nil
}
func fileRole(name string) string {
	lower := strings.ToLower(name)
	if strings.HasSuffix(lower, ".pptx") {
		return "TEMPLATE"
	}
	if strings.HasSuffix(lower, ".docx") {
		return "TEACHING_PLAN"
	}
	return "MATERIAL"
}

func fileProvenance(role string) string {
	if role == "MATERIAL" {
		return "MATERIAL"
	}
	return "TEACHER"
}

func (s *Store) CreateMissionAtomic(ctx context.Context, owner int64, title, description, firstMessage string, uploadIDs []string, connectionID *int64) (int64, int64, string, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return 0, 0, "", err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	snapshot := waitingSnapshot()
	if connectionID != nil {
		identity, err := snapshotForTx(ctx, tx, owner, connectionID, true)
		if err != nil {
			return 0, 0, "", err
		}
		snapshot = runnableAgentSnapshot(identity)
	}
	var missionID int64
	if err := tx.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title,description,selected_model_connection_id) VALUES($1,'SELF_CREATED',$2,$3,$4) RETURNING id`, owner, title, description, connectionID).Scan(&missionID); err != nil {
		return 0, 0, "", err
	}
	if err := addActivityTx(ctx, tx, missionID, "MISSION_CREATED", "Mission created", "MISSION", fmt.Sprint(missionID)); err != nil {
		return 0, 0, "", err
	}
	if connectionID != nil {
		if err := addActivityTx(ctx, tx, missionID, "MODEL_CONNECTION_SELECTED", "Model connection selected", "MODEL_CONNECTION", fmt.Sprint(*connectionID)); err != nil {
			return 0, 0, "", err
		}
	}
	if err := s.BindUploadIDs(ctx, tx, owner, missionID, uploadIDs); err != nil {
		return 0, 0, "", err
	}
	var messageID int64
	if err := tx.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content,message_type) VALUES($1,'USER',$2,'TEXT') RETURNING id`, missionID, firstMessage).Scan(&messageID); err != nil {
		return 0, 0, "", err
	}
	if err := addActivityTx(ctx, tx, missionID, "MESSAGE_CREATED", "Teacher sent the first request", "MESSAGE", fmt.Sprint(messageID)); err != nil {
		return 0, 0, "", err
	}
	runID := uuid.NewString()
	encoded, err := json.Marshal(snapshot)
	if err != nil {
		return 0, 0, "", fmt.Errorf("encode agent run snapshot: %w", err)
	}
	status := "WAITING_INPUTS"
	if connectionID != nil && missionFilesReadyTx(ctx, tx, missionID) {
		status = "QUEUED"
	}
	if _, err := tx.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_connection_id,model_identity_snapshot,status) VALUES($1,$2,$3,$4,$5,$6)`, runID, missionID, messageID, connectionID, encoded, status); err != nil {
		return 0, 0, "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, 0, "", err
	}
	return missionID, messageID, runID, nil
}
func (s *Store) CreateMessageAndRun(ctx context.Context, owner, missionID int64, content string) (int64, string, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return 0, "", err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	msgID, runID, err := s.createMessageAndRunTx(ctx, tx, owner, missionID, content)
	if err != nil {
		return 0, "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, "", err
	}
	return msgID, runID, nil
}

func (s *Store) createMessageAndRunTx(ctx context.Context, tx pgx.Tx, owner, missionID int64, content string) (int64, string, error) {
	var selected *int64
	if err := tx.QueryRow(ctx, `SELECT selected_model_connection_id FROM missions WHERE id=$1 AND owner_teacher_id=$2 FOR UPDATE`, missionID, owner).Scan(&selected); err != nil {
		return 0, "", err
	}
	snapshot := waitingSnapshot()
	if selected != nil {
		identity, err := snapshotForTx(ctx, tx, owner, selected, true)
		if err != nil {
			return 0, "", err
		}
		snapshot = runnableAgentSnapshot(identity)
	}
	var msgID int64
	if err := tx.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content,message_type) VALUES($1,'USER',$2,'TEXT') RETURNING id`, missionID, content).Scan(&msgID); err != nil {
		return 0, "", err
	}
	runID := uuid.NewString()
	encoded, err := json.Marshal(snapshot)
	if err != nil {
		return 0, "", fmt.Errorf("encode agent run snapshot: %w", err)
	}
	status := "WAITING_INPUTS"
	if selected != nil && missionFilesReadyTx(ctx, tx, missionID) {
		status = "QUEUED"
	}
	if _, err := tx.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_connection_id,model_identity_snapshot,status) VALUES($1,$2,$3,$4,$5,$6)`, runID, missionID, msgID, selected, encoded, status); err != nil {
		return 0, "", err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO activity_events(mission_id,event_type,summary,reference_type,reference_id) VALUES($1,'MESSAGE_CREATED','Teacher sent a message','MESSAGE',$2)`, missionID, fmt.Sprint(msgID)); err != nil {
		return 0, "", err
	}
	return msgID, runID, nil
}
func (s *Store) requireMissionOwner(ctx context.Context, owner, missionID int64) error {
	var ok bool
	err := s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM missions WHERE id=$1 AND owner_teacher_id=$2)`, missionID, owner).Scan(&ok)
	if err != nil {
		return err
	}
	if !ok {
		return pgx.ErrNoRows
	}
	return nil
}
func (s *Store) MissionOwner(ctx context.Context, missionID int64) (int64, error) {
	var owner int64
	err := s.DB.QueryRow(ctx, `SELECT owner_teacher_id FROM missions WHERE id=$1`, missionID).Scan(&owner)
	return owner, err
}

func (s *Store) applyMissionProgress(ctx context.Context, owner, missionID int64, m *model.Mission) error {
	var facts missionprogress.Facts
	var latestRunID *string
	err := s.DB.QueryRow(ctx, `
		SELECT
			m.status,
			(m.selected_model_connection_id IS NOT NULL),
			(SELECT a.id::text FROM agent_runs a WHERE a.mission_id=m.id ORDER BY a.created_at DESC,a.id DESC LIMIT 1),
			COALESCE((SELECT a.status FROM agent_runs a WHERE a.mission_id=m.id ORDER BY a.created_at DESC,a.id DESC LIMIT 1),''),
			EXISTS(SELECT 1 FROM questions q WHERE q.mission_id=m.id AND NOT EXISTS (SELECT 1 FROM question_answers qa WHERE qa.question_id=q.id)),
			EXISTS(SELECT 1 FROM planning_drafts d WHERE d.mission_id=m.id),
			EXISTS(SELECT 1 FROM locked_specifications ls WHERE ls.mission_id=m.id),
			COALESCE((SELECT g.status FROM generation_jobs g WHERE g.mission_id=m.id ORDER BY g.created_at DESC,g.id DESC LIMIT 1),''),
			EXISTS(SELECT 1 FROM artifacts a JOIN generation_jobs g ON g.id=a.generation_job_id WHERE a.mission_id=m.id AND a.status='READY' AND g.status='SUCCEEDED')
		FROM missions m
		WHERE m.id=$1 AND m.owner_teacher_id=$2`, missionID, owner).Scan(
		&facts.MissionStatus,
		&facts.HasSelectedModel,
		&latestRunID,
		&facts.LatestAgentRunStatus,
		&facts.HasPendingQuestion,
		&facts.HasPlanningDraft,
		&facts.HasLockedSpec,
		&facts.GenerationStatus,
		&facts.HasReadyArtifact,
	)
	if err != nil {
		return err
	}
	facts.LatestAgentRunID = latestRunID
	projected := missionprogress.Project(facts)
	m.Progress = projected.Progress
	m.ProgressLabel = projected.ProgressLabel
	m.ProgressStage = projected.ProgressStage
	m.ActiveAgentRunID = projected.ActiveAgentRunID
	return nil
}

func (s *Store) requireMissionReviewer(ctx context.Context, reviewer, missionID int64) error {
	var allowed bool
	err := s.DB.QueryRow(ctx, `SELECT EXISTS(
		SELECT 1
		FROM missions m
		JOIN users u ON u.id=m.dispatcher_id
		WHERE m.id=$1 AND m.dispatcher_id=$2 AND u.role='RESEARCHER' AND u.status='ACTIVE'
		  AND m.status IN ('SUBMITTED','COMPLETED','FEEDBACK')
	)`, missionID, reviewer).Scan(&allowed)
	if err != nil {
		return err
	}
	if !allowed {
		return pgx.ErrNoRows
	}
	return nil
}

func (s *Store) GetMission(ctx context.Context, owner, id int64) (model.Mission, error) {
	var m model.Mission
	err := s.DB.QueryRow(ctx, `SELECT id,owner_teacher_id,source,title,description,deadline,status,selected_model_connection_id,created_at,updated_at FROM missions WHERE id=$1 AND owner_teacher_id=$2`, id, owner).Scan(&m.ID, &m.OwnerTeacherID, &m.Source, &m.Title, &m.Description, &m.Deadline, &m.Status, &m.SelectedModelConnectionID, &m.CreatedAt, &m.UpdatedAt)
	if err == nil {
		err = s.applyMissionProgress(ctx, owner, id, &m)
	}
	return m, err
}
func (s *Store) ListMissions(ctx context.Context, owner int64) ([]model.Mission, error) {
	rows, err := s.DB.Query(ctx, `SELECT id,owner_teacher_id,source,title,description,deadline,status,selected_model_connection_id,created_at,updated_at FROM missions WHERE owner_teacher_id=$1 ORDER BY updated_at DESC,id DESC`, owner)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	out := make([]model.Mission, 0)
	for rows.Next() {
		var m model.Mission
		if err := rows.Scan(&m.ID, &m.OwnerTeacherID, &m.Source, &m.Title, &m.Description, &m.Deadline, &m.Status, &m.SelectedModelConnectionID, &m.CreatedAt, &m.UpdatedAt); err != nil {
			return nil, err
		}
		if err := s.applyMissionProgress(ctx, owner, m.ID, &m); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) ListReviewMissions(ctx context.Context, reviewer int64) ([]model.Mission, error) {
	rows, err := s.DB.Query(ctx, `SELECT id,owner_teacher_id,source,title,description,deadline,status,selected_model_connection_id,created_at,updated_at
		FROM missions
		WHERE dispatcher_id=$1 AND status IN ('SUBMITTED','COMPLETED','FEEDBACK')
		ORDER BY updated_at DESC,id DESC`, reviewer)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close review missions", rows)
	out := make([]model.Mission, 0)
	for rows.Next() {
		var m model.Mission
		if err := rows.Scan(&m.ID, &m.OwnerTeacherID, &m.Source, &m.Title, &m.Description, &m.Deadline, &m.Status, &m.SelectedModelConnectionID, &m.CreatedAt, &m.UpdatedAt); err != nil {
			return nil, err
		}
		if err := s.applyMissionProgress(ctx, m.OwnerTeacherID, m.ID, &m); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) GetMissionForReviewer(ctx context.Context, reviewer, id int64) (model.Mission, error) {
	if err := s.requireMissionReviewer(ctx, reviewer, id); err != nil {
		return model.Mission{}, err
	}
	var m model.Mission
	err := s.DB.QueryRow(ctx, `SELECT id,owner_teacher_id,source,title,description,deadline,status,selected_model_connection_id,created_at,updated_at
		FROM missions WHERE id=$1`, id).Scan(&m.ID, &m.OwnerTeacherID, &m.Source, &m.Title, &m.Description, &m.Deadline, &m.Status, &m.SelectedModelConnectionID, &m.CreatedAt, &m.UpdatedAt)
	if err == nil {
		err = s.applyMissionProgress(ctx, m.OwnerTeacherID, id, &m)
	}
	return m, err
}

func (s *Store) CurrentDraftForReviewer(ctx context.Context, reviewer, missionID int64) (model.PlanningDraft, error) {
	if err := s.requireMissionReviewer(ctx, reviewer, missionID); err != nil {
		return model.PlanningDraft{}, err
	}
	return s.currentDraftForMission(ctx, missionID)
}
func (s *Store) SetMissionConnection(ctx context.Context, owner, missionID int64, connectionID *int64) (string, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var previous *int64
	if err := tx.QueryRow(ctx, `SELECT selected_model_connection_id FROM missions WHERE id=$1 AND owner_teacher_id=$2 FOR UPDATE`, missionID, owner).Scan(&previous); err != nil {
		return "", err
	}
	if connectionID != nil {
		var ok bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM model_connections WHERE id=$1 AND owner_user_id=$2 AND enabled=true AND verification_status='VERIFIED')`, *connectionID, owner).Scan(&ok); err != nil {
			return "", err
		}
		if !ok {
			return "", errors.New("MODEL_CONNECTION_UNAVAILABLE")
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE missions SET selected_model_connection_id=$1,updated_at=now() WHERE id=$2 AND owner_teacher_id=$3`, connectionID, missionID, owner); err != nil {
		return "", err
	}
	selectionChanged := (previous == nil && connectionID != nil) || (previous != nil && (connectionID == nil || *previous != *connectionID))
	if selectionChanged {
		if connectionID == nil {
			if _, err := tx.Exec(ctx, `UPDATE agent_runs SET status='CANCELLED',error_code='AGENT_RUN_SUPERSEDED',error_message='Model connection selection cleared',finished_at=COALESCE(finished_at,now()),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE mission_id=$1 AND status IN ('WAITING_INPUTS','QUEUED')`, missionID); err != nil {
				return "", err
			}
		} else if _, err := tx.Exec(ctx, `UPDATE agent_runs SET status='CANCELLED',error_code='AGENT_RUN_SUPERSEDED',error_message='Model connection selection changed',finished_at=COALESCE(finished_at,now()),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE mission_id=$1 AND status IN ('WAITING_INPUTS','QUEUED') AND (model_connection_id IS NULL OR model_connection_id<>$2)`, missionID, *connectionID); err != nil {
			return "", err
		}
	}

	// A run created without a connection cannot be repaired in place. Retire it
	// and create a new run from the same user message with a frozen snapshot.
	var oldRunID string
	var triggerID *int64
	var oldConnection *int64
	var oldStatus string
	err = tx.QueryRow(ctx, `SELECT a.id,a.triggering_message_id,a.model_connection_id,a.status
		FROM agent_runs a WHERE a.mission_id=$1 ORDER BY a.created_at DESC LIMIT 1`, missionID).Scan(&oldRunID, &triggerID, &oldConnection, &oldStatus)
	newRunID := ""
	if err == nil && connectionID != nil && (oldStatus == "WAITING_INPUTS" || oldStatus == "QUEUED" || oldStatus == "CANCELLED") {
		needsReplacement := oldConnection == nil || previous == nil || *oldConnection != *connectionID
		if needsReplacement {
			if oldStatus == "WAITING_INPUTS" || oldStatus == "QUEUED" {
				if _, err := tx.Exec(ctx, `UPDATE agent_runs SET status='CANCELLED',error_code='AGENT_RUN_SUPERSEDED',error_message='Connection selected after run creation',finished_at=now(),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$1 AND status IN ('WAITING_INPUTS','QUEUED')`, oldRunID); err != nil {
					return "", err
				}
			}
			identity, err := snapshotForTx(ctx, tx, owner, connectionID, true)
			if err != nil {
				return "", err
			}
			snapshot := runnableAgentSnapshot(identity)
			encoded, err := json.Marshal(snapshot)
			if err != nil {
				return "", fmt.Errorf("encode replacement agent run snapshot: %w", err)
			}
			ready := missionFilesReadyTx(ctx, tx, missionID)
			status := "WAITING_INPUTS"
			if ready {
				status = "QUEUED"
			}
			newRunID = uuid.NewString()
			if _, err := tx.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_connection_id,model_identity_snapshot,status) VALUES($1,$2,$3,$4,$5,$6)`, newRunID, missionID, triggerID, connectionID, encoded, status); err != nil {
				return "", err
			}
		}
	} else if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return "", err
	}
	if selectionChanged {
		eventType, summary, referenceID := "MODEL_CONNECTION_SELECTED", "Model connection selected", ""
		if connectionID == nil {
			eventType, summary = "MODEL_CONNECTION_CLEARED", "Model connection selection cleared"
		} else {
			referenceID = fmt.Sprint(*connectionID)
		}
		if err := addActivityTx(ctx, tx, missionID, eventType, summary, "MODEL_CONNECTION", referenceID); err != nil {
			return "", err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return "", err
	}
	return newRunID, nil
}
func (s *Store) Messages(ctx context.Context, owner, missionID int64) ([]model.Message, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT mm.id,mm.mission_id,m.owner_teacher_id,mm.agent_run_id,COALESCE(mm.output_stage,''),mm.role,mm.content,mm.message_type,mm.structured_payload,mm.created_at,CASE WHEN mm.output_stage='QUESTION_MESSAGE' THEN 'QUESTION' ELSE 'MESSAGE' END,COALESCE(q.id::text,mm.id::text) FROM mission_messages mm JOIN missions m ON m.id=mm.mission_id LEFT JOIN questions q ON q.mission_id=mm.mission_id AND q.agent_run_id=mm.agent_run_id AND q.output_stage='QUESTION' WHERE mm.mission_id=$1 ORDER BY mm.created_at,mm.id`, missionID)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	var out []model.Message
	for rows.Next() {
		var m model.Message
		var payload []byte
		if err := rows.Scan(&m.ID, &m.MissionID, &m.OwnerUserID, &m.AgentRunID, &m.OutputStage, &m.Role, &m.Content, &m.MessageType, &payload, &m.CreatedAt, &m.ReferenceType, &m.ReferenceID); err != nil {
			return nil, err
		}
		if len(payload) > 0 {
			var v any
			if err := json.Unmarshal(payload, &v); err != nil {
				return nil, fmt.Errorf("decode message payload: %w", err)
			}
			m.StructuredPayload = v
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) ConversationSummary(ctx context.Context, owner, missionID int64) (model.ConversationSummary, error) {
	var summary model.ConversationSummary
	var sourceRunID *string
	err := s.DB.QueryRow(ctx, `
		SELECT cs.mission_id,m.owner_teacher_id,cs.summary_version,
		       cs.summarized_through_message_id,cs.summary,cs.source_agent_run_id,
		       cs.prompt_version,cs.created_at,cs.updated_at
		FROM conversation_summaries cs
		JOIN missions m ON m.id=cs.mission_id
		WHERE cs.mission_id=$1 AND m.owner_teacher_id=$2`, missionID, owner).Scan(
		&summary.MissionID,
		&summary.OwnerUserID,
		&summary.SummaryVersion,
		&summary.SummarizedThroughMessageID,
		&summary.Summary,
		&sourceRunID,
		&summary.PromptVersion,
		&summary.CreatedAt,
		&summary.UpdatedAt,
	)
	if err != nil {
		return summary, err
	}
	summary.SourceAgentRunID = sourceRunID
	return summary, nil
}

func (s *Store) SaveConversationSummary(ctx context.Context, owner, missionID int64, throughMessageID *int64, summaryText, sourceRunID, promptVersion string) error {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return err
	}
	if strings.TrimSpace(summaryText) == "" || strings.TrimSpace(promptVersion) == "" {
		return errors.New("CONVERSATION_SUMMARY_INVALID")
	}
	_, err := s.DB.Exec(ctx, `
		INSERT INTO conversation_summaries(
			mission_id,summary_version,summarized_through_message_id,summary,source_agent_run_id,prompt_version
		) VALUES($1,1,$2,$3,NULLIF($4,'')::uuid,$5)
		ON CONFLICT(mission_id) DO UPDATE SET
			summary_version=conversation_summaries.summary_version+1,
			summarized_through_message_id=EXCLUDED.summarized_through_message_id,
			summary=EXCLUDED.summary,
			source_agent_run_id=EXCLUDED.source_agent_run_id,
			prompt_version=EXCLUDED.prompt_version,
			updated_at=now()`, missionID, throughMessageID, strings.TrimSpace(summaryText), strings.TrimSpace(sourceRunID), strings.TrimSpace(promptVersion))
	return err
}

func (s *Store) Questions(ctx context.Context, owner, missionID int64) ([]model.Question, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT q.id,q.mission_id,m.owner_teacher_id,q.agent_run_id,COALESCE(q.output_stage,''),q.text,q.question_type,q.options_json,q.created_at,'QUESTION',q.id::text,qa.id::text,qa.selected_values_json,qa.text_answer,qa.answered_at
		FROM questions q
		JOIN missions m ON m.id=q.mission_id
		LEFT JOIN LATERAL (
			SELECT id,selected_values_json,text_answer,answered_at
			FROM question_answers
			WHERE question_id=q.id
			ORDER BY answered_at DESC,id DESC
			LIMIT 1
		) qa ON true
		WHERE q.mission_id=$1
		ORDER BY q.created_at,q.id`, missionID)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	out := make([]model.Question, 0)
	for rows.Next() {
		var q model.Question
		var options []byte
		var answerID *string
		var selectedValues []byte
		var textAnswer *string
		var answeredAt *time.Time
		if err := rows.Scan(&q.ID, &q.MissionID, &q.OwnerUserID, &q.AgentRunID, &q.OutputStage, &q.Text, &q.Type, &options, &q.CreatedAt, &q.ReferenceType, &q.ReferenceID, &answerID, &selectedValues, &textAnswer, &answeredAt); err != nil {
			return nil, err
		}
		if len(options) > 0 {
			if err := json.Unmarshal(options, &q.Options); err != nil {
				return nil, fmt.Errorf("decode question options: %w", err)
			}
		}
		if answerID != nil {
			selected := []string{}
			if len(selectedValues) > 0 && string(selectedValues) != "null" {
				if err := json.Unmarshal(selectedValues, &selected); err != nil {
					return nil, fmt.Errorf("decode question answer values: %w", err)
				}
				if selected == nil {
					selected = []string{}
				}
			}
			q.LatestAnswer = &model.QuestionAnswer{ID: *answerID, SelectedValues: selected, TextAnswer: "", AnsweredAt: *answeredAt}
			if textAnswer != nil {
				q.LatestAnswer.TextAnswer = *textAnswer
			}
		}
		out = append(out, q)
	}
	return out, rows.Err()
}
func (s *Store) MissionFiles(ctx context.Context, owner, missionID int64) ([]model.MissionFile, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT mf.id,mf.mission_id,m.owner_teacher_id,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at,mf.role,mf.provenance,mf.parse_status,mf.created_at FROM mission_files mf JOIN file_objects fo ON fo.id=mf.file_object_id JOIN missions m ON m.id=mf.mission_id WHERE mf.mission_id=$1 ORDER BY mf.created_at,mf.id`, missionID)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	var out []model.MissionFile
	for rows.Next() {
		var f model.MissionFile
		if err := rows.Scan(&f.ID, &f.MissionID, &f.OwnerUserID, &f.FileObject.ID, &f.FileObject.OriginalName, &f.FileObject.MimeType, &f.FileObject.Size, &f.FileObject.SHA256, &f.FileObject.StorageKey, &f.FileObject.CreatedAt, &f.Role, &f.Provenance, &f.ParseStatus, &f.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

// RAGProjectBinding returns the server-owned mapping for a Mission. The
// mapping is never inferred from either side's numeric identifier.
func (s *Store) RAGProjectBinding(ctx context.Context, owner, missionID int64) (int64, bool, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return 0, false, err
	}
	var projectID int64
	err := s.DB.QueryRow(ctx, `SELECT b.rag_project_id
		FROM rag_resource_bindings b
		JOIN missions m ON m.id=b.mission_id
		WHERE b.mission_id=$1 AND b.resource_type='PROJECT' AND m.owner_teacher_id=$2`, missionID, owner).Scan(&projectID)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, nil
	}
	return projectID, err == nil, err
}

func (s *Store) SaveRAGProjectBinding(ctx context.Context, owner, missionID, ragProjectID int64) error {
	if ragProjectID <= 0 {
		return errors.New("RAG_PROJECT_ID_INVALID")
	}
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return err
	}
	if _, err := s.DB.Exec(ctx, `INSERT INTO rag_resource_bindings(mission_id,rag_project_id,resource_type)
		VALUES($1,$2,'PROJECT') ON CONFLICT DO NOTHING`, missionID, ragProjectID); err != nil {
		return err
	}
	var existing int64
	if err := s.DB.QueryRow(ctx, `SELECT rag_project_id FROM rag_resource_bindings
		WHERE mission_id=$1 AND resource_type='PROJECT'`, missionID).Scan(&existing); err != nil {
		return err
	}
	if existing != ragProjectID {
		return errors.New("RAG_PROJECT_BINDING_CONFLICT")
	}
	return nil
}

// RAGMaterialBinding is owner-scoped through the Mission join and returns no
// row for another teacher's MissionFile.
func (s *Store) RAGMaterialBinding(ctx context.Context, owner, missionFileID int64) (model.RAGMaterialBinding, bool, error) {
	var binding model.RAGMaterialBinding
	err := s.DB.QueryRow(ctx, `SELECT b.mission_id,b.mission_file_id,b.rag_project_id,b.rag_material_id,b.source_sha256
		FROM rag_resource_bindings b
		JOIN mission_files mf ON mf.id=b.mission_file_id
		JOIN missions m ON m.id=mf.mission_id
		WHERE b.mission_file_id=$1 AND b.resource_type='MATERIAL' AND m.owner_teacher_id=$2`, missionFileID, owner).
		Scan(&binding.MissionID, &binding.MissionFileID, &binding.RAGProjectID, &binding.RAGMaterialID, &binding.SourceSHA256)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.RAGMaterialBinding{}, false, nil
	}
	return binding, err == nil, err
}

func (s *Store) SaveRAGMaterialBinding(ctx context.Context, owner, missionID, missionFileID, ragProjectID, ragMaterialID int64, sourceSHA256 string) error {
	if ragProjectID <= 0 || ragMaterialID <= 0 || strings.TrimSpace(sourceSHA256) == "" {
		return errors.New("RAG_MATERIAL_BINDING_INVALID")
	}
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return err
	}
	var belongs bool
	if err := s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM mission_files mf
		JOIN missions m ON m.id=mf.mission_id WHERE mf.id=$1 AND mf.mission_id=$2 AND m.owner_teacher_id=$3)`, missionFileID, missionID, owner).Scan(&belongs); err != nil {
		return err
	}
	if !belongs {
		return pgx.ErrNoRows
	}
	if _, err := s.DB.Exec(ctx, `INSERT INTO rag_resource_bindings(mission_id,mission_file_id,rag_project_id,rag_material_id,resource_type,source_sha256)
		VALUES($1,$2,$3,$4,'MATERIAL',$5) ON CONFLICT DO NOTHING`, missionID, missionFileID, ragProjectID, ragMaterialID, sourceSHA256); err != nil {
		return err
	}
	var existing model.RAGMaterialBinding
	if err := s.DB.QueryRow(ctx, `SELECT mission_id,mission_file_id,rag_project_id,rag_material_id,source_sha256
		FROM rag_resource_bindings WHERE mission_file_id=$1 AND source_sha256=$2 AND resource_type='MATERIAL'`, missionFileID, sourceSHA256).
		Scan(&existing.MissionID, &existing.MissionFileID, &existing.RAGProjectID, &existing.RAGMaterialID, &existing.SourceSHA256); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return errors.New("RAG_MATERIAL_BINDING_CONFLICT")
		}
		return err
	}
	if existing.MissionID != missionID || existing.RAGProjectID != ragProjectID || existing.RAGMaterialID != ragMaterialID || existing.SourceSHA256 != sourceSHA256 {
		return errors.New("RAG_MATERIAL_BINDING_CONFLICT")
	}
	return nil
}
func missionFilesReadyTx(ctx context.Context, tx pgx.Tx, missionID int64) bool {
	var ready bool
	if err := tx.QueryRow(ctx, `SELECT NOT EXISTS(SELECT 1 FROM mission_files WHERE mission_id=$1 AND parse_status<>'READY')`, missionID).Scan(&ready); err != nil {
		return false
	}
	return ready
}

// QueueReadyRuns is the controlled parser boundary: an external parser may
// update parse_status, and this DB-backed transition makes waiting runs
// claimable only after every attached file is READY.
func (s *Store) QueueReadyRuns(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `UPDATE agent_runs a
		SET status='QUEUED'
		FROM missions m
		WHERE a.mission_id=m.id AND a.status='WAITING_INPUTS'
		  AND a.model_connection_id IS NOT NULL
		  AND m.selected_model_connection_id=a.model_connection_id
		  AND a.model_identity_snapshot->>'connectionId'=a.model_connection_id::text
		  AND EXISTS (SELECT 1 FROM model_connections c WHERE c.id=a.model_connection_id AND c.owner_user_id=m.owner_teacher_id AND c.enabled=true AND c.verification_status='VERIFIED')
		  AND NOT EXISTS (SELECT 1 FROM questions q WHERE q.mission_id=a.mission_id AND q.agent_run_id=a.id AND q.output_stage='QUESTION')
		  AND NOT EXISTS (SELECT 1 FROM mission_files mf WHERE mf.mission_id=a.mission_id AND mf.parse_status<>'READY')`)
	return err
}

func (s *Store) PendingMissionFiles(ctx context.Context, limit int) ([]model.MissionFile, error) {
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	rows, err := s.DB.Query(ctx, `SELECT mf.id,mf.mission_id,m.owner_teacher_id,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at,mf.role,mf.provenance,mf.parse_status,mf.created_at
		FROM mission_files mf JOIN file_objects fo ON fo.id=mf.file_object_id JOIN missions m ON m.id=mf.mission_id
		WHERE mf.parse_status='PENDING' ORDER BY mf.created_at,mf.id LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	var out []model.MissionFile
	for rows.Next() {
		var f model.MissionFile
		if err := rows.Scan(&f.ID, &f.MissionID, &f.OwnerUserID, &f.FileObject.ID, &f.FileObject.OriginalName, &f.FileObject.MimeType, &f.FileObject.Size, &f.FileObject.SHA256, &f.FileObject.StorageKey, &f.FileObject.CreatedAt, &f.Role, &f.Provenance, &f.ParseStatus, &f.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

func (s *Store) SetMissionFileParseStatus(ctx context.Context, owner, missionFileID int64, status string) error {
	if status != "PENDING" && status != "READY" && status != "FAILED" {
		return errors.New("PARSE_STATUS_INVALID")
	}
	_, err := s.DB.Exec(ctx, `UPDATE mission_files mf SET parse_status=$1 FROM missions m WHERE mf.id=$2 AND mf.mission_id=m.id AND m.owner_teacher_id=$3`, status, missionFileID, owner)
	return err
}

// SaveMissionFileParseResult atomically persists the external Parser result
// and advances the mission file to READY. A READY state without a stored
// result would allow downstream retrieval to observe a false positive.
func (s *Store) SaveMissionFileParseResult(ctx context.Context, owner, missionFileID int64, result model.ParseResult) error {
	keywords, err := json.Marshal(result.Keywords)
	if err != nil {
		return fmt.Errorf("encode parser keywords: %w", err)
	}
	stages, err := json.Marshal(result.TeachingStages)
	if err != nil {
		return fmt.Errorf("encode parser teaching stages: %w", err)
	}
	sections := result.Sections
	if sections == nil {
		sections = []any{}
	}
	sectionsJSON, err := json.Marshal(sections)
	if err != nil {
		return fmt.Errorf("encode parser sections: %w", err)
	}
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { s.recordCleanupError("parser result transaction rollback", tx.Rollback(ctx)) }()
	_, err = tx.Exec(ctx, `INSERT INTO mission_file_parse_results(mission_file_id,summary,keywords_json,teaching_stages_json,analysis_text,extracted_text,page_count,sections_json) VALUES($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT (mission_file_id) DO UPDATE SET summary=EXCLUDED.summary,keywords_json=EXCLUDED.keywords_json,teaching_stages_json=EXCLUDED.teaching_stages_json,analysis_text=EXCLUDED.analysis_text,extracted_text=EXCLUDED.extracted_text,page_count=EXCLUDED.page_count,sections_json=EXCLUDED.sections_json,updated_at=now()`, missionFileID, strings.TrimSpace(result.Summary), keywords, stages, result.AnalysisText, result.ExtractedText, result.PageCount, sectionsJSON)
	if err != nil {
		return err
	}
	var missionID int64
	if err := tx.QueryRow(ctx, `UPDATE mission_files mf SET parse_status='READY' FROM missions m WHERE mf.id=$1 AND mf.mission_id=m.id AND m.owner_teacher_id=$2 RETURNING mf.mission_id`, missionFileID, owner).Scan(&missionID); err != nil {
		return errors.New("PARSER_RESULT_OWNER_MISMATCH")
	}
	parseEventPayload, err := json.Marshal(struct {
		Summary        string          `json:"summary"`
		Keywords       []string        `json:"keywords"`
		TeachingStages []string        `json:"teachingStages"`
		AnalysisText   string          `json:"analysisText"`
		ExtractedText  string          `json:"extractedText"`
		PageCount      *int            `json:"pageCount,omitempty"`
		Sections       json.RawMessage `json:"sections"`
	}{strings.TrimSpace(result.Summary), result.Keywords, result.TeachingStages, result.AnalysisText, result.ExtractedText, result.PageCount, sectionsJSON})
	if err != nil {
		return fmt.Errorf("encode parser event identity: %w", err)
	}
	parseDigest := sha256.Sum256(parseEventPayload)
	if err := addActivityTxIdempotent(ctx, tx, missionID, "FILE_PARSE_READY", "File parsing completed", "MISSION_FILE", fmt.Sprint(missionFileID), "mission-file:"+fmt.Sprint(missionFileID)+":parse-ready:"+fmt.Sprintf("%x", parseDigest)); err != nil {
		return err
	}
	if err := s.commitTx(ctx, tx, "parser-result"); err != nil {
		if !errors.Is(err, ErrCommitAmbiguous) {
			return err
		}
		found, reconcileErr := s.ReconcileMissionFileParseResult(ctx, owner, missionFileID)
		if reconcileErr == nil && found {
			return nil
		}
		if reconcileErr != nil {
			return errors.Join(err, reconcileErr)
		}
		return err
	}
	return nil
}

// ReconcileMissionFileParseResult checks the durable result after an
// uncertain commit. It is intentionally owner-scoped and only treats a
// READY file with a stored result as a successful reconciliation.
func (s *Store) ReconcileMissionFileParseResult(ctx context.Context, owner, missionFileID int64) (bool, error) {
	var found bool
	err := s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM mission_files mf JOIN missions m ON m.id=mf.mission_id JOIN mission_file_parse_results r ON r.mission_file_id=mf.id WHERE mf.id=$1 AND m.owner_teacher_id=$2 AND mf.parse_status='READY')`, missionFileID, owner).Scan(&found)
	return found, err
}

func (s *Store) AuthorizedMaterialFileIDs(ctx context.Context, owner, missionID int64) ([]int64, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	// The Agent/RAG boundary addresses a MissionFile, not the underlying
	// FileObject. These identifiers usually happen to match in a fresh
	// database, but temporary uploads and retries can make them diverge.
	rows, err := s.DB.Query(ctx, `SELECT mf.id FROM mission_files mf WHERE mf.mission_id=$1 AND mf.parse_status='READY' AND mf.role IN ('MATERIAL','TEACHING_PLAN') AND mf.provenance IN ('TEACHER','MATERIAL') ORDER BY mf.id`, missionID)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

func (s *Store) AuthorizedMaterialFile(ctx context.Context, owner, missionID, fileID int64) error {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return err
	}
	var ok bool
	err := s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM mission_files mf JOIN file_objects fo ON fo.id=mf.file_object_id WHERE mf.mission_id=$1 AND fo.id=$2 AND fo.owner_user_id=$3 AND mf.parse_status='READY' AND mf.role IN ('MATERIAL','TEACHING_PLAN') AND mf.provenance IN ('TEACHER','MATERIAL'))`, missionID, fileID, owner).Scan(&ok)
	if err != nil {
		return err
	}
	if !ok {
		return pgx.ErrNoRows
	}
	return nil
}

// RecoverAgentRuns resets only stale AgentRun leases. It intentionally does
// not touch generation_jobs: the upstream Mission-to-Locked service must be
// able to start without entering the downstream Generation boundary.
func (s *Store) RecoverAgentRuns(ctx context.Context) error {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	if _, err = tx.Exec(ctx, `UPDATE agent_runs a SET status=CASE WHEN a.model_connection_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mission_files mf WHERE mf.mission_id=a.mission_id AND mf.parse_status<>'READY') THEN 'QUEUED' ELSE 'WAITING_INPUTS' END,started_at=NULL,lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE status='RUNNING' AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp())`); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// RecoverInterrupted is kept as a compatibility alias for older callers. It
// now has the upstream-safe AgentRun-only semantics; Generation recovery is a
// separate downstream concern and is never performed by the Agent worker.
func (s *Store) RecoverInterrupted(ctx context.Context) error {
	return s.RecoverAgentRuns(ctx)
}

func (s *Store) CleanupExpiredUploads(ctx context.Context) ([]string, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var keys []string
	var fileObjectIDs []int64
	var fileObjectKeys = map[int64]string{}
	rows, err := tx.Query(ctx, `SELECT u.id::text, u.file_object_id, fo.storage_key
		FROM uploads u JOIN file_objects fo ON fo.id=u.file_object_id
		WHERE u.status='TEMPORARY' AND u.expires_at<=now()
		FOR UPDATE OF u,fo SKIP LOCKED`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var uploadID string
		var fileObjectID int64
		var key string
		if err := rows.Scan(&uploadID, &fileObjectID, &key); err != nil {
			s.closeRows("rows close cleanup expired uploads", rows)
			return nil, err
		}
		fileObjectIDs = append(fileObjectIDs, fileObjectID)
		fileObjectKeys[fileObjectID] = key
	}
	if err := rows.Err(); err != nil {
		s.closeRows("rows close cleanup expired uploads", rows)
		return nil, err
	}
	s.closeRows("rows close cleanup expired uploads", rows)
	// Keep the database rows for a physically deletable object until the
	// caller has removed the storage key. This makes a failed physical delete
	// retryable on the next maintenance pass instead of creating an untracked
	// orphan. Expired upload rows whose object is still referenced can be
	// removed immediately because the referenced object remains protected.
	seen := make(map[int64]struct{}, len(fileObjectIDs))
	for _, fileObjectID := range fileObjectIDs {
		if _, ok := seen[fileObjectID]; ok {
			continue
		}
		seen[fileObjectID] = struct{}{}
		var protected bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (
			SELECT 1 FROM mission_files WHERE file_object_id=$1
			UNION ALL
			SELECT 1 FROM uploads WHERE file_object_id=$1 AND (status<>'TEMPORARY' OR expires_at>now())
			UNION ALL
			SELECT 1 FROM artifacts WHERE file_object_id=$1
		)`, fileObjectID).Scan(&protected); err != nil {
			return nil, err
		}
		if protected {
			if _, err := tx.Exec(ctx, `DELETE FROM uploads WHERE file_object_id=$1 AND status='TEMPORARY' AND expires_at<=now()`, fileObjectID); err != nil {
				return nil, err
			}
			continue
		}
		keys = append(keys, fileObjectKeys[fileObjectID])
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return keys, nil
}

// FinalizeExpiredUpload removes the database metadata only after the caller
// has successfully attempted physical storage cleanup. The operation is
// intentionally idempotent: a process crash after the physical delete can
// retry the same key, while a failed delete leaves the temporary rows intact.
func (s *Store) FinalizeExpiredUpload(ctx context.Context, storageKey string) error {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var fileObjectID int64
	err = tx.QueryRow(ctx, `SELECT id FROM file_objects WHERE storage_key=$1 FOR UPDATE`, storageKey).Scan(&fileObjectID)
	if errors.Is(err, pgx.ErrNoRows) {
		return tx.Commit(ctx)
	}
	if err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM uploads WHERE file_object_id=$1 AND status='TEMPORARY' AND expires_at<=now()`, fileObjectID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM file_objects fo
		WHERE fo.id=$1
		  AND NOT EXISTS (SELECT 1 FROM mission_files mf WHERE mf.file_object_id=fo.id)
		  AND NOT EXISTS (SELECT 1 FROM uploads u WHERE u.file_object_id=fo.id)
		  AND NOT EXISTS (SELECT 1 FROM artifacts a WHERE a.file_object_id=fo.id)`, fileObjectID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Store) AddActivity(ctx context.Context, missionID int64, eventType, summary, refType, refID string) error {
	_, err := s.DB.Exec(ctx, `INSERT INTO activity_events(mission_id,event_type,summary,reference_type,reference_id) VALUES($1,$2,$3,NULLIF($4,''),NULLIF($5,''))`, missionID, eventType, summary, refType, refID)
	return err
}
func addActivityTx(ctx context.Context, tx pgx.Tx, missionID int64, eventType, summary, refType, refID string) error {
	_, err := tx.Exec(ctx, `INSERT INTO activity_events(mission_id,event_type,summary,reference_type,reference_id) VALUES($1,$2,$3,NULLIF($4,''),NULLIF($5,''))`, missionID, eventType, summary, refType, refID)
	return err
}

func addActivityTxIdempotent(ctx context.Context, tx pgx.Tx, missionID int64, eventType, summary, refType, refID, key string) error {
	_, err := tx.Exec(ctx, `INSERT INTO activity_events(mission_id,event_type,summary,reference_type,reference_id,idempotency_key) VALUES($1,$2,$3,NULLIF($4,''),NULLIF($5,''),$6) ON CONFLICT (mission_id,idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING`, missionID, eventType, summary, refType, refID, key)
	return err
}
func (s *Store) Events(ctx context.Context, owner, missionID int64, after int64) ([]model.ActivityEvent, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT id,mission_id,event_type,summary,reference_type,reference_id,created_at FROM activity_events WHERE mission_id=$1 AND id>$2 ORDER BY id`, missionID, after)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	var out []model.ActivityEvent
	for rows.Next() {
		var e model.ActivityEvent
		if err := rows.Scan(&e.ID, &e.MissionID, &e.EventType, &e.Summary, &e.ReferenceType, &e.ReferenceID, &e.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

func decodeMissionFeedbackRows(s *Store, rows pgx.Rows) ([]model.MissionFeedback, error) {
	defer s.closeRows("rows close mission feedback", rows)
	out := make([]model.MissionFeedback, 0)
	for rows.Next() {
		var item model.MissionFeedback
		var raw []byte
		if err := rows.Scan(&item.ID, &item.MissionID, &item.ReviewerUserID, &item.ReviewerName, &item.SubmissionVersion, &item.Rating, &item.Summary, &raw, &item.CreatedAt); err != nil {
			return nil, err
		}
		item.Items = make([]model.MissionFeedbackItem, 0)
		if len(raw) > 0 && string(raw) != "null" {
			if err := json.Unmarshal(raw, &item.Items); err != nil {
				return nil, fmt.Errorf("decode mission feedback items: %w", err)
			}
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func (s *Store) MissionFeedback(ctx context.Context, owner, missionID int64) ([]model.MissionFeedback, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT f.id,f.mission_id,f.reviewer_user_id,u.name,f.submission_version,f.rating,f.summary,f.items_json,f.created_at
		FROM mission_feedback f JOIN users u ON u.id=f.reviewer_user_id
		WHERE f.mission_id=$1 ORDER BY f.created_at DESC,f.id DESC`, missionID)
	if err != nil {
		return nil, err
	}
	return decodeMissionFeedbackRows(s, rows)
}

func (s *Store) ReviewMissionFeedback(ctx context.Context, reviewer, missionID int64) ([]model.MissionFeedback, error) {
	if err := s.requireMissionReviewer(ctx, reviewer, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT f.id,f.mission_id,f.reviewer_user_id,u.name,f.submission_version,f.rating,f.summary,f.items_json,f.created_at
		FROM mission_feedback f JOIN users u ON u.id=f.reviewer_user_id
		WHERE f.mission_id=$1 ORDER BY f.created_at DESC,f.id DESC`, missionID)
	if err != nil {
		return nil, err
	}
	return decodeMissionFeedbackRows(s, rows)
}

func validateMissionFeedback(submissionVersion, rating int, summary string, items []model.MissionFeedbackItem) error {
	if submissionVersion <= 0 || rating < 1 || rating > 5 || len(items) > 50 {
		return errors.New("FEEDBACK_INPUT_INVALID")
	}
	if strings.TrimSpace(summary) == "" || len([]rune(summary)) > 8000 {
		return errors.New("FEEDBACK_SUMMARY_INVALID")
	}
	for _, item := range items {
		if item.SlideNumber < 0 || strings.TrimSpace(item.SlideTitle) == "" || strings.TrimSpace(item.Comment) == "" || len([]rune(item.Comment)) > 2000 {
			return errors.New("FEEDBACK_ITEM_INVALID")
		}
		if item.Severity != "INFO" && item.Severity != "IMPORTANT" && item.Severity != "BLOCKING" {
			return errors.New("FEEDBACK_ITEM_INVALID")
		}
	}
	return nil
}

func (s *Store) CreateMissionFeedback(ctx context.Context, reviewer, missionID int64, submissionVersion, rating int, summary string, items []model.MissionFeedbackItem) (model.MissionFeedback, error) {
	if err := validateMissionFeedback(submissionVersion, rating, summary, items); err != nil {
		return model.MissionFeedback{}, err
	}
	encoded, err := json.Marshal(items)
	if err != nil {
		return model.MissionFeedback{}, err
	}
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return model.MissionFeedback{}, err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var status string
	if err := tx.QueryRow(ctx, `SELECT m.status FROM missions m JOIN users u ON u.id=m.dispatcher_id
		WHERE m.id=$1 AND m.dispatcher_id=$2 AND u.role='RESEARCHER' AND u.status='ACTIVE'
		  AND m.status IN ('SUBMITTED','COMPLETED','FEEDBACK') FOR UPDATE`, missionID, reviewer).Scan(&status); err != nil {
		return model.MissionFeedback{}, err
	}
	var feedback model.MissionFeedback
	if err := tx.QueryRow(ctx, `INSERT INTO mission_feedback(id,mission_id,reviewer_user_id,submission_version,rating,summary,items_json)
		VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING id,created_at`, uuid.NewString(), missionID, reviewer, submissionVersion, rating, strings.TrimSpace(summary), encoded).Scan(&feedback.ID, &feedback.CreatedAt); err != nil {
		return model.MissionFeedback{}, err
	}
	if status != "FEEDBACK" {
		if _, err := tx.Exec(ctx, `UPDATE missions SET status='FEEDBACK',updated_at=clock_timestamp() WHERE id=$1`, missionID); err != nil {
			return model.MissionFeedback{}, err
		}
	}
	if err := addActivityTx(ctx, tx, missionID, "REVIEW_FEEDBACK_RECEIVED", fmt.Sprintf("Researcher feedback received for submission v%d", submissionVersion), "MISSION_FEEDBACK", feedback.ID); err != nil {
		return model.MissionFeedback{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return model.MissionFeedback{}, err
	}
	feedback.MissionID = missionID
	feedback.ReviewerUserID = reviewer
	feedback.SubmissionVersion = submissionVersion
	feedback.Rating = rating
	feedback.Summary = strings.TrimSpace(summary)
	feedback.Items = items
	return feedback, nil
}

func (s *Store) ClaimAgent(ctx context.Context) (model.AgentRun, error) {
	var r model.AgentRun
	var snap []byte
	var connectionID *int64
	var started time.Time
	workerID, leaseSeconds := s.leaseSettings()
	token := uuid.NewString()
	var errorCode, errorMessage *string
	err := s.DB.QueryRow(ctx, `WITH candidate AS (SELECT a.id FROM agent_runs a JOIN missions m ON m.id=a.mission_id WHERE (a.status='QUEUED' OR (a.status='RUNNING' AND a.lease_expires_at IS NOT NULL AND a.lease_expires_at<=clock_timestamp())) AND a.model_connection_id IS NOT NULL AND m.selected_model_connection_id=a.model_connection_id AND a.model_identity_snapshot->>'connectionId'=a.model_connection_id::text AND EXISTS (SELECT 1 FROM model_connections c WHERE c.id=a.model_connection_id AND c.owner_user_id=m.owner_teacher_id AND c.enabled=true AND c.verification_status='VERIFIED') AND NOT EXISTS (SELECT 1 FROM mission_files mf WHERE mf.mission_id=a.mission_id AND mf.parse_status<>'READY') ORDER BY a.created_at FOR UPDATE OF a SKIP LOCKED LIMIT 1) UPDATE agent_runs a SET status='RUNNING',started_at=clock_timestamp(),lease_owner=$1,lease_token=$2,lease_expires_at=clock_timestamp()+($3 * interval '1 second'),heartbeat_at=clock_timestamp() FROM candidate c WHERE a.id=c.id RETURNING a.id,a.mission_id,a.model_connection_id,a.model_identity_snapshot,a.status,a.error_code,a.error_message,a.started_at,a.finished_at,a.created_at,a.lease_token`, workerID, token, leaseSeconds).Scan(&r.ID, &r.MissionID, &connectionID, &snap, &r.Status, &errorCode, &errorMessage, &started, &r.FinishedAt, &r.CreatedAt, &r.LeaseToken)
	if err != nil {
		return r, err
	}
	r.ModelConnectionID = connectionID
	if errorCode != nil {
		r.ErrorCode = *errorCode
	}
	if errorMessage != nil {
		r.ErrorMessage = *errorMessage
	}
	r.StartedAt = &started
	if len(snap) > 0 {
		if err := json.Unmarshal(snap, &r.ModelIdentitySnapshot); err != nil {
			return r, fmt.Errorf("decode agent run snapshot: %w", err)
		}
	}
	return r, nil
}
func (s *Store) FinishAgent(ctx context.Context, id, leaseToken, status, code, message string) error {
	result, err := s.DB.Exec(ctx, `UPDATE agent_runs SET status=$1,error_code=NULLIF($2,''),error_message=NULLIF($3,''),finished_at=clock_timestamp(),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$4 AND status='RUNNING' AND lease_token=$5 AND lease_expires_at>clock_timestamp()`, status, code, message, id, leaseToken)
	if err == nil && result.RowsAffected() == 0 {
		return errors.New("AGENT_EXECUTION_FENCE_LOST")
	}
	return err
}

func (s *Store) FinishAgentWithActivity(ctx context.Context, id, leaseToken, status, code, message, eventType, summary, refType, refID string) error {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var missionID int64
	if err := tx.QueryRow(ctx, `SELECT mission_id FROM agent_runs WHERE id=$1 FOR UPDATE`, id).Scan(&missionID); err != nil {
		return err
	}
	if eventType != "" {
		if err := addActivityTxIdempotent(ctx, tx, missionID, eventType, summary, refType, refID, "agent-run:"+id+":final:"+status); err != nil {
			return err
		}
	}
	result, err := tx.Exec(ctx, `UPDATE agent_runs SET status=$1,error_code=NULLIF($2,''),error_message=NULLIF($3,''),finished_at=clock_timestamp(),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$4 AND status='RUNNING' AND lease_token=$5 AND lease_expires_at>clock_timestamp()`, status, code, message, id, leaseToken)
	if err != nil {
		return err
	}
	if result.RowsAffected() == 0 {
		var current string
		var alreadyRecorded bool
		if err := tx.QueryRow(ctx, `SELECT status FROM agent_runs WHERE id=$1`, id).Scan(&current); err == nil && current == status {
			if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM activity_events WHERE mission_id=$2 AND idempotency_key=$1)`, "agent-run:"+id+":final:"+status, missionID).Scan(&alreadyRecorded); err == nil && alreadyRecorded {
				return s.commitTx(ctx, tx, "agent-final-reconcile")
			}
		}
		return errors.New("AGENT_EXECUTION_FENCE_LOST")
	}
	err = s.commitTx(ctx, tx, "agent-final")
	if !errors.Is(err, ErrCommitAmbiguous) {
		return err
	}
	if s.agentFinalCommitted(ctx, id, status) {
		return nil
	}
	return err
}

func (s *Store) agentFinalCommitted(ctx context.Context, runID, status string) bool {
	var current string
	if err := s.DB.QueryRow(ctx, `SELECT status FROM agent_runs WHERE id=$1`, runID).Scan(&current); err != nil || current != status {
		return false
	}
	var found bool
	if err := s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM activity_events WHERE idempotency_key=$1)`, "agent-run:"+runID+":final:"+status).Scan(&found); err != nil {
		return false
	}
	return found
}

func (s *Store) RenewAgentLease(ctx context.Context, id, leaseToken string) error {
	_, seconds := s.leaseSettings()
	result, err := s.DB.Exec(ctx, `UPDATE agent_runs SET lease_expires_at=clock_timestamp()+($1 * interval '1 second'),heartbeat_at=clock_timestamp() WHERE id=$2 AND status='RUNNING' AND lease_token=$3`, seconds, id, leaseToken)
	if err == nil && result.RowsAffected() == 0 {
		return errors.New("AGENT_EXECUTION_FENCE_LOST")
	}
	return err
}
func (s *Store) AgentCancelled(ctx context.Context, id string) (bool, error) {
	var cancelled bool
	err := s.DB.QueryRow(ctx, `SELECT status='CANCELLED' FROM agent_runs WHERE id=$1`, id).Scan(&cancelled)
	return cancelled, err
}
func (s *Store) CancelAgent(ctx context.Context, owner int64, id string) (model.AgentRun, error) {
	var r model.AgentRun
	var connectionID *int64
	var snap []byte
	var errorCode, errorMessage *string
	err := s.DB.QueryRow(ctx, `UPDATE agent_runs a SET status='CANCELLED',error_code='AGENT_CANCELLED',finished_at=now(),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL FROM missions m WHERE a.id=$1 AND a.mission_id=m.id AND m.owner_teacher_id=$2 AND a.status IN ('WAITING_INPUTS','QUEUED','RUNNING') RETURNING a.id,a.mission_id,a.model_connection_id,a.model_identity_snapshot,a.status,a.error_code,a.error_message,a.started_at,a.finished_at,a.created_at`, id, owner).Scan(&r.ID, &r.MissionID, &connectionID, &snap, &r.Status, &errorCode, &errorMessage, &r.StartedAt, &r.FinishedAt, &r.CreatedAt)
	if err != nil {
		return r, err
	}
	r.ModelConnectionID = connectionID
	if errorCode != nil {
		r.ErrorCode = *errorCode
	}
	if errorMessage != nil {
		r.ErrorMessage = *errorMessage
	}
	if len(snap) > 0 {
		if err := json.Unmarshal(snap, &r.ModelIdentitySnapshot); err != nil {
			return r, fmt.Errorf("decode agent run snapshot: %w", err)
		}
	}
	return r, nil
}
func (s *Store) AgentRuns(ctx context.Context, owner, missionID int64) ([]model.AgentRun, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT a.id,a.mission_id,m.owner_teacher_id,a.model_connection_id,a.model_identity_snapshot,a.status,a.error_code,a.error_message,a.started_at,a.finished_at,a.created_at FROM agent_runs a JOIN missions m ON m.id=a.mission_id WHERE a.mission_id=$1 ORDER BY a.created_at DESC`, missionID)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	var out []model.AgentRun
	for rows.Next() {
		var r model.AgentRun
		var snap []byte
		var errorCode, errorMessage *string
		if err := rows.Scan(&r.ID, &r.MissionID, &r.OwnerUserID, &r.ModelConnectionID, &snap, &r.Status, &errorCode, &errorMessage, &r.StartedAt, &r.FinishedAt, &r.CreatedAt); err != nil {
			return nil, err
		}
		if errorCode != nil {
			r.ErrorCode = *errorCode
		}
		if errorMessage != nil {
			r.ErrorMessage = *errorMessage
		}
		if len(snap) > 0 {
			if err := json.Unmarshal(snap, &r.ModelIdentitySnapshot); err != nil {
				return nil, fmt.Errorf("decode agent run snapshot: %w", err)
			}
		}
		out = append(out, r)
	}
	return out, rows.Err()
}
func (s *Store) AgentRunStatus(ctx context.Context, owner int64, runID string) (string, error) {
	var status string
	err := s.DB.QueryRow(ctx, `SELECT a.status FROM agent_runs a JOIN missions m ON m.id=a.mission_id WHERE a.id=$1 AND m.owner_teacher_id=$2`, runID, owner).Scan(&status)
	return status, err
}
func (s *Store) beginAgentOutputTx(ctx context.Context, missionID int64, runID, leaseToken string) (pgx.Tx, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return nil, err
	}
	var marker int
	err = tx.QueryRow(ctx, `SELECT 1 FROM agent_runs WHERE id=$1 AND mission_id=$2 AND status='RUNNING' AND lease_token=$3 AND lease_expires_at>clock_timestamp() FOR UPDATE`, runID, missionID, leaseToken).Scan(&marker)
	if err != nil {
		if rollbackErr := tx.Rollback(ctx); rollbackErr != nil && !errors.Is(rollbackErr, pgx.ErrTxClosed) {
			return nil, errors.Join(err, fmt.Errorf("agent output rollback: %w", rollbackErr))
		}
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, errors.New("AGENT_EXECUTION_FENCE_LOST")
		}
		return nil, err
	}
	if s.beforeAgentOutputWriteHook != nil {
		s.beforeAgentOutputWriteHook()
	}
	return tx, nil
}

func assertAgentLeaseTx(ctx context.Context, tx pgx.Tx, missionID int64, runID, leaseToken string) error {
	var marker int
	err := tx.QueryRow(ctx, `SELECT 1 FROM agent_runs WHERE id=$1 AND mission_id=$2 AND status='RUNNING' AND lease_token=$3 AND lease_expires_at>clock_timestamp() FOR UPDATE`, runID, missionID, leaseToken).Scan(&marker)
	if errors.Is(err, pgx.ErrNoRows) {
		return errors.New("AGENT_EXECUTION_FENCE_LOST")
	}
	return err
}

func (s *Store) existingAgentMessage(ctx context.Context, missionID int64, runID, stage string) (int64, bool, error) {
	var id int64
	err := s.DB.QueryRow(ctx, `SELECT id FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage=$3`, missionID, runID, stage).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, nil
	}
	return id, err == nil, err
}

func existingAgentMessageTx(ctx context.Context, tx pgx.Tx, missionID int64, runID, stage string) (int64, bool, error) {
	var id int64
	err := tx.QueryRow(ctx, `SELECT id FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage=$3`, missionID, runID, stage).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, nil
	}
	return id, err == nil, err
}

func (s *Store) existingAgentMessageTx(ctx context.Context, tx pgx.Tx, missionID int64, runID, stage string) (int64, bool, error) {
	return existingAgentMessageTx(ctx, tx, missionID, runID, stage)
}

func (s *Store) existingAgentMessageTxResult(ctx context.Context, tx pgx.Tx, missionID int64, runID, stage string) (int64, error) {
	id, found, err := existingAgentMessageTx(ctx, tx, missionID, runID, stage)
	if err != nil {
		return 0, err
	}
	if !found {
		return 0, errors.New("AGENT_OUTPUT_RECONCILIATION_FAILED")
	}
	return id, s.commitAgentOutputTx(ctx, tx, missionID, runID, stage, "agent-message-reconcile")
}

func (s *Store) existingAgentQuestion(ctx context.Context, missionID int64, runID string) (string, bool, error) {
	var id string
	err := s.DB.QueryRow(ctx, `SELECT id::text FROM questions WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION'`, missionID, runID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	return id, err == nil, err
}

func existingAgentQuestionTx(ctx context.Context, tx pgx.Tx, missionID int64, runID string) (string, bool, error) {
	var id string
	err := tx.QueryRow(ctx, `SELECT id::text FROM questions WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION'`, missionID, runID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	return id, err == nil, err
}

func (s *Store) existingAgentQuestionTx(ctx context.Context, tx pgx.Tx, missionID int64, runID string) (string, bool, error) {
	return existingAgentQuestionTx(ctx, tx, missionID, runID)
}

func (s *Store) existingAgentDraft(ctx context.Context, missionID int64, runID string) (model.PlanningDraft, bool, error) {
	var d model.PlanningDraft
	var plan []byte
	err := s.DB.QueryRow(ctx, `SELECT id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id,created_at FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage='PLAN_DRAFT'`, missionID, runID).Scan(&d.ID, &d.MissionID, &d.Version, &d.Markdown, &plan, &d.CreatedByAgentRunID, &d.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return d, false, nil
	}
	if err != nil {
		return d, false, err
	}
	if err := json.Unmarshal(plan, &d.StructuredPlan); err != nil {
		return d, false, fmt.Errorf("decode planning draft: %w", err)
	}
	d.OutputStage = AgentOutputStagePlan
	d.ReferenceType = "PLANNING_DRAFT"
	d.ReferenceID = d.ID
	return d, true, nil
}

func existingAgentDraftTx(ctx context.Context, tx pgx.Tx, missionID int64, runID string) (model.PlanningDraft, bool, error) {
	var d model.PlanningDraft
	var plan []byte
	err := tx.QueryRow(ctx, `SELECT id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id,created_at FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage='PLAN_DRAFT'`, missionID, runID).Scan(&d.ID, &d.MissionID, &d.Version, &d.Markdown, &plan, &d.CreatedByAgentRunID, &d.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return d, false, nil
	}
	if err != nil {
		return d, false, err
	}
	if err := json.Unmarshal(plan, &d.StructuredPlan); err != nil {
		return d, false, fmt.Errorf("decode planning draft: %w", err)
	}
	d.OutputStage = AgentOutputStagePlan
	d.ReferenceType = "PLANNING_DRAFT"
	d.ReferenceID = d.ID
	return d, true, nil
}

func (s *Store) existingAgentDraftTx(ctx context.Context, tx pgx.Tx, missionID int64, runID string) (model.PlanningDraft, bool, error) {
	return existingAgentDraftTx(ctx, tx, missionID, runID)
}

func (s *Store) commitTx(ctx context.Context, tx pgx.Tx, scope string) error {
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("%w: %v", ErrCommitAmbiguous, err)
	}
	if s.afterCommitHook != nil {
		if err := s.afterCommitHook(scope); err != nil {
			return fmt.Errorf("%w: %v", ErrCommitAmbiguous, err)
		}
	}
	return nil
}

func (s *Store) commitAgentOutputTx(ctx context.Context, tx pgx.Tx, missionID int64, runID, stage, scope string) error {
	if s.beforeAgentOutputCommitHook != nil {
		if err := s.beforeAgentOutputCommitHook(ctx, tx); err != nil {
			return fmt.Errorf("agent output commit preparation: %w", err)
		}
	}
	err := s.commitTx(ctx, tx, scope)
	if !errors.Is(err, ErrCommitAmbiguous) {
		return err
	}
	found, reconcileErr := s.ReconcileAgentOutput(ctx, missionID, runID, stage)
	if reconcileErr == nil && found {
		return nil
	}
	return err
}

func (s *Store) ReconcileAgentOutput(ctx context.Context, missionID int64, runID, stage string) (bool, error) {
	var found bool
	var err error
	switch stage {
	case AgentOutputStageMessage:
		err = s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage=$3)`, missionID, runID, stage).Scan(&found)
	case AgentOutputStageQuestion:
		err = s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM questions WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage=$3)`, missionID, runID, stage).Scan(&found)
	case AgentOutputStagePlan:
		err = s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage=$3)`, missionID, runID, stage).Scan(&found)
	default:
		return false, errors.New("AGENT_OUTPUT_STAGE_INVALID")
	}
	return found, err
}

func (s *Store) AddAssistantMessageForRun(ctx context.Context, missionID int64, runID, leaseToken, content, messageType string, payload any) (int64, error) {
	if id, found, err := s.existingAgentMessage(ctx, missionID, runID, "MESSAGE"); err != nil {
		return 0, err
	} else if found {
		return id, nil
	}
	tx, err := s.beginAgentOutputTx(ctx, missionID, runID, leaseToken)
	if err != nil {
		return 0, err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	if id, found, err := s.existingAgentMessageTx(ctx, tx, missionID, runID, "MESSAGE"); err != nil {
		return 0, err
	} else if found {
		return id, s.commitAgentOutputTx(ctx, tx, missionID, runID, AgentOutputStageMessage, "agent-message-reconcile")
	}
	if err := assertAgentLeaseTx(ctx, tx, missionID, runID, leaseToken); err != nil {
		return 0, err
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return 0, fmt.Errorf("encode assistant message payload: %w", err)
	}
	var id int64
	if err := tx.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,agent_run_id,output_stage,role,content,message_type,structured_payload) VALUES($1,$2,'MESSAGE','ASSISTANT',$3,$4,$5) RETURNING id`, missionID, runID, content, messageType, encoded).Scan(&id); err != nil {
		return 0, err
	}
	if err := addActivityTxIdempotent(ctx, tx, missionID, "MESSAGE_CREATED", "Agent response created", "MESSAGE", fmt.Sprint(id), "agent-run:"+runID+":message"); err != nil {
		return 0, err
	}
	if err := assertAgentLeaseTx(ctx, tx, missionID, runID, leaseToken); err != nil {
		return 0, err
	}
	return id, s.commitAgentOutputTx(ctx, tx, missionID, runID, AgentOutputStageMessage, "agent-message")
}

func (s *Store) SaveQuestionForRun(ctx context.Context, missionID int64, runID, leaseToken, text, qtype string, options any) (string, error) {
	if id, found, err := s.existingAgentQuestion(ctx, missionID, runID); err != nil {
		return "", err
	} else if found {
		return id, nil
	}
	tx, err := s.beginAgentOutputTx(ctx, missionID, runID, leaseToken)
	if err != nil {
		return "", err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	if id, found, err := s.existingAgentQuestionTx(ctx, tx, missionID, runID); err != nil {
		return "", err
	} else if found {
		return id, s.commitAgentOutputTx(ctx, tx, missionID, runID, AgentOutputStageQuestion, "agent-question-reconcile")
	}
	if err := assertAgentLeaseTx(ctx, tx, missionID, runID, leaseToken); err != nil {
		return "", err
	}
	encoded, err := json.Marshal(options)
	if err != nil {
		return "", fmt.Errorf("encode question options: %w", err)
	}
	var messageID int64
	if err := tx.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,agent_run_id,output_stage,role,content,message_type,structured_payload) VALUES($1,$2,'QUESTION_MESSAGE','ASSISTANT',$3,'QUESTION',$4) RETURNING id`, missionID, runID, text, encoded).Scan(&messageID); err != nil {
		return "", err
	}
	if err := addActivityTxIdempotent(ctx, tx, missionID, "MESSAGE_CREATED", "Agent response created", "MESSAGE", fmt.Sprint(messageID), "agent-run:"+runID+":question-message"); err != nil {
		return "", err
	}
	id := uuid.NewString()
	if err := tx.QueryRow(ctx, `INSERT INTO questions(id,mission_id,agent_run_id,output_stage,text,question_type,options_json) VALUES($1,$2,$3,'QUESTION',$4,$5,$6) RETURNING id::text`, id, missionID, runID, text, qtype, encoded).Scan(&id); err != nil {
		return "", err
	}
	if err := addActivityTxIdempotent(ctx, tx, missionID, "QUESTION_CREATED", "Agent asked a question", "QUESTION", id, "agent-run:"+runID+":question"); err != nil {
		return "", err
	}
	if err := assertAgentLeaseTx(ctx, tx, missionID, runID, leaseToken); err != nil {
		return "", err
	}
	return id, s.commitAgentOutputTx(ctx, tx, missionID, runID, AgentOutputStageQuestion, "agent-question")
}
func (s *Store) AnswerQuestion(ctx context.Context, owner int64, questionID, text string, selected []string) (string, string, error) {
	answerID := uuid.NewString()
	encoded, err := json.Marshal(selected)
	if err != nil {
		return "", "", fmt.Errorf("encode selected question values: %w", err)
	}
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return "", "", err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var missionID int64
	var questionType string
	var optionsJSON []byte
	if err := tx.QueryRow(ctx, `SELECT q.mission_id,q.question_type,q.options_json FROM questions q JOIN missions m ON m.id=q.mission_id WHERE q.id=$1 AND m.owner_teacher_id=$2 FOR UPDATE OF q`, questionID, owner).Scan(&missionID, &questionType, &optionsJSON); err != nil {
		return "", "", err
	}
	if err := validateQuestionAnswer(questionType, optionsJSON, text, selected); err != nil {
		return "", "", err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO question_answers(id,question_id,selected_values_json,text_answer) VALUES($1,$2,$3,$4)`, answerID, questionID, encoded, text); err != nil {
		return "", "", err
	}
	_, runID, err := s.createMessageAndRunTx(ctx, tx, owner, missionID, answerText(text, selected))
	if err != nil {
		return "", "", err
	}
	if err := addActivityTxIdempotent(ctx, tx, missionID, "QUESTION_ANSWERED", "Teacher answered a question", "QUESTION", questionID, "question-answer:"+questionID+":"+answerID); err != nil {
		return "", "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", "", err
	}
	return answerID, runID, nil
}

// validateQuestionAnswer applies the question contract again at the write
// boundary. HTTP validation alone is insufficient because callers can use
// the Store directly and the persisted Question is the authority for its
// type and option set.
func validateQuestionAnswer(questionType string, optionsJSON []byte, text string, selected []string) error {
	questionType = strings.ToUpper(strings.TrimSpace(questionType))
	options := []string{}
	if len(optionsJSON) > 0 && string(optionsJSON) != "null" {
		if err := json.Unmarshal(optionsJSON, &options); err != nil || options == nil {
			return model.ErrQuestionAnswerInvalid
		}
	}
	for _, value := range selected {
		if strings.TrimSpace(value) == "" {
			return model.ErrQuestionAnswerInvalid
		}
	}
	switch questionType {
	case "TEXT":
		if len(options) != 0 || len(selected) != 0 || strings.TrimSpace(text) == "" {
			return model.ErrQuestionAnswerInvalid
		}
		return nil
	case "SINGLE_CHOICE":
		if strings.TrimSpace(text) != "" || len(selected) != 1 {
			return model.ErrQuestionAnswerInvalid
		}
		return validateSelectedOptions(options, selected, 1)
	case "MULTI_CHOICE":
		if strings.TrimSpace(text) != "" || len(selected) < 1 {
			return model.ErrQuestionAnswerInvalid
		}
		return validateSelectedOptions(options, selected, 0)
	default:
		return model.ErrQuestionAnswerInvalid
	}
}

func validateSelectedOptions(options, selected []string, exactCount int) error {
	if len(options) < 2 || len(options) > 12 || (exactCount > 0 && len(selected) != exactCount) {
		return model.ErrQuestionAnswerInvalid
	}
	allowed := make(map[string]struct{}, len(options))
	for _, option := range options {
		if strings.TrimSpace(option) == "" {
			return model.ErrQuestionAnswerInvalid
		}
		allowed[option] = struct{}{}
	}
	seen := make(map[string]struct{}, len(selected))
	for _, value := range selected {
		if _, ok := allowed[value]; !ok {
			return model.ErrQuestionAnswerInvalid
		}
		if _, duplicate := seen[value]; duplicate {
			return model.ErrQuestionAnswerInvalid
		}
		seen[value] = struct{}{}
	}
	return nil
}
func answerText(text string, selected []string) string {
	if strings.TrimSpace(text) != "" {
		return text
	}
	return strings.Join(selected, ", ")
}
func (s *Store) SaveDraftForRun(ctx context.Context, missionID int64, runID, leaseToken, markdown string, plan any) (model.PlanningDraft, error) {
	if draft, found, err := s.existingAgentDraft(ctx, missionID, runID); err != nil {
		return model.PlanningDraft{}, err
	} else if found {
		return draft, nil
	}
	tx, err := s.beginAgentOutputTx(ctx, missionID, runID, leaseToken)
	if err != nil {
		return model.PlanningDraft{}, err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	if draft, found, err := s.existingAgentDraftTx(ctx, tx, missionID, runID); err != nil {
		return model.PlanningDraft{}, err
	} else if found {
		return draft, s.commitAgentOutputTx(ctx, tx, missionID, runID, AgentOutputStagePlan, "agent-draft-reconcile")
	}
	if err := assertAgentLeaseTx(ctx, tx, missionID, runID, leaseToken); err != nil {
		return model.PlanningDraft{}, err
	}
	if _, err := tx.Exec(ctx, `SELECT 1 FROM missions WHERE id=$1 FOR UPDATE`, missionID); err != nil {
		return model.PlanningDraft{}, err
	}
	encoded, err := json.Marshal(plan)
	if err != nil {
		return model.PlanningDraft{}, fmt.Errorf("encode planning draft: %w", err)
	}
	var version int
	if err := tx.QueryRow(ctx, `SELECT COALESCE(MAX(version),0)+1 FROM planning_drafts WHERE mission_id=$1`, missionID).Scan(&version); err != nil {
		return model.PlanningDraft{}, err
	}
	id := uuid.NewString()
	var createdAt time.Time
	if err := tx.QueryRow(ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id,output_stage) VALUES($1,$2,$3,$4,$5,$6,'PLAN_DRAFT') RETURNING created_at`, id, missionID, version, markdown, encoded, runID).Scan(&createdAt); err != nil {
		return model.PlanningDraft{}, err
	}
	if err := addActivityTxIdempotent(ctx, tx, missionID, "PLAN_DRAFT_CREATED", "Planning draft created", "PLANNING_DRAFT", id, "agent-run:"+runID+":plan-draft"); err != nil {
		return model.PlanningDraft{}, err
	}
	if err := assertAgentLeaseTx(ctx, tx, missionID, runID, leaseToken); err != nil {
		return model.PlanningDraft{}, err
	}
	if err := s.commitAgentOutputTx(ctx, tx, missionID, runID, AgentOutputStagePlan, "agent-draft"); err != nil {
		return model.PlanningDraft{}, err
	}
	return model.PlanningDraft{ID: id, MissionID: missionID, Version: version, Markdown: markdown, StructuredPlan: plan, CreatedByAgentRunID: runID, OutputStage: AgentOutputStagePlan, ReferenceType: "PLANNING_DRAFT", ReferenceID: id, CreatedAt: createdAt}, nil
}
func (s *Store) currentDraftForMission(ctx context.Context, missionID int64) (model.PlanningDraft, error) {
	var d model.PlanningDraft
	var plan []byte
	err := s.DB.QueryRow(ctx, `SELECT d.id,d.mission_id,m.owner_teacher_id,d.version,d.markdown,d.structured_plan_json,d.created_by_agent_run_id,COALESCE(d.output_stage,''),d.created_at FROM planning_drafts d JOIN missions m ON m.id=d.mission_id WHERE d.mission_id=$1 ORDER BY d.version DESC LIMIT 1`, missionID).Scan(&d.ID, &d.MissionID, &d.OwnerUserID, &d.Version, &d.Markdown, &plan, &d.CreatedByAgentRunID, &d.OutputStage, &d.CreatedAt)
	if err != nil {
		return d, err
	}
	if err := json.Unmarshal(plan, &d.StructuredPlan); err != nil {
		return d, fmt.Errorf("decode planning draft: %w", err)
	}
	d.ReferenceType = "PLANNING_DRAFT"
	d.ReferenceID = d.ID
	return d, nil
}
func (s *Store) CurrentDraft(ctx context.Context, owner, missionID int64) (model.PlanningDraft, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return model.PlanningDraft{}, err
	}
	return s.currentDraftForMission(ctx, missionID)
}
func (s *Store) Drafts(ctx context.Context, owner, missionID int64) ([]model.PlanningDraft, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT d.id,d.mission_id,m.owner_teacher_id,d.version,d.markdown,d.structured_plan_json,d.created_by_agent_run_id,COALESCE(d.output_stage,''),d.created_at FROM planning_drafts d JOIN missions m ON m.id=d.mission_id WHERE d.mission_id=$1 ORDER BY d.version DESC`, missionID)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	var out []model.PlanningDraft
	for rows.Next() {
		var d model.PlanningDraft
		var plan []byte
		if err := rows.Scan(&d.ID, &d.MissionID, &d.OwnerUserID, &d.Version, &d.Markdown, &plan, &d.CreatedByAgentRunID, &d.OutputStage, &d.CreatedAt); err != nil {
			return nil, err
		}
		if err := json.Unmarshal(plan, &d.StructuredPlan); err != nil {
			return nil, fmt.Errorf("decode planning draft: %w", err)
		}
		d.ReferenceType = "PLANNING_DRAFT"
		d.ReferenceID = d.ID
		out = append(out, d)
	}
	return out, rows.Err()
}

// ApproveDraft is the upstream boundary: it creates only the immutable
// Locked Specification. Generation is intentionally not queued here so that
// teacher approval can be audited and reviewed before any downstream worker
// is allowed to observe a new job.
func (s *Store) ApproveDraft(ctx context.Context, owner int64, draftID string) (model.LockedSpecification, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return model.LockedSpecification{}, err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var d model.PlanningDraft
	var plan []byte
	err = tx.QueryRow(ctx, `SELECT d.id,d.mission_id,d.version,d.structured_plan_json FROM planning_drafts d JOIN missions m ON m.id=d.mission_id WHERE d.id=$1 AND m.owner_teacher_id=$2 FOR UPDATE`, draftID, owner).Scan(&d.ID, &d.MissionID, &d.Version, &plan)
	if err != nil {
		return model.LockedSpecification{}, err
	}
	var existing model.LockedSpecification
	var existingRaw, existingBinding []byte
	err = tx.QueryRow(ctx, `SELECT id,mission_id,source_draft_id,version,specification_json,template_binding_json,content_hash,created_at FROM locked_specifications WHERE source_draft_id=$1 FOR UPDATE`, draftID).Scan(&existing.ID, &existing.MissionID, &existing.SourceDraftID, &existing.Version, &existingRaw, &existingBinding, &existing.ContentHash, &existing.CreatedAt)
	if err == nil {
		if err := json.Unmarshal(existingRaw, &existing.Specification); err != nil {
			return model.LockedSpecification{}, fmt.Errorf("decode locked specification: %w", err)
		}
		if err := json.Unmarshal(existingBinding, &existing.TemplateBinding); err != nil {
			return model.LockedSpecification{}, fmt.Errorf("decode template binding: %w", err)
		}
		if err := tx.Commit(ctx); err != nil {
			return model.LockedSpecification{}, err
		}
		return existing, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return model.LockedSpecification{}, err
	}
	binding, err := s.templateBindingTx(ctx, tx, d.MissionID)
	if err != nil {
		return model.LockedSpecification{}, err
	}
	var decoded any
	if err := json.Unmarshal(plan, &decoded); err != nil {
		return model.LockedSpecification{}, specification.ErrPlanInvalid
	}
	compiled, contentHash, err := specification.CompileWithSourceValidator(decoded, binding, func(ref map[string]any) error {
		return validateSourceRefTx(ctx, tx, owner, d.MissionID, ref)
	})
	if err != nil {
		return model.LockedSpecification{}, err
	}
	encoded, err := json.Marshal(compiled)
	if err != nil {
		return model.LockedSpecification{}, fmt.Errorf("encode locked specification: %w", err)
	}
	bindingEncoded, err := json.Marshal(binding)
	if err != nil {
		return model.LockedSpecification{}, fmt.Errorf("encode template binding: %w", err)
	}
	specID := uuid.NewString()
	if _, err := tx.Exec(ctx, `INSERT INTO locked_specifications(id,mission_id,source_draft_id,version,specification_json,template_binding_json,content_hash) VALUES($1,$2,$3,$4,$5,$6,$7)`, specID, d.MissionID, d.ID, d.Version, encoded, bindingEncoded, contentHash); err != nil {
		return model.LockedSpecification{}, err
	}
	if err := addActivityTx(ctx, tx, d.MissionID, "PLAN_APPROVED", "Plan approved and locked", "LOCKED_SPECIFICATION", specID); err != nil {
		return model.LockedSpecification{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return model.LockedSpecification{}, err
	}
	return model.LockedSpecification{ID: specID, MissionID: d.MissionID, SourceDraftID: d.ID, Version: d.Version, Specification: compiled, TemplateBinding: binding, ContentHash: contentHash}, nil
}

// CreateGenerationJob is the explicit post-approval generation boundary. It
// deliberately does not share ApproveDraft: approval creates only the
// immutable Locked Specification, while this transaction validates the exact
// specification requested and creates a QUEUED worker input.
func (s *Store) CreateGenerationJob(ctx context.Context, owner, missionID int64, specificationID string, specificationVersion int) (GenerationJobRequestResult, error) {
	if owner <= 0 || missionID <= 0 || strings.TrimSpace(specificationID) == "" || specificationVersion <= 0 {
		return GenerationJobRequestResult{}, ErrGenerationSpecificationNotFound
	}
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return GenerationJobRequestResult{}, err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()

	var missionOwner int64
	if err := tx.QueryRow(ctx, `SELECT owner_teacher_id FROM missions WHERE id=$1 FOR UPDATE`, missionID).Scan(&missionOwner); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return GenerationJobRequestResult{}, ErrGenerationMissionNotFound
		}
		return GenerationJobRequestResult{}, err
	}
	if missionOwner != owner {
		return GenerationJobRequestResult{}, ErrGenerationMissionNotFound
	}

	var storedVersion int
	var binding []byte
	err = tx.QueryRow(ctx, `SELECT version,template_binding_json FROM locked_specifications WHERE id=$1 AND mission_id=$2 FOR SHARE`, specificationID, missionID).Scan(&storedVersion, &binding)
	if errors.Is(err, pgx.ErrNoRows) {
		return GenerationJobRequestResult{}, ErrGenerationSpecificationNotFound
	}
	if err != nil {
		return GenerationJobRequestResult{}, err
	}
	if storedVersion != specificationVersion {
		return GenerationJobRequestResult{}, ErrGenerationVersionMismatch
	}
	if !missionFilesReadyTx(ctx, tx, missionID) {
		return GenerationJobRequestResult{}, ErrGenerationMaterialsNotReady
	}
	if err := s.validateGenerationTemplateBindingTx(ctx, tx, owner, missionID, binding); err != nil {
		return GenerationJobRequestResult{}, err
	}

	existing, err := generationJobForMissionTx(ctx, tx, missionID)
	if err == nil {
		if existing.SpecificationID != specificationID {
			return GenerationJobRequestResult{}, ErrGenerationActiveConflict
		}
		if err := tx.Commit(ctx); err != nil {
			return GenerationJobRequestResult{}, err
		}
		return GenerationJobRequestResult{Job: existing, Created: false}, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return GenerationJobRequestResult{}, err
	}

	jobID := uuid.NewString()
	if _, err := tx.Exec(ctx, `INSERT INTO generation_jobs(id,mission_id,specification_id,status) VALUES($1,$2,$3,'QUEUED')`, jobID, missionID, specificationID); err != nil {
		return GenerationJobRequestResult{}, err
	}
	if err := addActivityTx(ctx, tx, missionID, "GENERATION_REQUESTED", "PPT generation requested", "GENERATION_JOB", jobID); err != nil {
		return GenerationJobRequestResult{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return GenerationJobRequestResult{}, err
	}
	return GenerationJobRequestResult{
		Job: model.GenerationJob{
			ID: jobID, MissionID: missionID, SpecificationID: specificationID,
			SpecificationVersion: specificationVersion, Status: "QUEUED",
		},
		Created: true,
	}, nil
}

func (s *Store) validateGenerationTemplateBindingTx(ctx context.Context, tx pgx.Tx, owner, missionID int64, raw []byte) error {
	var binding map[string]any
	if len(raw) == 0 || json.Unmarshal(raw, &binding) != nil || binding == nil {
		return ErrGenerationTemplateInvalid
	}
	if fmt.Sprint(binding["bindingKind"]) != "LESSONFORGE_UPSTREAM_TEMPLATE_BINDING" || fmt.Sprint(binding["contractVersion"]) != "lessonforge-upstream-template-v1" ||
		numericID(binding["missionId"]) != missionID || numericID(binding["ownerUserId"]) != owner {
		return ErrGenerationTemplateInvalid
	}
	missionFileID := numericID(binding["missionFileId"])
	fileObjectID := numericID(binding["fileObjectId"])
	if missionFileID <= 0 || fileObjectID <= 0 {
		return ErrGenerationTemplateInvalid
	}

	var input templatebinding.Input
	if err := tx.QueryRow(ctx, `SELECT mf.id,fo.id,m.owner_teacher_id,fo.original_name,fo.mime_type,fo.sha256,fo.storage_key,fo.size_bytes,mf.parse_status,fo.created_at
		FROM mission_files mf
		JOIN file_objects fo ON fo.id=mf.file_object_id
		JOIN missions m ON m.id=mf.mission_id
		WHERE mf.id=$1 AND fo.id=$2 AND mf.mission_id=$3 AND mf.role='TEMPLATE' AND m.owner_teacher_id=$4 AND fo.owner_user_id=$4`, missionFileID, fileObjectID, missionID, owner).
		Scan(&input.MissionFileID, &input.FileObjectID, &input.OwnerUserID, &input.OriginalName, &input.MimeType, &input.SHA256, &input.StorageKey, &input.Size, &input.ParseStatus, &input.LastModified); err != nil {
		return ErrGenerationTemplateInvalid
	}
	input.MissionID = missionID
	expected, err := templatebinding.Build(input, s.StorageRoot)
	if err != nil {
		return ErrGenerationTemplateInvalid
	}
	for _, key := range []string{"templateStorageKey", "templateFileSha256", "templateOriginalName", "templateMimeType", "templateFileSize"} {
		if key == "templateFileSize" {
			if numericID(binding[key]) != numericID(expected[key]) {
				return ErrGenerationTemplateInvalid
			}
			continue
		}
		if fmt.Sprint(binding[key]) != fmt.Sprint(expected[key]) {
			return ErrGenerationTemplateInvalid
		}
	}
	executionReady, executionReadyOK := binding["executionReady"].(bool)
	engineNativeProfilePresent, engineNativeProfilePresentOK := binding["engineNativeProfilePresent"].(bool)
	if !executionReadyOK || !executionReady || !engineNativeProfilePresentOK || !engineNativeProfilePresent {
		return ErrGenerationTemplateProfileNotReady
	}
	return nil
}

func (s *Store) templateBindingTx(ctx context.Context, tx pgx.Tx, missionID int64) (map[string]any, error) {
	var input templatebinding.Input
	if err := tx.QueryRow(ctx, `SELECT mf.id,fo.id,m.owner_teacher_id,fo.original_name,fo.mime_type,fo.sha256,fo.storage_key,fo.size_bytes,mf.parse_status,fo.created_at FROM mission_files mf JOIN file_objects fo ON fo.id=mf.file_object_id JOIN missions m ON m.id=mf.mission_id WHERE mf.mission_id=$1 AND mf.role='TEMPLATE' AND fo.owner_user_id=m.owner_teacher_id ORDER BY mf.created_at,mf.id LIMIT 1`, missionID).Scan(&input.MissionFileID, &input.FileObjectID, &input.OwnerUserID, &input.OriginalName, &input.MimeType, &input.SHA256, &input.StorageKey, &input.Size, &input.ParseStatus, &input.LastModified); err != nil {
		return nil, specification.ErrTemplateRequired
	}
	input.MissionID = missionID
	binding, err := templatebinding.Build(input, s.StorageRoot)
	if err != nil {
		return nil, specification.ErrTemplateRequired
	}
	// Approval is allowed to freeze the teacher's plan before the downstream
	// Engine profile exists. When the Java bridge already has one, however,
	// persist that server-owned native profile in the immutable binding so the
	// later explicit Generate action can use the exact same profile version.
	// A bridge outage, missing profile, or incomplete response must not turn
	// approval into a hidden dependency on Java; the binding stays identity-only
	// and generation will fail closed at its own gate.
	if s.TemplateProfile != nil {
		profileCtx, cancel := context.WithTimeout(ctx, templateProfileResolveTimeout)
		profile, profileErr := s.TemplateProfile.GetProfile(profileCtx, input.OwnerUserID, missionID, input.SHA256)
		cancel()
		if profileErr == nil {
			mergeTemplateProfile(binding, profile, input)
		}
	}
	return binding, nil
}

func mergeTemplateProfile(binding, profile map[string]any, input templatebinding.Input) bool {
	if binding == nil || profile == nil {
		return false
	}
	expectedSHA := strings.ToLower(strings.TrimSpace(input.SHA256))
	if strings.ToLower(strings.TrimSpace(fmt.Sprint(profile["templateFileSha256"]))) != expectedSHA ||
		numericID(profile["missionId"]) != input.MissionID ||
		numericID(profile["ownerUserId"]) != input.OwnerUserID {
		return false
	}
	native, ok := profile["engineNativeProfile"].(map[string]any)
	if !ok || len(native) == 0 {
		return false
	}
	profileChecksum := strings.ToLower(strings.TrimSpace(fmt.Sprint(profile["engineNativeProfileChecksum"])))
	if !isSHA256Hex(profileChecksum) || !completeTemplateProfile(profile, input) {
		return false
	}
	for _, key := range []string{
		"templateId", "profileId", "templateVersion", "profileVersion", "templateFileVersion",
		"templateProfileVersion", "projectId", "sourceVersionId", "sourceSha256", "parserSnapshotChecksum",
		"contractVersion", "status", "executionStatus", "pageSize", "spatialProfile",
		"templatePageReferences", "components", "preservedNativeObjects", "textFitPolicy",
	} {
		if value, exists := profile[key]; exists {
			binding[key] = value
		}
	}
	binding["engineNativeProfile"] = native
	binding["engineNativeProfileChecksum"] = profileChecksum
	binding["profileSource"] = "JAVA_ENGINE_NATIVE_PROFILE"
	binding["executionReady"] = true
	binding["engineNativeProfilePresent"] = true
	return true
}

func completeTemplateProfile(profile map[string]any, input templatebinding.Input) bool {
	for _, key := range []string{
		"contractVersion", "profileId", "templateId", "projectId", "ownerUserId", "templateVersion",
		"profileVersion", "templateFileVersion", "templateProfileVersion", "status", "pageSize",
		"spatialProfile", "templatePageReferences", "components", "textFitPolicy", "executionStatus",
		"sourceVersionId", "sourceSha256", "parserSnapshotChecksum",
	} {
		if _, exists := profile[key]; !exists {
			return false
		}
	}
	if strings.TrimSpace(fmt.Sprint(profile["contractVersion"])) != "1.0.0" ||
		strings.ToUpper(strings.TrimSpace(fmt.Sprint(profile["status"]))) != "READY" ||
		strings.ToUpper(strings.TrimSpace(fmt.Sprint(profile["executionStatus"]))) != "EXECUTION_READY" ||
		strings.ToLower(strings.TrimSpace(fmt.Sprint(profile["sourceSha256"]))) != strings.ToLower(strings.TrimSpace(input.SHA256)) {
		return false
	}
	if numericID(profile["templateVersion"]) <= 0 || numericID(profile["profileVersion"]) <= 0 || numericID(profile["sourceVersionId"]) <= 0 {
		return false
	}
	if !isSHA256Hex(strings.ToLower(strings.TrimSpace(fmt.Sprint(profile["sourceSha256"])))) ||
		!isSHA256Hex(strings.ToLower(strings.TrimSpace(fmt.Sprint(profile["parserSnapshotChecksum"])))) {
		return false
	}
	for _, key := range []string{"pageSize", "spatialProfile", "textFitPolicy"} {
		if _, ok := profile[key].(map[string]any); !ok {
			return false
		}
	}
	for _, key := range []string{"templatePageReferences", "components"} {
		if _, ok := profile[key].([]any); !ok {
			return false
		}
	}
	return true
}

func isSHA256Hex(value string) bool {
	if len(value) != 64 {
		return false
	}
	for _, r := range value {
		if !((r >= '0' && r <= '9') || (r >= 'a' && r <= 'f')) {
			return false
		}
	}
	return true
}

func storagePath(root, key string) (string, error) {
	if strings.TrimSpace(root) == "" || key == "" || filepath.IsAbs(key) || strings.ContainsAny(key, "\\\x00") {
		return "", errors.New("invalid storage key")
	}
	clean := filepath.Clean(filepath.FromSlash(key))
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", errors.New("invalid storage key")
	}
	rootAbs, err := filepath.Abs(root)
	if err != nil {
		return "", err
	}
	pathAbs, err := filepath.Abs(filepath.Join(rootAbs, clean))
	if err != nil {
		return "", err
	}
	if pathAbs != rootAbs && !strings.HasPrefix(pathAbs, rootAbs+string(filepath.Separator)) {
		return "", errors.New("storage key escapes root")
	}
	return pathAbs, nil
}

func numericID(value any) int64 {
	switch number := value.(type) {
	case float64:
		return int64(number)
	case int64:
		return number
	case int:
		return int64(number)
	case string:
		parsed, err := strconv.ParseInt(number, 10, 64)
		if err != nil {
			return 0
		}
		return parsed
	default:
		return 0
	}
}

func validateSourceRefTx(ctx context.Context, tx pgx.Tx, owner, missionID int64, ref map[string]any) error {
	sourceType := strings.ToUpper(strings.TrimSpace(fmt.Sprint(ref["type"])))
	fileID := numericID(ref["id"])
	if fileID == 0 {
		fileID = numericID(ref["fileId"])
	}
	if fileID == 0 {
		return specification.ErrPlanInvalid
	}
	var exists bool
	query := `SELECT EXISTS(SELECT 1 FROM mission_files mf JOIN file_objects fo ON fo.id=mf.file_object_id JOIN missions m ON m.id=mf.mission_id WHERE mf.mission_id=$1 AND fo.id=$2 AND m.owner_teacher_id=$3 AND mf.parse_status='READY' AND (($4='MATERIAL' AND mf.role IN ('MATERIAL','TEACHING_PLAN') AND mf.provenance='MATERIAL') OR ($4='TEACHER' AND mf.role IN ('MATERIAL','TEACHING_PLAN','USER_IMAGE') AND mf.provenance='TEACHER')))`
	if err := tx.QueryRow(ctx, query, missionID, fileID, owner, sourceType).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return specification.ErrPlanInvalid
	}
	return nil
}

func generationJobForTx(ctx context.Context, tx pgx.Tx, missionID int64, specID string) (model.GenerationJob, error) {
	var j model.GenerationJob
	var feedback []byte
	err := tx.QueryRow(ctx, `SELECT g.id,g.mission_id,g.specification_id,ls.version,g.status,g.current_slide,g.total_slides,g.artifact_id,g.generation_feedback,g.created_at,g.started_at,g.finished_at FROM generation_jobs g JOIN locked_specifications ls ON ls.id=g.specification_id WHERE g.mission_id=$1 AND g.specification_id=$2 ORDER BY g.created_at DESC,g.id DESC LIMIT 1`, missionID, specID).Scan(&j.ID, &j.MissionID, &j.SpecificationID, &j.SpecificationVersion, &j.Status, &j.CurrentSlide, &j.TotalSlides, &j.ArtifactID, &feedback, &j.CreatedAt, &j.StartedAt, &j.FinishedAt)
	if err == nil && len(feedback) > 0 {
		if decodeErr := json.Unmarshal(feedback, &j.Feedback); decodeErr != nil {
			return j, fmt.Errorf("decode generation feedback: %w", decodeErr)
		}
	}
	return j, err
}

func generationJobForMissionTx(ctx context.Context, tx pgx.Tx, missionID int64) (model.GenerationJob, error) {
	var j model.GenerationJob
	var feedback []byte
	err := tx.QueryRow(ctx, `SELECT g.id,g.mission_id,g.specification_id,ls.version,g.status,g.current_slide,g.total_slides,g.artifact_id,g.generation_feedback,g.created_at,g.started_at,g.finished_at
		FROM generation_jobs g JOIN locked_specifications ls ON ls.id=g.specification_id
		WHERE g.mission_id=$1 AND g.status IN ('QUEUED','RUNNING','VERIFYING')
		ORDER BY g.created_at DESC,g.id DESC LIMIT 1`, missionID).Scan(&j.ID, &j.MissionID, &j.SpecificationID, &j.SpecificationVersion, &j.Status, &j.CurrentSlide, &j.TotalSlides, &j.ArtifactID, &feedback, &j.CreatedAt, &j.StartedAt, &j.FinishedAt)
	if err == nil && len(feedback) > 0 {
		if decodeErr := json.Unmarshal(feedback, &j.Feedback); decodeErr != nil {
			return j, fmt.Errorf("decode generation feedback: %w", decodeErr)
		}
	}
	return j, err
}
func (s *Store) ClaimGeneration(ctx context.Context) (model.GenerationJob, error) {
	var j model.GenerationJob
	var started time.Time
	var artifactID *string
	var feedback []byte
	workerID, leaseSeconds := s.leaseSettings()
	token := uuid.NewString()
	if s.beforeGenerationClaimHook != nil {
		s.beforeGenerationClaimHook()
	}
	err := s.DB.QueryRow(ctx, `WITH candidate AS (SELECT id FROM generation_jobs WHERE cancel_requested_at IS NULL AND (status='QUEUED' OR (status='RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at<=clock_timestamp())) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE generation_jobs g SET status='RUNNING',started_at=clock_timestamp(),lease_owner=$1,lease_token=$2,lease_expires_at=clock_timestamp()+($3 * interval '1 second'),heartbeat_at=clock_timestamp() FROM candidate c WHERE g.id=c.id RETURNING g.id,g.mission_id,g.specification_id,g.status,g.current_slide,g.total_slides,g.artifact_id,g.generation_feedback,g.created_at,g.started_at,g.finished_at,g.lease_token`, workerID, token, leaseSeconds).Scan(&j.ID, &j.MissionID, &j.SpecificationID, &j.Status, &j.CurrentSlide, &j.TotalSlides, &artifactID, &feedback, &j.CreatedAt, &started, &j.FinishedAt, &j.LeaseToken)
	if err != nil {
		return j, err
	}
	j.ArtifactID = artifactID
	j.StartedAt = &started
	if len(feedback) > 0 {
		if err := json.Unmarshal(feedback, &j.Feedback); err != nil {
			return j, fmt.Errorf("decode generation feedback: %w", err)
		}
	}
	if s.afterGenerationClaimHook != nil {
		s.afterGenerationClaimHook(j)
	}
	return j, nil
}
func (s *Store) LockedSpecificationForMission(ctx context.Context, missionID int64, specID string) (model.LockedSpecification, error) {
	var spt model.LockedSpecification
	var raw, binding []byte
	err := s.DB.QueryRow(ctx, `SELECT id,mission_id,source_draft_id,version,specification_json,template_binding_json,content_hash,created_at FROM locked_specifications WHERE id=$1 AND mission_id=$2`, specID, missionID).Scan(&spt.ID, &spt.MissionID, &spt.SourceDraftID, &spt.Version, &raw, &binding, &spt.ContentHash, &spt.CreatedAt)
	if err != nil {
		return spt, err
	}
	if err := json.Unmarshal(raw, &spt.Specification); err != nil {
		return spt, fmt.Errorf("decode locked specification: %w", err)
	}
	if err := json.Unmarshal(binding, &spt.TemplateBinding); err != nil {
		return spt, fmt.Errorf("decode template binding: %w", err)
	}
	return spt, nil
}

// CurrentLockedSpecification returns the newest immutable specification that
// belongs to the mission owner. The owner predicate is deliberately part of
// the query so a UI refresh cannot turn a specification id into a cross-owner
// read primitive.
func (s *Store) CurrentLockedSpecification(ctx context.Context, owner, missionID int64) (model.LockedSpecification, error) {
	var spt model.LockedSpecification
	var raw, binding []byte
	err := s.DB.QueryRow(ctx, `SELECT ls.id,ls.mission_id,ls.source_draft_id,ls.version,ls.specification_json,ls.template_binding_json,ls.content_hash,ls.created_at FROM locked_specifications ls JOIN missions m ON m.id=ls.mission_id WHERE ls.mission_id=$1 AND m.owner_teacher_id=$2 ORDER BY ls.version DESC,ls.created_at DESC LIMIT 1`, missionID, owner).Scan(&spt.ID, &spt.MissionID, &spt.SourceDraftID, &spt.Version, &raw, &binding, &spt.ContentHash, &spt.CreatedAt)
	if err != nil {
		return spt, err
	}
	if err := json.Unmarshal(raw, &spt.Specification); err != nil {
		return spt, fmt.Errorf("decode locked specification: %w", err)
	}
	if err := json.Unmarshal(binding, &spt.TemplateBinding); err != nil {
		return spt, fmt.Errorf("decode template binding: %w", err)
	}
	return spt, nil
}

func (s *Store) RenewGenerationLease(ctx context.Context, id, leaseToken string) error {
	_, seconds := s.leaseSettings()
	result, err := s.DB.Exec(ctx, `UPDATE generation_jobs SET lease_expires_at=clock_timestamp()+($1 * interval '1 second'),heartbeat_at=clock_timestamp() WHERE id=$2 AND status='RUNNING' AND lease_token=$3`, seconds, id, leaseToken)
	if err == nil && result.RowsAffected() == 0 {
		return errors.New("GENERATION_EXECUTION_FENCE_LOST")
	}
	return err
}

func (s *Store) FinishGeneration(ctx context.Context, id, leaseToken, status string, feedback any, artifactID *string) error {
	raw, err := json.Marshal(feedback)
	if err != nil {
		return fmt.Errorf("encode generation feedback: %w", err)
	}
	result, err := s.DB.Exec(ctx, `UPDATE generation_jobs SET status=$1,generation_feedback=$2,artifact_id=$3,finished_at=clock_timestamp(),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$4 AND status='RUNNING' AND lease_token=$5 AND lease_expires_at>clock_timestamp() AND (cancel_requested_at IS NULL OR $1='CANCELLED')`, status, raw, artifactID, id, leaseToken)
	if err == nil && result.RowsAffected() == 0 {
		return errors.New("GENERATION_EXECUTION_FENCE_LOST")
	}
	return err
}

func (s *Store) FinishGenerationWithActivity(ctx context.Context, id, leaseToken, status string, feedback any, eventType, summary, refType, refID string) error {
	raw, err := json.Marshal(feedback)
	if err != nil {
		return fmt.Errorf("encode generation feedback: %w", err)
	}
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var missionID int64
	if err := tx.QueryRow(ctx, `SELECT mission_id FROM generation_jobs WHERE id=$1 FOR UPDATE`, id).Scan(&missionID); err != nil {
		return err
	}
	if eventType != "" {
		if err := addActivityTxIdempotent(ctx, tx, missionID, eventType, summary, refType, refID, "generation-job:"+id+":final:"+status); err != nil {
			return err
		}
	}
	result, err := tx.Exec(ctx, `UPDATE generation_jobs SET status=$1,generation_feedback=$2,finished_at=clock_timestamp(),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$3 AND status='RUNNING' AND lease_token=$4 AND lease_expires_at>clock_timestamp() AND (cancel_requested_at IS NULL OR $1='CANCELLED')`, status, raw, id, leaseToken)
	if err != nil {
		return err
	}
	if result.RowsAffected() == 0 {
		var current string
		var alreadyRecorded bool
		if err := tx.QueryRow(ctx, `SELECT status FROM generation_jobs WHERE id=$1`, id).Scan(&current); err == nil && current == status {
			if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM activity_events WHERE mission_id=$2 AND idempotency_key=$1)`, "generation-job:"+id+":final:"+status, missionID).Scan(&alreadyRecorded); err == nil && alreadyRecorded {
				return s.commitTx(ctx, tx, "generation-final-reconcile")
			}
		}
		return errors.New("GENERATION_EXECUTION_FENCE_LOST")
	}
	err = s.commitTx(ctx, tx, "generation-final")
	if !errors.Is(err, ErrCommitAmbiguous) {
		return err
	}
	if s.generationFinalCommitted(ctx, id, status) {
		return nil
	}
	return err
}

func (s *Store) generationFinalCommitted(ctx context.Context, jobID, status string) bool {
	var current string
	if err := s.DB.QueryRow(ctx, `SELECT status FROM generation_jobs WHERE id=$1`, jobID).Scan(&current); err != nil || current != status {
		return false
	}
	var found bool
	if err := s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM activity_events WHERE idempotency_key=$1)`, "generation-job:"+jobID+":final:"+status).Scan(&found); err != nil {
		return false
	}
	return found
}
func (s *Store) CancelGeneration(ctx context.Context, owner int64, id string) error {
	_, err := s.DB.Exec(ctx, `UPDATE generation_jobs g SET status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE status END, cancel_requested_at=now(), finished_at=CASE WHEN status='QUEUED' THEN now() ELSE finished_at END
		FROM missions m WHERE g.id=$1 AND g.mission_id=m.id AND m.owner_teacher_id=$2 AND g.status IN ('QUEUED','RUNNING')`, id, owner)
	return err
}
func (s *Store) GenerationCancelRequested(ctx context.Context, id string) (bool, error) {
	var requested bool
	err := s.DB.QueryRow(ctx, `SELECT cancel_requested_at IS NOT NULL OR status='CANCELLED' FROM generation_jobs WHERE id=$1`, id).Scan(&requested)
	return requested, err
}
func (s *Store) Job(ctx context.Context, owner, missionID int64, id string) (model.GenerationJob, error) {
	var j model.GenerationJob
	var feedback []byte
	err := s.DB.QueryRow(ctx, `SELECT g.id,g.mission_id,g.specification_id,ls.version,g.status,g.current_slide,g.total_slides,g.artifact_id,g.generation_feedback,g.created_at,g.started_at,g.finished_at FROM generation_jobs g JOIN locked_specifications ls ON ls.id=g.specification_id JOIN missions m ON m.id=g.mission_id WHERE g.id=$1 AND g.mission_id=$2 AND m.owner_teacher_id=$3`, id, missionID, owner).Scan(&j.ID, &j.MissionID, &j.SpecificationID, &j.SpecificationVersion, &j.Status, &j.CurrentSlide, &j.TotalSlides, &j.ArtifactID, &feedback, &j.CreatedAt, &j.StartedAt, &j.FinishedAt)
	if err != nil {
		return j, err
	}
	if len(feedback) > 0 {
		if err := json.Unmarshal(feedback, &j.Feedback); err != nil {
			return j, fmt.Errorf("decode generation feedback: %w", err)
		}
	}
	return j, nil
}
func (s *Store) Jobs(ctx context.Context, owner, missionID int64) ([]model.GenerationJob, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT g.id,g.mission_id,g.specification_id,ls.version,g.status,g.current_slide,g.total_slides,g.artifact_id,g.generation_feedback,g.created_at,g.started_at,g.finished_at FROM generation_jobs g JOIN locked_specifications ls ON ls.id=g.specification_id WHERE g.mission_id=$1 ORDER BY g.created_at DESC,g.id DESC`, missionID)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	var out []model.GenerationJob
	for rows.Next() {
		var j model.GenerationJob
		var feedback []byte
		if err := rows.Scan(&j.ID, &j.MissionID, &j.SpecificationID, &j.SpecificationVersion, &j.Status, &j.CurrentSlide, &j.TotalSlides, &j.ArtifactID, &feedback, &j.CreatedAt, &j.StartedAt, &j.FinishedAt); err != nil {
			return nil, err
		}
		if len(feedback) > 0 {
			if err := json.Unmarshal(feedback, &j.Feedback); err != nil {
				return nil, fmt.Errorf("decode generation feedback: %w", err)
			}
		}
		out = append(out, j)
	}
	return out, rows.Err()
}
func (s *Store) CreateArtifact(ctx context.Context, owner int64, missionID int64, jobID string, file model.FileObject, contentType string) (model.Artifact, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return model.Artifact{}, err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var fileID int64
	if err := tx.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,$3,$4,$5,$6) RETURNING id`, owner, file.StorageKey, file.OriginalName, file.MimeType, file.Size, file.SHA256).Scan(&fileID); err != nil {
		return model.Artifact{}, err
	}
	var version int
	if err := tx.QueryRow(ctx, `SELECT COALESCE(MAX(version),0)+1 FROM artifacts WHERE mission_id=$1`, missionID).Scan(&version); err != nil {
		return model.Artifact{}, err
	}
	id := uuid.NewString()
	if _, err := tx.Exec(ctx, `INSERT INTO artifacts(id,mission_id,generation_job_id,file_object_id,version,content_type,sha256,size_bytes) VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, id, missionID, jobID, fileID, version, contentType, file.SHA256, file.Size); err != nil {
		return model.Artifact{}, err
	}
	if err := addActivityTx(ctx, tx, missionID, "ARTIFACT_READY", "PPTX artifact is ready", "ARTIFACT", id); err != nil {
		return model.Artifact{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return model.Artifact{}, err
	}
	file.ID = fileID
	return model.Artifact{ID: id, MissionID: missionID, GenerationJobID: jobID, File: file, Version: version, ContentType: contentType, SHA256: file.SHA256, Size: file.Size, Status: ArtifactStatusReady}, nil
}

func (s *Store) CommitGenerationArtifact(ctx context.Context, owner int64, missionID int64, jobID, leaseToken string, file model.FileObject, contentType string, feedback any) (model.Artifact, error) {
	if artifact, found, err := s.generationArtifactByJob(ctx, owner, missionID, jobID); err != nil {
		return model.Artifact{}, err
	} else if found {
		if artifact.Status == ArtifactStatusReady || artifact.Status == ArtifactStatusStaged {
			return artifact, nil
		}
		return model.Artifact{}, errors.New("ARTIFACT_NOT_RETRYABLE")
	}
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return model.Artifact{}, err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var lockedMissionID int64
	if err := tx.QueryRow(ctx, `SELECT g.mission_id FROM generation_jobs g JOIN missions m ON m.id=g.mission_id WHERE g.id=$1 AND g.mission_id=$2 AND m.owner_teacher_id=$3 AND g.status='RUNNING' AND g.lease_token=$4 AND g.lease_expires_at>clock_timestamp() AND g.cancel_requested_at IS NULL FOR UPDATE OF g`, jobID, missionID, owner, leaseToken).Scan(&lockedMissionID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			if artifact, found, reconcileErr := s.generationArtifactByJob(ctx, owner, missionID, jobID); reconcileErr == nil && found {
				return artifact, nil
			}
			return model.Artifact{}, errors.New("GENERATION_EXECUTION_FENCE_LOST")
		}
		return model.Artifact{}, err
	}
	if s.beforeGenerationArtifactWriteHook != nil {
		s.beforeGenerationArtifactWriteHook()
	}
	var marker int
	if err := tx.QueryRow(ctx, `SELECT 1 FROM generation_jobs WHERE id=$1 AND mission_id=$2 AND status='RUNNING' AND lease_token=$3 AND lease_expires_at>clock_timestamp() AND cancel_requested_at IS NULL FOR UPDATE`, jobID, missionID, leaseToken).Scan(&marker); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return model.Artifact{}, errors.New("GENERATION_EXECUTION_FENCE_LOST")
		}
		return model.Artifact{}, err
	}
	var fileID int64
	if err := tx.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT (storage_key) DO NOTHING RETURNING id`, owner, file.StorageKey, file.OriginalName, file.MimeType, file.Size, file.SHA256).Scan(&fileID); err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			return model.Artifact{}, err
		}
		if err := tx.QueryRow(ctx, `SELECT id FROM file_objects WHERE storage_key=$1 AND owner_user_id=$2 AND original_name=$3 AND mime_type=$4 AND size_bytes=$5 AND sha256=$6`, file.StorageKey, owner, file.OriginalName, file.MimeType, file.Size, file.SHA256).Scan(&fileID); err != nil {
			return model.Artifact{}, errors.New("ARTIFACT_STORAGE_IDENTITY_CONFLICT")
		}
	}
	var version int
	if err := tx.QueryRow(ctx, `SELECT COALESCE(MAX(version),0)+1 FROM artifacts WHERE mission_id=$1`, missionID).Scan(&version); err != nil {
		return model.Artifact{}, err
	}
	id := uuid.NewString()
	if err := tx.QueryRow(ctx, `INSERT INTO artifacts(id,mission_id,generation_job_id,file_object_id,version,content_type,sha256,size_bytes,status) VALUES($1,$2,$3,$4,$5,$6,$7,$8,'STAGED') RETURNING id`, id, missionID, jobID, fileID, version, contentType, file.SHA256, file.Size).Scan(&id); err != nil {
		return model.Artifact{}, err
	}
	if err := addActivityTxIdempotent(ctx, tx, missionID, "ARTIFACT_STAGED", "PPTX artifact staged for verification", "ARTIFACT", id, "generation-job:"+jobID+":artifact"); err != nil {
		return model.Artifact{}, err
	}
	rawFeedback, err := json.Marshal(feedback)
	if err != nil {
		return model.Artifact{}, fmt.Errorf("encode generation feedback: %w", err)
	}
	result, err := tx.Exec(ctx, `UPDATE generation_jobs SET status='VERIFYING',generation_feedback=$1,artifact_id=$2,finished_at=NULL,lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$3 AND status='RUNNING' AND lease_token=$4 AND lease_expires_at>clock_timestamp() AND cancel_requested_at IS NULL`, rawFeedback, id, jobID, leaseToken)
	if err != nil {
		return model.Artifact{}, err
	}
	if result.RowsAffected() == 0 {
		return model.Artifact{}, errors.New("GENERATION_EXECUTION_FENCE_LOST")
	}
	if err := s.commitTx(ctx, tx, "generation-artifact"); err != nil {
		if errors.Is(err, ErrCommitAmbiguous) {
			if artifact, found, reconcileErr := s.generationArtifactByJob(ctx, owner, missionID, jobID); reconcileErr == nil && found {
				return artifact, nil
			}
		}
		return model.Artifact{}, err
	}
	file.ID = fileID
	return model.Artifact{ID: id, MissionID: lockedMissionID, GenerationJobID: jobID, File: file, Version: version, ContentType: contentType, SHA256: file.SHA256, Size: file.Size, Status: ArtifactStatusStaged}, nil
}

// FinalizeGenerationArtifact is the only transition that makes a generated
// artifact visible. The physical file has already passed verification before
// this transaction; keeping the database row STAGED until this point makes a
// crash between DB commit and verification fail closed.
func (s *Store) FinalizeGenerationArtifact(ctx context.Context, owner, missionID int64, jobID, artifactID string) error {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var currentStatus, currentArtifact string
	if err := tx.QueryRow(ctx, `SELECT g.status,COALESCE(g.artifact_id::text,'') FROM generation_jobs g JOIN missions m ON m.id=g.mission_id WHERE g.id=$1 AND g.mission_id=$2 AND m.owner_teacher_id=$3 FOR UPDATE`, jobID, missionID, owner).Scan(&currentStatus, &currentArtifact); err != nil {
		return err
	}
	if currentStatus == "SUCCEEDED" && currentArtifact == artifactID {
		return s.commitTx(ctx, tx, "generation-finalize-reconcile")
	}
	if currentStatus != "VERIFYING" || currentArtifact != artifactID {
		return errors.New("GENERATION_ARTIFACT_NOT_STAGED")
	}
	var artifactStatus string
	if err := tx.QueryRow(ctx, `SELECT status FROM artifacts WHERE id=$1 AND generation_job_id=$2 FOR UPDATE`, artifactID, jobID).Scan(&artifactStatus); err != nil {
		return err
	}
	if artifactStatus != ArtifactStatusStaged {
		return errors.New("GENERATION_ARTIFACT_NOT_STAGED")
	}
	if _, err := tx.Exec(ctx, `UPDATE artifacts SET status='READY' WHERE id=$1 AND generation_job_id=$2`, artifactID, jobID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE activity_events SET event_type='ARTIFACT_READY',summary='PPTX artifact is ready' WHERE mission_id=$1 AND idempotency_key=$2`, missionID, "generation-job:"+jobID+":artifact"); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE generation_jobs SET status='SUCCEEDED',finished_at=clock_timestamp() WHERE id=$1 AND status='VERIFYING' AND artifact_id=$2`, jobID, artifactID); err != nil {
		return err
	}
	err = s.commitTx(ctx, tx, "generation-finalize")
	// A successful database commit with a lost acknowledgement is deliberately
	// still unknown to this caller.  Do not turn it into success in-process:
	// the next Worker.RunOnce observes the committed READY/SUCCEEDED state via
	// its normal recovery/claim pass, which is the same path used after a real
	// process or connection failure.
	return err
}

// InvalidateGenerationArtifact is a compensating transaction for a staged or
// ready artifact whose physical file no longer matches its receipt. It removes
// all success references atomically, but never decides physical cleanup from
// an uncertain database result.
func (s *Store) InvalidateGenerationArtifact(ctx context.Context, owner, missionID int64, jobID, artifactID string, feedback any) error {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { s.recordCleanupError("transaction rollback", tx.Rollback(ctx)) }()
	var status string
	var currentArtifact *string
	if err := tx.QueryRow(ctx, `SELECT g.status,g.artifact_id FROM generation_jobs g JOIN missions m ON m.id=g.mission_id WHERE g.id=$1 AND g.mission_id=$2 AND m.owner_teacher_id=$3 FOR UPDATE`, jobID, missionID, owner).Scan(&status, &currentArtifact); err != nil {
		return err
	}
	if currentArtifact == nil || *currentArtifact != artifactID {
		if status == "FAILED" || status == "CANCELLED" {
			return s.commitTx(ctx, tx, "generation-invalidate-reconcile")
		}
		return errors.New("GENERATION_ARTIFACT_NOT_CURRENT")
	}
	var fileID int64
	if err := tx.QueryRow(ctx, `SELECT file_object_id FROM artifacts WHERE id=$1 AND generation_job_id=$2 FOR UPDATE`, artifactID, jobID).Scan(&fileID); err != nil {
		return err
	}
	rawFeedback, err := json.Marshal(feedback)
	if err != nil {
		return fmt.Errorf("encode invalid artifact feedback: %w", err)
	}
	if _, err := tx.Exec(ctx, `UPDATE generation_jobs SET status='FAILED',generation_feedback=$1,artifact_id=NULL,finished_at=clock_timestamp(),lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$2 AND artifact_id=$3`, rawFeedback, jobID, artifactID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE activity_events SET event_type='ARTIFACT_INVALID',summary='PPTX artifact failed physical verification' WHERE mission_id=$1 AND idempotency_key=$2`, missionID, "generation-job:"+jobID+":artifact"); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM artifacts WHERE id=$1 AND generation_job_id=$2`, artifactID, jobID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM file_objects fo WHERE fo.id=$1 AND NOT EXISTS (SELECT 1 FROM mission_files mf WHERE mf.file_object_id=fo.id) AND NOT EXISTS (SELECT 1 FROM uploads u WHERE u.file_object_id=fo.id) AND NOT EXISTS (SELECT 1 FROM artifacts a WHERE a.file_object_id=fo.id)`, fileID); err != nil {
		return err
	}
	err = s.commitTx(ctx, tx, "generation-invalidate")
	if !errors.Is(err, ErrCommitAmbiguous) {
		return err
	}
	if s.generationInvalidated(ctx, jobID, artifactID) {
		return nil
	}
	return err
}

// StorageKeyReferenced reports whether any remaining file object still owns a
// physical storage key. Callers use it after a successful invalidation before
// removing bytes, so a MissionFile/upload sharing the key is never deleted.
func (s *Store) StorageKeyReferenced(ctx context.Context, storageKey string) (bool, error) {
	var referenced bool
	err := s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM file_objects WHERE storage_key=$1)`, storageKey).Scan(&referenced)
	return referenced, err
}

func (s *Store) generationArtifactReady(ctx context.Context, owner, missionID int64, jobID, artifactID string) bool {
	var found bool
	return s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM artifacts a JOIN missions m ON m.id=a.mission_id JOIN generation_jobs g ON g.id=a.generation_job_id WHERE a.id=$1 AND a.generation_job_id=$2 AND a.status='READY' AND g.status='SUCCEEDED' AND a.mission_id=$3 AND m.owner_teacher_id=$4)`, artifactID, jobID, missionID, owner).Scan(&found) == nil && found
}

func (s *Store) generationInvalidated(ctx context.Context, jobID, artifactID string) bool {
	var found bool
	return s.DB.QueryRow(ctx, `SELECT NOT EXISTS(SELECT 1 FROM artifacts WHERE id=$1) AND EXISTS(SELECT 1 FROM generation_jobs WHERE id=$2 AND status='FAILED' AND artifact_id IS NULL)`, artifactID, jobID).Scan(&found) == nil && found
}

// StagedGenerationArtifact returns one crash-recovery candidate. It is
// deliberately separate from ClaimGeneration: a staged output has no active
// lease and must be verified/finalized, not regenerated.
func (s *Store) StagedGenerationArtifact(ctx context.Context) (int64, model.Artifact, bool, error) {
	var owner int64
	var artifact model.Artifact
	row := s.DB.QueryRow(ctx, `SELECT m.owner_teacher_id,a.id,a.mission_id,a.generation_job_id,a.version,a.content_type,a.sha256,a.size_bytes,a.status,a.created_at,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at FROM artifacts a JOIN file_objects fo ON fo.id=a.file_object_id JOIN generation_jobs g ON g.id=a.generation_job_id JOIN missions m ON m.id=a.mission_id WHERE a.status='STAGED' AND g.status='VERIFYING' ORDER BY a.created_at,a.id LIMIT 1`)
	var rawSize, fileSize int64
	err := row.Scan(&owner, &artifact.ID, &artifact.MissionID, &artifact.GenerationJobID, &artifact.Version, &artifact.ContentType, &artifact.SHA256, &rawSize, &artifact.Status, &artifact.CreatedAt, &artifact.File.ID, &artifact.File.OriginalName, &artifact.File.MimeType, &fileSize, &artifact.File.SHA256, &artifact.File.StorageKey, &artifact.File.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, model.Artifact{}, false, nil
	}
	if err != nil {
		return 0, model.Artifact{}, false, err
	}
	artifact.Size = rawSize
	artifact.File.Size = fileSize
	return owner, artifact, true, nil
}

func scanArtifact(row interface{ Scan(...any) error }) (model.Artifact, error) {
	var a model.Artifact
	var rawSize, fileSize int64
	err := row.Scan(&a.ID, &a.MissionID, &a.GenerationJobID, &a.Version, &a.ContentType, &a.SHA256, &rawSize, &a.Status, &a.CreatedAt, &a.File.ID, &a.File.OriginalName, &a.File.MimeType, &fileSize, &a.File.SHA256, &a.File.StorageKey, &a.File.CreatedAt)
	a.Size = rawSize
	a.File.Size = fileSize
	return a, err
}

func (s *Store) generationArtifactByJob(ctx context.Context, owner, missionID int64, jobID string) (model.Artifact, bool, error) {
	row := s.DB.QueryRow(ctx, `SELECT a.id,a.mission_id,a.generation_job_id,a.version,a.content_type,a.sha256,a.size_bytes,a.status,a.created_at,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at FROM artifacts a JOIN file_objects fo ON fo.id=a.file_object_id JOIN missions m ON m.id=a.mission_id WHERE a.generation_job_id=$1 AND a.mission_id=$2 AND m.owner_teacher_id=$3`, jobID, missionID, owner)
	a, err := scanArtifact(row)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.Artifact{}, false, nil
	}
	return a, err == nil, err
}

func (s *Store) generationArtifactByJobTx(ctx context.Context, tx pgx.Tx, owner, missionID int64, jobID string) (model.Artifact, bool, error) {
	row := tx.QueryRow(ctx, `SELECT a.id,a.mission_id,a.generation_job_id,a.version,a.content_type,a.sha256,a.size_bytes,a.status,a.created_at,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at FROM artifacts a JOIN file_objects fo ON fo.id=a.file_object_id JOIN missions m ON m.id=a.mission_id WHERE a.generation_job_id=$1 AND a.mission_id=$2 AND m.owner_teacher_id=$3`, jobID, missionID, owner)
	a, err := scanArtifact(row)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.Artifact{}, false, nil
	}
	return a, err == nil, err
}

// ReconcileGenerationArtifact reads the primary database after a commit
// response is uncertain. A false result means no artifact reference exists at
// the time of this read, so the caller may clean the stable staged file.
func (s *Store) ReconcileGenerationArtifact(ctx context.Context, owner, missionID int64, jobID, leaseToken, storageKey string) (model.Artifact, bool, error) {
	artifact, found, err := s.generationArtifactByJob(ctx, owner, missionID, jobID)
	if err != nil {
		return artifact, false, err
	}
	if found {
		if artifact.File.StorageKey != storageKey {
			return artifact, true, errors.New("ARTIFACT_STORAGE_KEY_MISMATCH")
		}
		return artifact, found, err
	}
	var status, currentToken string
	var expired bool
	if err := s.DB.QueryRow(ctx, `SELECT status,COALESCE(lease_token,''),COALESCE(lease_expires_at<=clock_timestamp(),false) FROM generation_jobs WHERE id=$1 AND mission_id=$2`, jobID, missionID).Scan(&status, &currentToken, &expired); err != nil {
		return model.Artifact{}, false, err
	}
	var referenced bool
	if err := s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM artifacts a JOIN file_objects fo ON fo.id=a.file_object_id WHERE a.generation_job_id=$1 AND fo.storage_key=$2)`, jobID, storageKey).Scan(&referenced); err != nil {
		return model.Artifact{}, false, err
	}
	if referenced {
		return model.Artifact{}, false, errors.New("ARTIFACT_REFERENCE_RECONCILIATION_FAILED")
	}
	// A stale worker may remove only its own uncommitted stable file. If a new
	// worker has already claimed the job, the token differs and the physical
	// bytes remain protected for that worker's attempt.
	if status == "RUNNING" && expired && currentToken == leaseToken {
		return model.Artifact{}, false, nil
	}
	if status != "FAILED" && status != "CANCELLED" {
		return model.Artifact{}, false, errors.New("ARTIFACT_COMMIT_NOT_CONFIRMED")
	}
	return model.Artifact{}, false, nil
}

func (s *Store) Artifact(ctx context.Context, owner int64, id string) (model.Artifact, error) {
	var a model.Artifact
	var rawSize int64
	err := s.DB.QueryRow(ctx, `SELECT a.id,a.mission_id,a.generation_job_id,a.version,a.content_type,a.sha256,a.size_bytes,a.status,a.created_at,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at FROM artifacts a JOIN file_objects fo ON fo.id=a.file_object_id JOIN generation_jobs g ON g.id=a.generation_job_id JOIN missions m ON m.id=a.mission_id WHERE a.id=$1 AND a.status='READY' AND g.status='SUCCEEDED' AND m.owner_teacher_id=$2`, id, owner).Scan(&a.ID, &a.MissionID, &a.GenerationJobID, &a.Version, &a.ContentType, &a.SHA256, &rawSize, &a.Status, &a.CreatedAt, &a.File.ID, &a.File.OriginalName, &a.File.MimeType, &a.File.Size, &a.File.SHA256, &a.File.StorageKey, &a.File.CreatedAt)
	a.Size = rawSize
	return a, err
}
func (s *Store) Artifacts(ctx context.Context, owner, missionID int64) ([]model.Artifact, error) {
	if err := s.requireMissionOwner(ctx, owner, missionID); err != nil {
		return nil, err
	}
	rows, err := s.DB.Query(ctx, `SELECT a.id,a.mission_id,a.generation_job_id,a.version,a.content_type,a.sha256,a.size_bytes,a.status,a.created_at,fo.id,fo.original_name,fo.mime_type,fo.size_bytes,fo.sha256,fo.storage_key,fo.created_at FROM artifacts a JOIN file_objects fo ON fo.id=a.file_object_id JOIN generation_jobs g ON g.id=a.generation_job_id WHERE a.mission_id=$1 AND a.status='READY' AND g.status='SUCCEEDED' ORDER BY a.version DESC`, missionID)
	if err != nil {
		return nil, err
	}
	defer s.closeRows("rows close", rows)
	var out []model.Artifact
	for rows.Next() {
		var a model.Artifact
		if err := rows.Scan(&a.ID, &a.MissionID, &a.GenerationJobID, &a.Version, &a.ContentType, &a.SHA256, &a.Size, &a.Status, &a.CreatedAt, &a.File.ID, &a.File.OriginalName, &a.File.MimeType, &a.File.Size, &a.File.SHA256, &a.File.StorageKey, &a.File.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}
