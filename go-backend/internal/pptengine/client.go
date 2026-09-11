package pptengine

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

var ErrNotConfigured = errors.New("PPT Engine adapter is not configured")
var ErrArtifactBridgeNotConfigured = errors.New("PPT Engine artifact bridge is not configured")

type Client struct {
	baseURL      string
	artifactRoot string
	httpClient   *http.Client
}

func NewClient(baseURL, artifactRoot string, timeout time.Duration) *Client {
	return &Client{baseURL: strings.TrimRight(baseURL, "/"), artifactRoot: strings.TrimSpace(artifactRoot), httpClient: &http.Client{Timeout: timeout}}
}

type ExecuteResult struct {
	Status      string            `json:"status"`
	Artifacts   []ArtifactReceipt `json:"artifacts"`
	Diagnostics []any             `json:"diagnostics"`
	Feedback    []any             `json:"feedback"`
	Raw         json.RawMessage   `json:"-"`
}
type ArtifactReceipt struct {
	ArtifactID   string `json:"artifactId"`
	ArtifactType string `json:"artifactType"`
	StorageKey   string `json:"storageKey"`
	SHA256       string `json:"sha256"`
	Size         int64  `json:"fileSize"`
	ContentType  string `json:"mediaType"`
}

func (c *Client) Execute(ctx context.Context, request json.RawMessage) (result ExecuteResult, err error) {
	return c.postExecute(ctx, request)
}

// ComposePlan calls the frozen Engine V2 composition boundary. The raw plan
// remains Engine-owned and is passed unchanged to execute.
type ComposePlanResult struct {
	ContractVersion     string          `json:"contractVersion"`
	PlanContractVersion string          `json:"planContractVersion"`
	FeedbackContract    string          `json:"feedbackContractVersion"`
	RequestID           string          `json:"requestId"`
	Plan                json.RawMessage `json:"plan"`
	Feedback            json.RawMessage `json:"feedback"`
	Raw                 json.RawMessage `json:"-"`
}

func (c *Client) ComposePlan(ctx context.Context, request json.RawMessage) (result ComposePlanResult, err error) {
	if c.baseURL == "" {
		return ComposePlanResult{}, ErrNotConfigured
	}
	encoded := bytes.TrimSpace(request)
	if len(encoded) == 0 {
		return ComposePlanResult{}, errors.New("PPT_ENGINE_PAYLOAD_UNAVAILABLE")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/internal/v1/compose-plan", bytes.NewReader(encoded))
	if err != nil {
		return ComposePlanResult{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return ComposePlanResult{}, errors.New("PPT_ENGINE_NETWORK_ERROR")
	}
	defer func() {
		if closeErr := resp.Body.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close PPT engine compose response: %w", closeErr))
		}
	}()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 10*1024*1024+1))
	if err != nil {
		return ComposePlanResult{}, err
	}
	if len(data) > 10*1024*1024 {
		return ComposePlanResult{}, errors.New("PPT_ENGINE_RESPONSE_TOO_LARGE")
	}
	if err := json.Unmarshal(data, &result); err != nil {
		return ComposePlanResult{}, fmt.Errorf("PPT_ENGINE_RESPONSE_INVALID: %w", err)
	}
	result.Raw = append([]byte(nil), data...)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return result, fmt.Errorf("PPT_ENGINE_HTTP_%d", resp.StatusCode)
	}
	if len(bytes.TrimSpace(result.Plan)) == 0 || bytes.Equal(bytes.TrimSpace(result.Plan), []byte("null")) {
		return result, errors.New("PPT_ENGINE_COMPOSE_PLAN_MISSING")
	}
	return result, nil
}

func (c *Client) ExecuteV2(ctx context.Context, request json.RawMessage) (ExecuteResult, error) {
	return c.postExecute(ctx, request)
}

