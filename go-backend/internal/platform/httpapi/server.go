package httpapi

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"lessonforge.local/backend/internal/agent"
	"lessonforge.local/backend/internal/auth"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/platform/crypto"
	"lessonforge.local/backend/internal/platform/database"
	"lessonforge.local/backend/internal/platform/storage"
)

type contextKey string

const userKey contextKey = "lessonforge.user"

type Server struct {
	cfg    Config
	db     *pgxpool.Pool
	store  *database.Store
	crypto *crypto.Service
	files  *storage.Service
	models *model.Client
	agent  *agent.Runtime
	// afterDownloadOpenHook is a same-package test seam for the protected
	// descriptor-to-send window. Production servers leave it nil.
	afterDownloadOpenHook func() error
	// beforeDownloadSendHook is a same-package test seam for the final
	// verification-to-header window. Production servers leave it nil.
	beforeDownloadSendHook func() error
	// downloadSendErrorHook observes an actual response Write or Flush error in
	// same-package TCP integration tests. Production servers leave it nil.
	downloadSendErrorHook func(error)
}
type Config struct {
	Addr, SessionCookie string
	SessionTTL, timeOut time.Duration
	MaxUploadBytes      int64
	CORSOrigins         []string
}

func NewServer(cfg Config, db *pgxpool.Pool, store *database.Store, crypt *crypto.Service, files *storage.Service, models *model.Client, runtime *agent.Runtime) *Server {
	return &Server{cfg: cfg, db: db, store: store, crypto: crypt, files: files, models: models, agent: runtime}
}
func (s *Server) Router() http.Handler {
	r := chi.NewRouter()
	r.Use(s.cors)
	r.Get("/healthz", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, map[string]string{"status": "ok"}) })
	r.Route("/api/auth", func(r chi.Router) {
		r.Post("/register", s.register)
		r.Post("/login", s.login)
		r.Post("/logout", s.logout)
		r.Group(func(r chi.Router) { r.Use(s.requireAuth); r.Get("/me", s.me) })
	})
	r.Group(func(r chi.Router) {
		r.Use(s.requireAuth)
		r.Use(s.requireTeacher)
		r.Get("/api/model-connections", s.listConnections)
		r.Get("/api/model-connections/{id}", s.getConnection)
		r.Post("/api/model-connections", s.createConnection)
		r.Put("/api/model-connections/{id}", s.updateConnection)
		r.Delete("/api/model-connections/{id}", s.deleteConnection)
		r.Post("/api/model-connections/{id}/enabled", s.enableConnection)
		r.Post("/api/model-connections/{id}/verify", s.verifyConnection)
		r.Post("/api/uploads", s.upload)
		r.Get("/api/missions", s.listMissions)
		r.Post("/api/missions", s.createMission)
		r.Get("/api/missions/{id}", s.getMission)
		r.Get("/api/missions/{id}/messages", s.messages)
		r.Post("/api/missions/{id}/messages", s.sendMessage)
		r.Put("/api/missions/{id}/model-connection", s.selectConnection)
		r.Post("/api/missions/{id}/files", s.addMissionFile)
		r.Get("/api/missions/{id}/files", s.missionFiles)
		r.Get("/api/missions/{id}/questions", s.questions)
		r.Get("/api/missions/{id}/agent-runs", s.agentRuns)
		r.Get("/api/missions/{id}/events", s.events)
		r.Get("/api/missions/{id}/feedback", s.missionFeedback)
		r.Get("/api/missions/{id}/planning/current", s.currentDraft)
		r.Get("/api/missions/{id}/planning/history", s.drafts)
		r.Post("/api/planning/{draftId}/approve", s.approveDraft)
		r.Post("/api/questions/{id}/answers", s.answerQuestion)
		r.Post("/api/agent-runs/{id}/cancel", s.cancelAgent)
		r.Get("/api/missions/{id}/generation-jobs", s.jobs)
		r.Post("/api/missions/{id}/generation-jobs", s.createGenerationJob)
		r.Get("/api/generation-jobs/{id}", s.job)
		r.Post("/api/generation-jobs/{id}/cancel", s.cancelGeneration)
		r.Get("/api/missions/{id}/artifacts", s.artifacts)
		r.Get("/api/artifacts/{id}/download", s.download)
	})
	r.Group(func(r chi.Router) {
		r.Use(s.requireAuth)
		r.Use(s.requireResearcher)
		r.Get("/api/researcher/missions", s.listReviewMissions)
		r.Get("/api/researcher/missions/{id}", s.getReviewMission)
		r.Get("/api/researcher/missions/{id}/feedback", s.reviewMissionFeedback)
		r.Post("/api/researcher/missions/{id}/feedback", s.createReviewFeedback)
	})
	return r
}

