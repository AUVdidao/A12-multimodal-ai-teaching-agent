package config

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Addr                          string
	DatabaseURL                   string
	StorageRoot                   string
	SessionCookie                 string
	SessionTTL                    time.Duration
	EncryptionKey                 []byte
	RAGBaseURL                    string
	RAGBearerToken                string
	PPTEngineBaseURL              string
	PPTEngineArtifactRoot         string
	PPTEngineSharedStorageRoot    string
	ModelRequestTimeout           time.Duration
	ToolRequestTimeout            time.Duration
	AgentRunTimeout               time.Duration
	JobLeaseDuration              time.Duration
	MaxUploadBytes                int64
	MaxModelResponseBytes         int64
	MaxParserResponseBytes        int64
	RAGSearchPath                 string
	RAGReadPath                   string
	TemplateCapabilityURL         string
	TemplateCapabilityBearerToken string
	ParserURL                     string
	ParserRequestTimeout          time.Duration
	ParserInterval                time.Duration
	PPTEngineRequestTimeout       time.Duration
	EnableGenerationWorker        bool
	CORSOrigins                   []string
}

func Load() (Config, error) {
	key, err := encryptionKey()
	if err != nil {
		return Config{}, err
	}
	return Config{
		Addr:                       env("LESSONFORGE_ADDR", ":8090"),
		DatabaseURL:                env("LESSONFORGE_DATABASE_URL", "postgres://lessonforge:lessonforge@localhost:54329/lessonforge?sslmode=disable"),
		StorageRoot:                env("LESSONFORGE_STORAGE_ROOT", "./data/files"),
		SessionCookie:              env("LESSONFORGE_SESSION_COOKIE", "lessonforge_session"),
		SessionTTL:                 durationEnv("LESSONFORGE_SESSION_TTL", 7*24*time.Hour),
		EncryptionKey:              key,
		RAGBaseURL:                 env("LESSONFORGE_RAG_BASE_URL", ""),
		RAGBearerToken:             env("LESSONFORGE_RAG_BEARER_TOKEN", ""),
		PPTEngineBaseURL:           env("LESSONFORGE_PPT_ENGINE_BASE_URL", ""),
		PPTEngineArtifactRoot:      env("LESSONFORGE_PPT_ENGINE_ARTIFACT_ROOT", ""),
		PPTEngineSharedStorageRoot: env("LESSONFORGE_PPT_ENGINE_SHARED_STORAGE_ROOT", ""),
		ModelRequestTimeout:        durationEnv("LESSONFORGE_MODEL_TIMEOUT", 90*time.Second),
		ToolRequestTimeout:         durationEnv("LESSONFORGE_TOOL_TIMEOUT", 30*time.Second),
		AgentRunTimeout:            durationEnv("LESSONFORGE_AGENT_TIMEOUT", 5*time.Minute),
		JobLeaseDuration:           durationEnv("LESSONFORGE_JOB_LEASE", 30*time.Second),
		MaxUploadBytes:             int64Env("LESSONFORGE_MAX_UPLOAD_BYTES", 200*1024*1024),
		MaxModelResponseBytes:      int64Env("LESSONFORGE_MAX_MODEL_RESPONSE_BYTES", 4*1024*1024),
		MaxParserResponseBytes:     int64Env("LESSONFORGE_MAX_PARSER_RESPONSE_BYTES", 16*1024*1024),
		// No legacy Workflow/Kimi endpoint is a safe default. The existing Java
		// RAG contract is project-scoped and must be explicitly mapped/configured
		// before the Mission-first backend may call it.
		RAGSearchPath:                 env("LESSONFORGE_RAG_SEARCH_PATH", ""),
		RAGReadPath:                   env("LESSONFORGE_RAG_READ_PATH", ""),
		TemplateCapabilityURL:         env("LESSONFORGE_TEMPLATE_CAPABILITY_URL", ""),
		TemplateCapabilityBearerToken: env("LESSONFORGE_TEMPLATE_CAPABILITY_BEARER_TOKEN", ""),
		ParserURL:                     env("LESSONFORGE_PARSER_URL", ""),
		ParserRequestTimeout:          durationEnv("LESSONFORGE_PARSER_TIMEOUT", 5*time.Minute),
		ParserInterval:                durationEnv("LESSONFORGE_PARSER_INTERVAL", 5*time.Second),
		PPTEngineRequestTimeout:       durationEnv("LESSONFORGE_PPT_ENGINE_TIMEOUT", 5*time.Minute),
		// Generation is an explicit downstream opt-in. The upstream Mission to
		// Locked Specification service must not touch Generation/PPT Engine state
		// unless a later deployment deliberately enables it.
		EnableGenerationWorker: boolEnv("LESSONFORGE_ENABLE_GENERATION_WORKER", false),
		CORSOrigins:            splitCSV(env("LESSONFORGE_CORS_ORIGINS", "null,http://localhost:4173,http://127.0.0.1:4173,http://localhost:5173,http://127.0.0.1:5173")),
	}, nil
}

func splitCSV(raw string) []string {
	var result []string
	for _, value := range strings.Split(raw, ",") {
		if value = strings.TrimSpace(value); value != "" {
			result = append(result, value)
		}
	}
	return result
}

func encryptionKey() ([]byte, error) {
	raw := os.Getenv("LESSONFORGE_ENCRYPTION_KEY")
	if raw == "" {
		return nil, errors.New("LESSONFORGE_ENCRYPTION_KEY is required and must be a persistent 32-byte key")
	}
	if decoded, err := base64.RawStdEncoding.DecodeString(raw); err == nil && len(decoded) == 32 {
		return decoded, nil
	}
	if decoded, err := hex.DecodeString(raw); err == nil && len(decoded) == 32 {
		return decoded, nil
	}
	return nil, fmt.Errorf("LESSONFORGE_ENCRYPTION_KEY must be 32 bytes encoded as raw base64 or hex")
}

func GenerateEncryptionKey() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawStdEncoding.EncodeToString(b), nil
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func durationEnv(name string, fallback time.Duration) time.Duration {
	if value := os.Getenv(name); value != "" {
		if parsed, err := time.ParseDuration(value); err == nil && parsed > 0 {
			return parsed
		}
	}
	return fallback
}

func int64Env(name string, fallback int64) int64 {
	if value := os.Getenv(name); value != "" {
		if parsed, err := strconv.ParseInt(value, 10, 64); err == nil && parsed > 0 {
			return parsed
		}
	}
	return fallback
}

func boolEnv(name string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}