func (c *Client) postExecute(ctx context.Context, request json.RawMessage) (result ExecuteResult, err error) {
	if c.baseURL == "" {
		return ExecuteResult{}, ErrNotConfigured
	}
	encoded := bytes.TrimSpace(request)
	if len(encoded) == 0 {
		return ExecuteResult{}, errors.New("PPT_ENGINE_PAYLOAD_UNAVAILABLE")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/internal/v2/execute", bytes.NewReader(encoded))
	if err != nil {
		return ExecuteResult{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return ExecuteResult{}, errors.New("PPT_ENGINE_NETWORK_ERROR")
	}
	defer func() {
		if closeErr := resp.Body.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close PPT engine response: %w", closeErr))
		}
	}()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 10*1024*1024+1))
	if err != nil {
		return ExecuteResult{}, err
	}
	if len(data) > 10*1024*1024 {
		return ExecuteResult{}, errors.New("PPT_ENGINE_RESPONSE_TOO_LARGE")
	}
	if err := json.Unmarshal(data, &result); err != nil {
		return ExecuteResult{}, fmt.Errorf("PPT_ENGINE_RESPONSE_INVALID: %w", err)
	}
	result.Raw = append([]byte(nil), data...)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return result, fmt.Errorf("PPT_ENGINE_HTTP_%d", resp.StatusCode)
	}
	return result, nil
}

func (c *Client) ReadArtifact(ctx context.Context, receipt ArtifactReceipt, maxBytes int64) (data []byte, err error) {
	if c.artifactRoot == "" {
		return nil, ErrArtifactBridgeNotConfigured
	}
	if receipt.StorageKey == "" || filepath.IsAbs(receipt.StorageKey) || strings.ContainsAny(receipt.StorageKey, "\\\x00") {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_KEY_INVALID")
	}
	clean := filepath.Clean(filepath.FromSlash(receipt.StorageKey))
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_KEY_INVALID")
	}
	root, err := filepath.Abs(c.artifactRoot)
	if err != nil {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_ROOT_INVALID")
	}
	root, err = filepath.EvalSymlinks(root)
	if err != nil {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_ROOT_INVALID")
	}
	path := filepath.Join(root, clean)
	pathAbs, err := filepath.Abs(path)
	if err != nil || (pathAbs != root && !strings.HasPrefix(pathAbs, root+string(filepath.Separator))) {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_KEY_INVALID")
	}
	resolvedPath, err := filepath.EvalSymlinks(pathAbs)
	if err != nil {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_NOT_FOUND")
	}
	resolvedPath, err = filepath.Abs(resolvedPath)
	if err != nil || (resolvedPath != root && !strings.HasPrefix(resolvedPath, root+string(filepath.Separator))) {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_KEY_INVALID")
	}
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}
	file, err := os.Open(resolvedPath)
	if err != nil {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_NOT_FOUND")
	}
	defer func() {
		// ReadArtifact returns the primary read/validation error to its caller;
		// a close failure is still surfaced through the returned error when the
		// operation otherwise succeeded.
		if closeErr := file.Close(); closeErr != nil && err == nil {
			err = fmt.Errorf("close Engine artifact: %w", closeErr)
		}
	}()
	if maxBytes <= 0 {
		maxBytes = 200 * 1024 * 1024
	}
	readData, readErr := io.ReadAll(io.LimitReader(file, maxBytes+1))
	if readErr != nil {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_READ_FAILED")
	}
	data = readData
	if int64(len(data)) > maxBytes || len(data) == 0 {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_SIZE_INVALID")
	}
	if int64(len(data)) != receipt.Size {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_SIZE_MISMATCH")
	}
	hash := sha256.Sum256(data)
	if receipt.SHA256 == "" || !strings.EqualFold(fmt.Sprintf("%x", hash[:]), receipt.SHA256) {
		return nil, errors.New("PPT_ENGINE_ARTIFACT_CHECKSUM_MISMATCH")
	}
	return data, nil
}