func (s *Server) cors(next http.Handler) http.Handler {
	allowed := make(map[string]struct{}, len(s.cfg.CORSOrigins))
	for _, origin := range s.cfg.CORSOrigins {
		allowed[origin] = struct{}{}
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if _, ok := allowed[origin]; ok {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Access-Control-Allow-Credentials", "true")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
			w.Header().Add("Vary", "Origin")
		}
		if r.Method == http.MethodOptions {
			if _, ok := allowed[origin]; !ok {
				writeError(w, http.StatusForbidden, "CORS_ORIGIN_NOT_ALLOWED")
				return
			}
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name, Email, Password string
		Role                  model.Role
	}
	if !decode(w, r, &req) {
		return
	}
	if req.Role != "TEACHER" && req.Role != "RESEARCHER" {
		writeError(w, 400, "ROLE_INVALID")
		return
	}
	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		writeError(w, 400, "PASSWORD_INVALID")
		return
	}
	u, err := s.store.CreateUser(r.Context(), req.Name, req.Email, hash, req.Role)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			writeError(w, 409, "EMAIL_ALREADY_EXISTS")
		} else {
			writeError(w, 500, "USER_CREATE_FAILED")
		}
		return
	}
	writeJSON(w, 201, map[string]any{"user": u})
}
func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var req struct{ Email, Password string }
	if !decode(w, r, &req) {
		return
	}
	u, hash, err := s.store.UserByEmail(r.Context(), req.Email)
	if err != nil || !auth.CheckPassword(hash, req.Password) {
		writeError(w, 401, "INVALID_CREDENTIALS")
		return
	}
	token, err := auth.NewToken()
	if err != nil {
		writeError(w, 500, "SESSION_CREATE_FAILED")
		return
	}
	expires := time.Now().Add(s.cfg.SessionTTL)
	if err := s.store.CreateSession(r.Context(), u.ID, auth.TokenHash(token), expires); err != nil {
		writeError(w, 500, "SESSION_CREATE_FAILED")
		return
	}
	http.SetCookie(w, &http.Cookie{Name: s.cfg.SessionCookie, Value: token, Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: r.TLS != nil, Expires: expires})
	writeJSON(w, 200, map[string]any{"user": u, "expiresAt": expires, "token": token})
}
func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	if token := s.token(r); token != "" {
		if err := s.store.DeleteSession(r.Context(), auth.TokenHash(token)); err != nil {
			writeError(w, http.StatusServiceUnavailable, "SESSION_DELETE_FAILED")
			return
		}
	}
	http.SetCookie(w, &http.Cookie{Name: s.cfg.SessionCookie, Value: "", Path: "/", HttpOnly: true, MaxAge: -1})
	writeJSON(w, 200, map[string]any{"ok": true})
}
func (s *Server) me(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"user": currentUser(r.Context())})
}

func (s *Server) listConnections(w http.ResponseWriter, r *http.Request) {
	items, err := s.store.ListConnections(r.Context(), currentUser(r.Context()).ID)
	if err != nil {
		writeError(w, 500, "CONNECTION_LIST_FAILED")
		return
	}
	writeJSON(w, 200, items)
}
func (s *Server) getConnection(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	connection, _, err := s.store.Connection(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "CONNECTION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, connection)
}
func (s *Server) createConnection(w http.ResponseWriter, r *http.Request) {
	var req connectionRequest
	if !decode(w, r, &req) {
		return
	}
	normalized, err := s.validatedBase(r.Context(), req)
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	req.BaseURL = normalized
	encrypted, err := s.crypto.Encrypt(req.APIKey)
	if err != nil {
		writeError(w, 500, "CREDENTIAL_ENCRYPTION_FAILED")
		return
	}
	capabilities := model.DefaultModelCapabilities()
	capabilitiesSet := false
	if req.Capabilities != nil {
		capabilities = *req.Capabilities
		capabilitiesSet = true
	}
	c, err := s.store.CreateConnection(r.Context(), currentUser(r.Context()).ID, model.ModelConnection{Name: req.Name, Provider: normalizedProvider(req.Provider), Protocol: req.Protocol, BaseURL: req.BaseURL, ModelID: req.ModelID, Capabilities: capabilities, CapabilitiesSet: capabilitiesSet, KeyHint: s.crypto.Hint(req.APIKey)}, encrypted)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			writeError(w, 409, "CONNECTION_NAME_EXISTS")
		} else {
			writeError(w, 500, "CONNECTION_CREATE_FAILED")
		}
		return
	}
	writeJSON(w, 201, c)
}
func (s *Server) updateConnection(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var req connectionRequest
	if !decode(w, r, &req) {
		return
	}
	owner := currentUser(r.Context()).ID
	old, encrypted, err := s.store.Connection(r.Context(), owner, id)
	if err != nil {
		writeNotFound(w, "CONNECTION_NOT_FOUND")
		return
	}
	replace := strings.TrimSpace(req.APIKey) != ""
	if replace {
		if err := s.validateConnection(r.Context(), req); err != nil {
			writeError(w, 400, err.Error())
			return
		}
	} else {
		if req.Name == "" || len(req.Name) > 120 {
			writeError(w, 400, "CONNECTION_NAME_INVALID")
			return
		}
		if req.Protocol != "OPENAI_COMPATIBLE" {
			writeError(w, 400, "PROTOCOL_INVALID")
			return
		}
		if req.ModelID == "" || len(req.ModelID) > 128 {
			writeError(w, 400, "MODEL_ID_INVALID")
			return
		}
		if err := s.models.ValidateBaseURL(r.Context(), req.BaseURL); err != nil {
			writeError(w, 400, "BASE_URL_UNSAFE_OR_UNREACHABLE")
			return
		}
	}
	normalized, err := model.NormalizeBaseURL(req.BaseURL)
	if err != nil {
		writeError(w, 400, "BASE_URL_INVALID")
		return
	}
	if !replace {
		req.APIKey = ""
	}
	newEncrypted := encrypted
	newHint := old.KeyHint
	if replace {
		newEncrypted, err = s.crypto.Encrypt(req.APIKey)
		if err != nil {
			writeError(w, 500, "CREDENTIAL_ENCRYPTION_FAILED")
			return
		}
		newHint = s.crypto.Hint(req.APIKey)
	}
	provider := req.Provider
	if strings.TrimSpace(provider) == "" {
		provider = old.Provider
	}
	capabilities := old.Capabilities
	capabilitiesSet := true
	if req.Capabilities != nil {
		capabilities = *req.Capabilities
	}
	updated, err := s.store.UpdateConnection(r.Context(), owner, id, model.ModelConnection{Name: req.Name, Provider: normalizedProvider(provider), Protocol: req.Protocol, BaseURL: normalized, ModelID: req.ModelID, Capabilities: capabilities, CapabilitiesSet: capabilitiesSet}, newEncrypted, newHint, replace)
	if err != nil {
		writeError(w, 500, "CONNECTION_UPDATE_FAILED")
		return
	}
	writeJSON(w, 200, updated)
}
func (s *Server) deleteConnection(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if err := s.store.DeleteConnection(r.Context(), currentUser(r.Context()).ID, id); err != nil {
		writeNotFound(w, "CONNECTION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true})
}
func (s *Server) enableConnection(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	value, err := strconv.ParseBool(r.URL.Query().Get("enabled"))
	if err != nil {
		writeError(w, 400, "ENABLED_REQUIRED")
		return
	}
	c, err := s.store.SetConnectionEnabled(r.Context(), currentUser(r.Context()).ID, id, value)
	if err != nil {
		writeNotFound(w, "CONNECTION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, c)
}
func (s *Server) verifyConnection(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	owner := currentUser(r.Context()).ID
	c, encrypted, err := s.store.Connection(r.Context(), owner, id)
	if err != nil {
		writeNotFound(w, "CONNECTION_NOT_FOUND")
		return
	}
	key, err := s.crypto.Decrypt(encrypted)
	if err != nil {
		if statusErr := s.store.MarkConnectionVerification(r.Context(), owner, id, "INVALID"); statusErr != nil {
			writeError(w, http.StatusServiceUnavailable, "CONNECTION_STATUS_WRITE_FAILED")
			return
		}
		writeError(w, 409, "MODEL_CONNECTION_CREDENTIAL_UNAVAILABLE")
		return
	}
	resolved := model.ResolvedConnection{ID: id, OwnerUserID: owner, Provider: c.Provider, Protocol: c.Protocol, BaseURL: c.BaseURL, ModelID: c.ModelID, Capabilities: c.Capabilities, CapabilityVerification: c.CapabilityVerification, APIKey: key}
	started := time.Now()
	checks, err := s.models.VerifyConnection(r.Context(), resolved)
	status := checks.HTTPStatus
	safeCode := "VERIFIED"
	verificationStatus := "VERIFIED"
	if err != nil {
		verificationStatus = "INVALID"
		safeCode = safeErrorCode(err)
	}
	if checks.NormalChat {
		if capabilityErr := s.store.MarkConnectionCapabilityVerification(r.Context(), owner, id, checks); capabilityErr != nil {
			writeError(w, http.StatusServiceUnavailable, "CONNECTION_CAPABILITY_STATUS_WRITE_FAILED")
			return
		}
	}
	if statusErr := s.store.MarkConnectionVerification(r.Context(), owner, id, verificationStatus); statusErr != nil {
		writeError(w, http.StatusServiceUnavailable, "CONNECTION_STATUS_WRITE_FAILED")
		return
	}
	if auditErr := s.audit(r.Context(), uuid.New(), owner, 0, id, c, verificationStatus, status, time.Since(started)); auditErr != nil {
		writeError(w, http.StatusServiceUnavailable, "CONNECTION_AUDIT_WRITE_FAILED")
		return
	}
	writeJSON(w, 200, map[string]any{"connectionId": id, "status": verificationStatus, "safeCode": safeCode, "httpStatus": status, "baseUrlHost": host(c.BaseURL), "modelId": c.ModelID, "capabilities": checks, "verifiedAt": time.Now()})
}

type connectionRequest struct {
	Name         string                   `json:"name"`
	Provider     string                   `json:"provider"`
	Protocol     string                   `json:"protocol"`
	BaseURL      string                   `json:"baseUrl"`
	APIKey       string                   `json:"apiKey"`
	ModelID      string                   `json:"modelId"`
	Capabilities *model.ModelCapabilities `json:"capabilities"`
}

func normalizedProvider(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "CUSTOM"
	}
	return value
}

func (s *Server) validateConnection(ctx context.Context, req connectionRequest) error {
	_, err := s.validatedBase(ctx, req)
	return err
}
func (s *Server) validatedBase(ctx context.Context, req connectionRequest) (string, error) {
	if strings.TrimSpace(req.Name) == "" || len(req.Name) > 120 {
		return "", errors.New("CONNECTION_NAME_INVALID")
	}
	if provider := normalizedProvider(req.Provider); len(provider) > 64 || strings.ContainsAny(provider, " \t\r\n") {
		return "", errors.New("PROVIDER_INVALID")
	}
	if req.Protocol != "OPENAI_COMPATIBLE" {
		return "", errors.New("PROTOCOL_INVALID")
	}
	if strings.TrimSpace(req.APIKey) == "" {
		return "", errors.New("API_KEY_REQUIRED")
	}
	if len(req.APIKey) > 512 {
		return "", errors.New("API_KEY_INVALID")
	}
	if strings.TrimSpace(req.ModelID) == "" || len(req.ModelID) > 128 {
		return "", errors.New("MODEL_ID_INVALID")
	}
	normalized, err := model.NormalizeBaseURL(req.BaseURL)
	if err != nil {
		return "", errors.New("BASE_URL_INVALID")
	}
	if err := s.models.ValidateBaseURL(ctx, normalized); err != nil {
		return "", errors.New("BASE_URL_UNSAFE_OR_UNREACHABLE")
	}
	return normalized, nil
}

func (s *Server) upload(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, s.cfg.MaxUploadBytes+1024)
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		writeError(w, 400, "UPLOAD_INVALID")
		return
	}
	multipartFile, header, err := r.FormFile("file")
	if err != nil {
		writeError(w, 400, "FILE_REQUIRED")
		return
	}
	defer multipartFile.Close()
	saved, err := s.files.SaveMultipart(r.Context(), header)
	if err != nil {
		writeError(w, 400, "UPLOAD_REJECTED")
		return
	}
	id, err := s.store.CreateUpload(r.Context(), currentUser(r.Context()).ID, model.FileObject{OriginalName: saved.OriginalName, MimeType: saved.MimeType, Size: saved.Size, SHA256: saved.SHA256, StorageKey: saved.StorageKey}, time.Now().Add(24*time.Hour))
	if err != nil {
		if removeErr := s.files.Remove(saved.StorageKey); removeErr != nil {
			writeError(w, http.StatusServiceUnavailable, "UPLOAD_CLEANUP_FAILED")
			return
		}
		writeError(w, 500, "UPLOAD_PERSIST_FAILED")
		return
	}
	writeJSON(w, 201, map[string]any{"uploadId": id, "file": map[string]any{"name": saved.OriginalName, "mimeType": saved.MimeType, "size": saved.Size, "sha256": saved.SHA256}})
}
func (s *Server) createMission(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Title, Description, Message string
		UploadIDs                   []string `json:"uploadIds"`
		ModelConnectionID           *int64   `json:"modelConnectionId"`
	}
	if !decode(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.Message) == "" {
		writeError(w, 400, "FIRST_MESSAGE_REQUIRED")
		return
	}
	if req.Title == "" {
		req.Title = firstLine(req.Message)
	}
	missionID, messageID, runID, err := s.store.CreateMissionAtomic(r.Context(), currentUser(r.Context()).ID, req.Title, req.Description, req.Message, req.UploadIDs, req.ModelConnectionID)
	if err != nil {
		writeError(w, 400, safeStoreError(err))
		return
	}
	status, statusErr := s.store.AgentRunStatus(r.Context(), currentUser(r.Context()).ID, runID)
	if statusErr != nil {
		writeError(w, 500, "AGENT_RUN_STATE_UNAVAILABLE")
		return
	}
	writeJSON(w, 201, map[string]any{"missionId": missionID, "messageId": messageID, "agentRunId": runID, "status": status})
}
func (s *Server) listMissions(w http.ResponseWriter, r *http.Request) {
	items, err := s.store.ListMissions(r.Context(), currentUser(r.Context()).ID)
	if err != nil {
		writeError(w, 500, "MISSION_LIST_FAILED")
		return
	}
	writeJSON(w, 200, items)
}
func (s *Server) missionFeedback(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	items, err := s.store.MissionFeedback(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, items)
}

func (s *Server) listReviewMissions(w http.ResponseWriter, r *http.Request) {
	items, err := s.store.ListReviewMissions(r.Context(), currentUser(r.Context()).ID)
	if err != nil {
		writeError(w, 500, "REVIEW_MISSION_LIST_FAILED")
		return
	}
	writeJSON(w, 200, items)
}

func (s *Server) getReviewMission(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	reviewer := currentUser(r.Context()).ID
	mission, err := s.store.GetMissionForReviewer(r.Context(), reviewer, id)
	if err != nil {
		writeNotFound(w, "REVIEW_MISSION_NOT_FOUND")
		return
	}
	draft, draftErr := s.store.CurrentDraftForReviewer(r.Context(), reviewer, id)
	if draftErr != nil && !errors.Is(draftErr, pgx.ErrNoRows) {
		writeError(w, 500, "REVIEW_MISSION_STATE_UNAVAILABLE")
		return
	}
	feedback, feedbackErr := s.store.ReviewMissionFeedback(r.Context(), reviewer, id)
	if feedbackErr != nil {
		writeError(w, 500, "REVIEW_MISSION_STATE_UNAVAILABLE")
		return
	}
	writeJSON(w, 200, map[string]any{"mission": mission, "currentDraft": draftOrNil(draft), "feedback": feedback})
}

func (s *Server) reviewMissionFeedback(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	items, err := s.store.ReviewMissionFeedback(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "REVIEW_MISSION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, items)
}

func (s *Server) createReviewFeedback(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var req struct {
		SubmissionVersion int                         `json:"submissionVersion"`
		Rating            int                         `json:"rating"`
		Summary           string                      `json:"summary"`
		Items             []model.MissionFeedbackItem `json:"items"`
	}
	if !decode(w, r, &req) {
		return
	}
	feedback, err := s.store.CreateMissionFeedback(r.Context(), currentUser(r.Context()).ID, id, req.SubmissionVersion, req.Rating, req.Summary, req.Items)
	if err != nil {
		switch {
		case errors.Is(err, pgx.ErrNoRows):
			writeNotFound(w, "REVIEW_MISSION_NOT_FOUND")
		case strings.HasPrefix(err.Error(), "FEEDBACK_"):
			writeError(w, http.StatusBadRequest, err.Error())
		default:
			writeError(w, http.StatusInternalServerError, "REVIEW_FEEDBACK_CREATE_FAILED")
		}
		return
	}
	writeJSON(w, http.StatusCreated, feedback)
}

func (s *Server) getMission(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	owner := currentUser(r.Context()).ID
	m, err := s.store.GetMission(r.Context(), owner, id)
	if err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	messages, err := s.store.Messages(r.Context(), owner, id)
	if err != nil {
		writeError(w, 500, "MISSION_STATE_UNAVAILABLE")
		return
	}
	files, err := s.store.MissionFiles(r.Context(), owner, id)
	if err != nil {
		writeError(w, 500, "MISSION_STATE_UNAVAILABLE")
		return
	}
	draft, err := s.store.CurrentDraft(r.Context(), owner, id)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		writeError(w, 500, "MISSION_STATE_UNAVAILABLE")
		return
	}
	locked, lockedErr := s.store.CurrentLockedSpecification(r.Context(), owner, id)
	if lockedErr != nil && !errors.Is(lockedErr, pgx.ErrNoRows) {
		writeError(w, 500, "MISSION_STATE_UNAVAILABLE")
		return
	}
	jobs, err := s.store.Jobs(r.Context(), owner, id)
	if err != nil {
		writeError(w, 500, "MISSION_STATE_UNAVAILABLE")
		return
	}
	artifacts, err := s.store.Artifacts(r.Context(), owner, id)
	if err != nil {
		writeError(w, 500, "MISSION_STATE_UNAVAILABLE")
		return
	}
	feedback, err := s.store.MissionFeedback(r.Context(), owner, id)
	if err != nil {
		writeError(w, 500, "MISSION_STATE_UNAVAILABLE")
		return
	}
	var lockedPayload any
	if lockedErr == nil {
		lockedPayload = locked
	}
	writeJSON(w, 200, map[string]any{"mission": m, "messages": messages, "files": files, "currentDraft": draftOrNil(draft), "lockedSpecification": lockedPayload, "generationJobs": jobs, "artifacts": artifacts, "feedback": feedback})
}
func (s *Server) messages(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	items, err := s.store.Messages(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, items)
}
func (s *Server) questions(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	items, err := s.store.Questions(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, items)
}
func (s *Server) agentRuns(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	items, err := s.store.AgentRuns(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, items)
}
func (s *Server) answerQuestion(w http.ResponseWriter, r *http.Request) {
	questionID := chi.URLParam(r, "id")
	if _, err := uuid.Parse(questionID); err != nil {
		writeError(w, 400, "QUESTION_ID_INVALID")
		return
	}
	var req struct {
		SelectedValues []string `json:"selectedValues"`
		TextAnswer     string   `json:"textAnswer"`
	}
	if !decode(w, r, &req) {
		return
	}
	if len(req.SelectedValues) == 0 && strings.TrimSpace(req.TextAnswer) == "" {
		writeError(w, 400, "ANSWER_REQUIRED")
		return
	}
	answerID, runID, err := s.store.AnswerQuestion(r.Context(), currentUser(r.Context()).ID, questionID, req.TextAnswer, req.SelectedValues)
	if err != nil {
		if errors.Is(err, model.ErrQuestionAnswerInvalid) {
			writeError(w, http.StatusBadRequest, "QUESTION_ANSWER_INVALID")
			return
		}
		writeError(w, 404, "QUESTION_NOT_FOUND")
		return
	}
	writeJSON(w, 202, map[string]string{"answerId": answerID, "agentRunId": runID})
}
func (s *Server) cancelAgent(w http.ResponseWriter, r *http.Request) {
	runID := chi.URLParam(r, "id")
	if _, err := uuid.Parse(runID); err != nil {
		writeError(w, 400, "AGENT_RUN_ID_INVALID")
		return
	}
	run, err := s.store.CancelAgent(r.Context(), currentUser(r.Context()).ID, runID)
	if err != nil {
		writeError(w, 404, "AGENT_RUN_NOT_CANCELLABLE")
		return
	}
	writeJSON(w, 200, run)
}
func (s *Server) sendMessage(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var req struct {
		Content string `json:"content"`
	}
	if !decode(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.Content) == "" {
		writeError(w, 400, "MESSAGE_REQUIRED")
		return
	}
	owner := currentUser(r.Context()).ID
	if _, err := s.store.GetMission(r.Context(), owner, id); err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	msg, run, err := s.store.CreateMessageAndRun(r.Context(), owner, id, req.Content)
	if err != nil {
		writeError(w, 400, safeStoreError(err))
		return
	}
	status, statusErr := s.store.AgentRunStatus(r.Context(), owner, run)
	if statusErr != nil {
		writeError(w, 500, "AGENT_RUN_STATE_UNAVAILABLE")
		return
	}
	writeJSON(w, 202, map[string]any{"messageId": msg, "agentRunId": run, "status": status})
}
func (s *Server) selectConnection(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var req struct {
		ConnectionID *int64 `json:"connectionId"`
	}
	if !decode(w, r, &req) {
		return
	}
	owner := currentUser(r.Context()).ID
	newRunID, err := s.store.SetMissionConnection(r.Context(), owner, id, req.ConnectionID)
	if err != nil {
		writeError(w, 400, safeStoreError(err))
		return
	}
	writeJSON(w, 200, map[string]any{"missionId": id, "modelConnectionId": req.ConnectionID, "agentRunId": newRunID})
}
func (s *Server) addMissionFile(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if _, err := s.store.GetMission(r.Context(), currentUser(r.Context()).ID, id); err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, s.cfg.MaxUploadBytes+1024)
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		writeError(w, 400, "UPLOAD_INVALID")
		return
	}
	multipartFile, header, err := r.FormFile("file")
	if err != nil {
		writeError(w, 400, "FILE_REQUIRED")
		return
	}
	defer multipartFile.Close()
	saved, err := s.files.SaveMultipart(r.Context(), header)
	if err != nil {
		writeError(w, 400, "UPLOAD_REJECTED")
		return
	}
	fileID, err := s.store.AddMissionFile(r.Context(), currentUser(r.Context()).ID, id, model.FileObject{OriginalName: saved.OriginalName, MimeType: saved.MimeType, Size: saved.Size, SHA256: saved.SHA256, StorageKey: saved.StorageKey})
	if err != nil {
		if removeErr := s.files.Remove(saved.StorageKey); removeErr != nil {
			writeError(w, http.StatusServiceUnavailable, "MISSION_FILE_CLEANUP_FAILED")
			return
		}
		writeError(w, 500, "MISSION_FILE_PERSIST_FAILED")
		return
	}
	writeJSON(w, 201, map[string]any{"fileId": fileID, "name": saved.OriginalName, "sha256": saved.SHA256})
}
func (s *Server) missionFiles(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	files, err := s.store.MissionFiles(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, files)
}
func (s *Server) events(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	owner := currentUser(r.Context()).ID
	afterRaw := r.URL.Query().Get("after")
	after := int64(0)
	if afterRaw != "" {
		parsed, err := strconv.ParseInt(afterRaw, 10, 64)
		if err != nil || parsed < 0 {
			writeError(w, http.StatusBadRequest, "EVENT_CURSOR_INVALID")
			return
		}
		after = parsed
	}
	items, err := s.store.Events(r.Context(), owner, id, after)
	if err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	if _, supportsFlush := w.(http.Flusher); !supportsFlush {
		writeError(w, 500, "SSE_UNSUPPORTED")
		return
	}
	for _, event := range items {
		if err := writeSSE(w, event); err != nil {
			log.Printf("SSE write error mission_id=%d error=%v", id, err)
			return
		}
		after = event.ID
	}
	if err := http.NewResponseController(w).Flush(); err != nil && !errors.Is(err, http.ErrNotSupported) {
		log.Printf("SSE flush error mission_id=%d error=%v", id, err)
		return
	}
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ticker.C:
			next, err := s.store.Events(r.Context(), owner, id, after)
			if err != nil {
				return
			}
			for _, event := range next {
				if err := writeSSE(w, event); err != nil {
					log.Printf("SSE write error mission_id=%d error=%v", id, err)
					return
				}
				after = event.ID
			}
			if err := http.NewResponseController(w).Flush(); err != nil && !errors.Is(err, http.ErrNotSupported) {
				log.Printf("SSE flush error mission_id=%d error=%v", id, err)
				return
			}
		}
	}
}
func (s *Server) currentDraft(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	draft, err := s.store.CurrentDraft(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "PLANNING_DRAFT_NOT_FOUND")
		return
	}
	writeJSON(w, 200, draft)
}
func (s *Server) drafts(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	items, err := s.store.Drafts(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, items)
}
func (s *Server) approveDraft(w http.ResponseWriter, r *http.Request) {
	draftID := chi.URLParam(r, "draftId")
	spec, err := s.store.ApproveDraft(r.Context(), currentUser(r.Context()).ID, draftID)
	if err != nil {
		writeError(w, 400, safeStoreError(err))
		return
	}
	writeJSON(w, 201, map[string]any{"lockedSpecification": spec})
}
func (s *Server) jobs(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	items, err := s.store.Jobs(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, items)
}
func (s *Server) createGenerationJob(w http.ResponseWriter, r *http.Request) {
	missionID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var request struct {
		SpecificationID      string `json:"specificationId"`
		SpecificationVersion int    `json:"specificationVersion"`
	}
	if !decode(w, r, &request) {
		return
	}
	result, err := s.store.CreateGenerationJob(r.Context(), currentUser(r.Context()).ID, missionID, request.SpecificationID, request.SpecificationVersion)
	if err != nil {
		switch {
		case errors.Is(err, database.ErrGenerationMissionNotFound), errors.Is(err, database.ErrGenerationSpecificationNotFound):
			writeNotFound(w, err.Error())
		case errors.Is(err, database.ErrGenerationVersionMismatch), errors.Is(err, database.ErrGenerationTemplateInvalid), errors.Is(err, database.ErrGenerationTemplateProfileNotReady), errors.Is(err, database.ErrGenerationMaterialsNotReady), errors.Is(err, database.ErrGenerationActiveConflict):
			writeError(w, http.StatusConflict, err.Error())
		default:
			writeError(w, http.StatusInternalServerError, "GENERATION_REQUEST_FAILED")
		}
		return
	}
	status := http.StatusOK
	if result.Created {
		status = http.StatusCreated
	}
	writeJSON(w, status, map[string]any{"generationJob": result.Job, "created": result.Created})
}
func (s *Server) job(w http.ResponseWriter, r *http.Request) {
	jobID := chi.URLParam(r, "id")
	if jobID == "" {
		writeError(w, 400, "ID_INVALID")
		return
	}
	missionID, ok := queryID(w, r, "missionId")
	if !ok {
		return
	}
	j, err := s.store.Job(r.Context(), currentUser(r.Context()).ID, missionID, jobID)
	if err != nil {
		writeNotFound(w, "GENERATION_JOB_NOT_FOUND")
		return
	}
	writeJSON(w, 200, j)
}
func (s *Server) cancelGeneration(w http.ResponseWriter, r *http.Request) {
	if err := s.store.CancelGeneration(r.Context(), currentUser(r.Context()).ID, chi.URLParam(r, "id")); err != nil {
		writeError(w, 404, "GENERATION_JOB_NOT_CANCELLABLE")
		return
	}
	writeJSON(w, 202, map[string]string{"status": "CANCEL_REQUESTED"})
}
func (s *Server) artifacts(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	items, err := s.store.Artifacts(r.Context(), currentUser(r.Context()).ID, id)
	if err != nil {
		writeNotFound(w, "MISSION_NOT_FOUND")
		return
	}
	writeJSON(w, 200, items)
}
func (s *Server) download(w http.ResponseWriter, r *http.Request) {
	artifact, err := s.store.Artifact(r.Context(), currentUser(r.Context()).ID, chi.URLParam(r, "id"))
	if err != nil {
		writeNotFound(w, "ARTIFACT_NOT_FOUND")
		return
	}
	if artifact.Size <= 0 || artifact.Size != artifact.File.Size || !strings.EqualFold(artifact.SHA256, artifact.File.SHA256) {
		s.failClosedArtifactDownload(w, r, artifact, errors.New("artifact metadata mismatch"))
		return
	}
	file, err := s.files.OpenVerified(r.Context(), storage.File{StorageKey: artifact.File.StorageKey, Size: artifact.File.Size, SHA256: artifact.File.SHA256})
	if err != nil {
		s.failClosedArtifactDownload(w, r, artifact, err)
		return
	}
	defer func() {
		if closeErr := file.Close(); closeErr != nil {
			log.Printf("download cleanup error operation=close artifact_id=%s storage_key=%s error=%v", artifact.ID, artifact.File.StorageKey, closeErr)
			if cleanupErr := s.invalidateAfterDownloadWriteFailure(r, artifact); cleanupErr != nil {
				log.Printf("download cleanup error operation=invalidate_after_close artifact_id=%s error=%v", artifact.ID, cleanupErr)
			}
		}
	}()
	if s.afterDownloadOpenHook != nil {
		if err := s.afterDownloadOpenHook(); err != nil {
			s.failClosedArtifactDownload(w, r, artifact, err)
			return
		}
	}
	// Read and verify the bytes from the same protected descriptor before any
	// HTTP success headers are emitted. A truncate or in-place replacement after
	// OpenVerified must therefore fail closed instead of producing a 200 with a
	// short or altered body.
	var body bytes.Buffer
	if _, err := io.Copy(&body, io.LimitReader(file, artifact.Size+1)); err != nil || int64(body.Len()) != artifact.Size {
		s.failClosedArtifactDownload(w, r, artifact, errors.New("artifact changed while preparing download"))
		return
	}
	digest := sha256.Sum256(body.Bytes())
	if !strings.EqualFold(hex.EncodeToString(digest[:]), artifact.SHA256) {
		s.failClosedArtifactDownload(w, r, artifact, errors.New("artifact checksum changed while preparing download"))
		return
	}
	// A removed path can leave an already-open descriptor readable on Unix.
	// Confirm the storage entry still resolves to the same receipt before a
	// 200 is sent, while the buffered bytes remain the ones read from that
	// protected descriptor.
	if err := s.files.Verify(r.Context(), storage.File{StorageKey: artifact.File.StorageKey, Size: artifact.File.Size, SHA256: artifact.File.SHA256}); err != nil {
		s.failClosedArtifactDownload(w, r, artifact, err)
		return
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		s.failClosedArtifactDownload(w, r, artifact, err)
		return
	}
	protected := make([]byte, len(body.Bytes()))
	if _, err := io.ReadFull(file, protected); err != nil || !bytes.Equal(protected, body.Bytes()) {
		s.failClosedArtifactDownload(w, r, artifact, errors.New("artifact protected handle changed before send"))
		return
	}
	// This is the final complete verification. The send seam intentionally
	// follows it and precedes WriteHeader/Write: the response only ever writes
	// the receipt-checked protected bytes, never a second path read.
	if s.beforeDownloadSendHook != nil {
		if err := s.beforeDownloadSendHook(); err != nil {
			s.failClosedArtifactDownload(w, r, artifact, err)
			return
		}
	}
	w.Header().Set("Content-Type", artifact.ContentType)
	w.Header().Set("Content-Disposition", `attachment; filename="`+strings.ReplaceAll(artifact.File.OriginalName, "\"", "")+`"`)
	w.Header().Set("Content-Length", strconv.FormatInt(artifact.Size, 10))
	w.WriteHeader(http.StatusOK)
	n, err := w.Write(body.Bytes())
	if err != nil || n != len(body.Bytes()) {
		writeErr := err
		if writeErr == nil {
			writeErr = io.ErrShortWrite
		}
		if s.downloadSendErrorHook != nil {
			s.downloadSendErrorHook(writeErr)
		}
		// Headers may already be committed, so do not append a second JSON
		// response. The client observes the short Content-Length response; the
		// database is still compensated and the physical key is cleaned only if
		// no other file object references it.
		if cleanupErr := s.invalidateAfterDownloadWriteFailure(r, artifact); cleanupErr != nil {
			log.Printf("download cleanup error operation=invalidate_after_write artifact_id=%s error=%v", artifact.ID, cleanupErr)
		}
	}
	if flushErr := http.NewResponseController(w).Flush(); flushErr != nil && !errors.Is(flushErr, http.ErrNotSupported) {
		if s.downloadSendErrorHook != nil {
			s.downloadSendErrorHook(flushErr)
		}
		if cleanupErr := s.invalidateAfterDownloadWriteFailure(r, artifact); cleanupErr != nil {
			log.Printf("download cleanup error operation=invalidate_after_flush artifact_id=%s error=%v", artifact.ID, cleanupErr)
		}
	}
}

func (s *Server) invalidateAfterDownloadWriteFailure(r *http.Request, artifact model.Artifact) error {
	cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ownerID := currentUser(r.Context()).ID
	if err := s.store.InvalidateGenerationArtifact(cleanupCtx, ownerID, artifact.MissionID, artifact.GenerationJobID, artifact.ID, map[string]string{"code": "ARTIFACT_DOWNLOAD_WRITE_FAILED"}); err != nil {
		return err
	}
	referenced, err := s.store.StorageKeyReferenced(cleanupCtx, artifact.File.StorageKey)
	if err != nil || referenced {
		return err
	}
	return s.files.Remove(artifact.File.StorageKey)
}

func (s *Server) failClosedArtifactDownload(w http.ResponseWriter, r *http.Request, artifact model.Artifact, _ error) {
	cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err := s.store.InvalidateGenerationArtifact(cleanupCtx, currentUser(r.Context()).ID, artifact.MissionID, artifact.GenerationJobID, artifact.ID, map[string]string{"code": "ARTIFACT_DOWNLOAD_INTEGRITY_FAILED"})
	if err != nil {
		// The database outcome is unknown; preserve any physical bytes until a
		// later reconciliation can determine whether they are still referenced.
		writeError(w, http.StatusServiceUnavailable, "ARTIFACT_INTEGRITY_UNRESOLVED")
		return
	}
	if referenced, referenceErr := s.store.StorageKeyReferenced(cleanupCtx, artifact.File.StorageKey); referenceErr != nil {
		writeError(w, http.StatusServiceUnavailable, "ARTIFACT_INTEGRITY_UNRESOLVED")
		return
	} else if !referenced {
		if removeErr := s.files.Remove(artifact.File.StorageKey); removeErr != nil {
			writeError(w, http.StatusServiceUnavailable, "ARTIFACT_INTEGRITY_UNRESOLVED")
			return
		}
	}
	writeError(w, http.StatusConflict, "ARTIFACT_FILE_INTEGRITY_FAILED")
}

func (s *Server) audit(ctx context.Context, requestID uuid.UUID, actor, mission, connectionID int64, c model.ModelConnection, purpose string, status int, latency time.Duration) error {
	_, err := s.db.Exec(ctx, `INSERT INTO execution_audits(request_id,actor_user_id,mission_id,model_connection_id,protocol,base_url_host,model_id,purpose,credential_source,http_status,latency_ms) VALUES($1,$2,NULLIF($3,0),$4,$5,$6,$7,$8,'USER_BYOK',$9,$10)`, requestID, actor, mission, connectionID, c.Protocol, host(c.BaseURL), c.ModelID, purpose, status, latency.Milliseconds())
	return err
}

func (s *Server) requireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := s.token(r)
		if token == "" {
			writeError(w, 401, "AUTH_REQUIRED")
			return
		}
		u, err := s.store.UserBySessionHash(r.Context(), auth.TokenHash(token))
		if err != nil {
			writeError(w, 401, "SESSION_EXPIRED")
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), userKey, u)))
	})
}
func (s *Server) requireTeacher(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if currentUser(r.Context()).Role != model.RoleTeacher {
			writeError(w, http.StatusForbidden, "TEACHER_ROLE_REQUIRED")
			return
		}
		next.ServeHTTP(w, r)
	})
}
func (s *Server) requireResearcher(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if currentUser(r.Context()).Role != model.RoleResearcher {
			writeError(w, http.StatusForbidden, "RESEARCHER_ROLE_REQUIRED")
			return
		}
		next.ServeHTTP(w, r)
	})
}
func (s *Server) token(r *http.Request) string {
	if token := auth.Bearer(r.Header.Get("Authorization")); token != "" {
		return token
	}
	if cookie, err := r.Cookie(s.cfg.SessionCookie); err == nil {
		return cookie.Value
	}
	return ""
}
func currentUser(ctx context.Context) model.User { u, _ := ctx.Value(userKey).(model.User); return u }

func decode(w http.ResponseWriter, r *http.Request, out any) bool {
	decoder := json.NewDecoder(io.LimitReader(r.Body, 2<<20))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(out); err != nil {
		writeError(w, 400, "JSON_INVALID")
		return false
	}
	return true
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(value); err != nil {
		log.Printf("http response error operation=encode_json status=%d error=%v", status, err)
	}
}
func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]any{"error": map[string]string{"code": code}})
}
func writeNotFound(w http.ResponseWriter, code string) { writeError(w, 404, code) }
func pathID(w http.ResponseWriter, r *http.Request, name string) (int64, bool) {
	id, err := strconv.ParseInt(chi.URLParam(r, name), 10, 64)
	if err != nil || id <= 0 {
		writeError(w, 400, "ID_INVALID")
		return 0, false
	}
	return id, true
}
func queryID(w http.ResponseWriter, r *http.Request, name string) (int64, bool) {
	id, err := strconv.ParseInt(r.URL.Query().Get(name), 10, 64)
	if err != nil || id <= 0 {
		writeError(w, 400, "MISSION_ID_REQUIRED")
		return 0, false
	}
	return id, true
}
func firstLine(v string) string {
	line := strings.TrimSpace(strings.SplitN(v, "\n", 2)[0])
	if len(line) > 120 {
		return line[:120]
	}
	return line
}
func safeStoreError(err error) string {
	if errors.Is(err, pgx.ErrNoRows) {
		return "NOT_FOUND"
	}
	if err == nil {
		return "REQUEST_FAILED"
	}
	if strings.HasPrefix(err.Error(), "upload ") {
		return "UPLOAD_UNAVAILABLE"
	}
	if strings.Contains(err.Error(), "MODEL_CONNECTION") {
		return err.Error()
	}
	return "REQUEST_REJECTED"
}
func safeErrorCode(err error) string {
	if err == nil {
		return ""
	}
	if x, ok := err.(*model.HTTPError); ok {
		switch x.Status {
		case 401, 403:
			return "AUTHENTICATION_FAILED"
		case 404:
			return "MODEL_NOT_FOUND"
		case 408:
			return "MODEL_TIMEOUT"
		case 429:
			return "RATE_LIMITED"
		case 402:
			return "INSUFFICIENT_BALANCE"
		case 400:
			return "MODEL_REQUEST_REJECTED"
		default:
			if x.Status >= 500 && x.Status <= 599 {
				return "MODEL_PROVIDER_ERROR"
			}
			return "MODEL_PROVIDER_ERROR"
		}
	}
	if errors.Is(err, model.ErrTimeout) {
		return "MODEL_TIMEOUT"
	}
	message := strings.TrimSpace(err.Error())
	for _, prefix := range []string{"MODEL_", "SSRF_", "REDIRECT_"} {
		if strings.HasPrefix(message, prefix) {
			return strings.TrimSpace(strings.SplitN(message, ":", 2)[0])
		}
	}
	if strings.HasPrefix(message, "model response format:") {
		return "MODEL_RESPONSE_FORMAT_INVALID"
	}
	return "MODEL_PROVIDER_ERROR"
}
func host(raw string) string {
	value, err := model.NormalizeBaseURL(raw)
	if err != nil {
		return ""
	}
	u, err := url.Parse(value)
	if err != nil {
		return ""
	}
	return u.Host
}
func writeSSE(w http.ResponseWriter, event model.ActivityEvent) error {
	data, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("marshal SSE event: %w", err)
	}
	_, err = fmt.Fprintf(w, "id: %d\nevent: %s\ndata: %s\n\n", event.ID, event.EventType, data)
	return err
}
func draftOrNil(d model.PlanningDraft) any {
	if d.ID == "" {
		return nil
	}
	return d
}
