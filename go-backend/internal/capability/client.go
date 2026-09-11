package capability

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"
)

var ErrNotConfigured = errors.New("template capability adapter is not configured")
var ErrProfileIncomplete = errors.New("TEMPLATE_PROFILE_INCOMPLETE")
var ErrUpstreamFailure = errors.New("TEMPLATE_CAPABILITY_UPSTREAM_FAILURE")
var ErrAuthorizationNotConfigured = errors.New("TEMPLATE_CAPABILITY_AUTHORIZATION_NOT_CONFIGURED")

type Profile struct {
	TemplateID             string
	TemplateFileSHA256     string
	TemplateFileVersion    string
	TemplateProfileVersion string
	EngineNativeProfile    map[string]any
}

type Client struct {
	endpoint         string
	bearerToken      string
	requireBearer    bool
	httpClient       *http.Client
	maxResponseBytes int64
}

func NewClient(endpoint string, timeout time.Duration, maxResponseBytes int64) *Client {
	return &Client{endpoint: strings.TrimSpace(endpoint), httpClient: &http.Client{Timeout: timeout}, maxResponseBytes: maxResponseBytes}
}

// NewAuthenticatedClient is used for the internal bridge to the existing
// template service. The bridge is deliberately configured, rather than
// inferred from a teacher-facing URL or a legacy provider path.
func NewAuthenticatedClient(endpoint, bearerToken string, timeout time.Duration, maxResponseBytes int64) *Client {
	return &Client{endpoint: strings.TrimSpace(endpoint), bearerToken: strings.TrimSpace(bearerToken), requireBearer: true, httpClient: &http.Client{Timeout: timeout}, maxResponseBytes: maxResponseBytes}
}

func (c *Client) Get(ctx context.Context, missionID int64) (result any, err error) {
	return c.get(ctx, 0, missionID, "")
}

// GetForOwner sends the server-owned identity pair to the configured bridge.
// The bridge must enforce that the external template/profile belongs to this
// actor; a mission id by itself is not an authorization boundary.
func (c *Client) GetForOwner(ctx context.Context, ownerID, missionID int64) (result any, err error) {
	return c.get(ctx, ownerID, missionID, "")
}

func (c *Client) get(ctx context.Context, ownerID, missionID int64, expectedTemplateSHA256 string) (result any, err error) {
	if c.endpoint == "" {
		return nil, ErrNotConfigured
	}
	if c.requireBearer && c.bearerToken == "" {
		return nil, ErrAuthorizationNotConfigured
	}
	requestBody := map[string]any{"missionId": missionID}
	if ownerID > 0 {
		requestBody["ownerUserId"] = ownerID
	}
	if expected := strings.ToLower(strings.TrimSpace(expectedTemplateSHA256)); expected != "" {
		requestBody["templateFileSha256"] = expected
	}
	body, err := json.Marshal(requestBody)
	if err != nil {
		return nil, errors.New("TEMPLATE_CAPABILITY_REQUEST_INVALID")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, errors.New("TEMPLATE_CAPABILITY_ENDPOINT_INVALID")
	}
	req.Header.Set("Content-Type", "application/json")
	if c.bearerToken != "" {
		req.Header.Set("Authorization", "Bearer "+c.bearerToken)
	}
	if ownerID > 0 {
		req.Header.Set("X-LessonForge-Actor-User-Id", strconv.FormatInt(ownerID, 10))
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, errors.New("TEMPLATE_CAPABILITY_NETWORK_ERROR")
	}
	defer func() {
		if closeErr := resp.Body.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close capability response: %w", closeErr))
		}
	}()
	limit := c.maxResponseBytes
	if limit <= 0 {
		limit = 4 * 1024 * 1024
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, errors.New("TEMPLATE_CAPABILITY_READ_FAILED")
	}
	if int64(len(data)) > limit {
		return nil, errors.New("TEMPLATE_CAPABILITY_RESPONSE_TOO_LARGE")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, ErrUpstreamFailure
	}
	if err := decodeResponse(data, &result); err != nil {
		if errors.Is(err, ErrUpstreamFailure) {
			return nil, err
		}
		return nil, errors.New("TEMPLATE_CAPABILITY_RESPONSE_FORMAT")
	}
	return result, nil
}

func (c *Client) GetProfile(ctx context.Context, ownerID, missionID int64, expectedTemplateSHA256 string) (map[string]any, error) {
	result, err := c.get(ctx, ownerID, missionID, expectedTemplateSHA256)
	if err != nil {
		return nil, err
	}
	object, ok := result.(map[string]any)
	if !ok {
		return nil, ErrProfileIncomplete
	}
	// A bridge may wrap the Engine-native object as engineNativeProfile while
	// also returning the Java profile response. Unwrap only that explicit
	// server-owned field; never derive geometry/components from capabilityView.
	if native, ok := object["engineNativeProfile"].(map[string]any); ok {
		merged := make(map[string]any, len(object)+len(native))
		for key, value := range object {
			merged[key] = value
		}
		for key, value := range native {
			merged[key] = value
		}
		object = merged
	}
	if missionID > 0 && stringValue(object["missionId"]) != fmt.Sprint(missionID) {
		return nil, ErrProfileIncomplete
	}
	// The Java ProfileResponse names these values as version/checksum fields in
	// the persisted native object. Normalize them to the bridge contract while
	// retaining the original native fields unchanged.
	if _, ok := object["templateFileVersion"]; !ok {
		if value, exists := object["templateVersion"]; exists {
			object["templateFileVersion"] = fmt.Sprint(value)
		}
	}
	if _, ok := object["templateProfileVersion"]; !ok {
		if value, exists := object["profileVersion"]; exists {
			object["templateProfileVersion"] = fmt.Sprint(value)
		}
	}
	templateID := stringValue(object["templateId"])
	templateFileVersion := stringValue(object["templateFileVersion"])
	templateProfileVersion := stringValue(object["templateProfileVersion"])
	fileSHA256 := ""
	if raw, ok := object["templateFileSha256"]; ok && raw != nil {
		fileSHA256 = strings.ToLower(strings.TrimSpace(fmt.Sprint(raw)))
	}
	if fileSHA256 == "" {
		fileSHA256 = strings.ToLower(strings.TrimSpace(stringValue(object["sourceSha256"])))
		if fileSHA256 != "" {
			object["templateFileSha256"] = fileSHA256
		}
	}
	sourceSHA256 := strings.ToLower(strings.TrimSpace(stringValue(object["sourceSha256"])))
	expected := strings.ToLower(strings.TrimSpace(expectedTemplateSHA256))
	if strings.TrimSpace(templateID) == "" || strings.TrimSpace(templateFileVersion) == "" || strings.TrimSpace(templateProfileVersion) == "" || !isSHA256(fileSHA256) || fileSHA256 != sourceSHA256 || (expected != "" && fileSHA256 != expected) || !completeNativeProfile(object, ownerID) {
		return nil, ErrProfileIncomplete
	}
	// The Engine V2 contract owns the complete execution-ready profile shape.
	// Keep every server-provided field instead of reducing it to the legacy
	// capability summary; the Store still appends only its trusted file hash.
	profile := make(map[string]any, len(object)+1)
	for key, value := range object {
		profile[key] = value
	}
	return profile, nil
}

func isSHA256(value string) bool {
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

func completeNativeProfile(object map[string]any, ownerID int64) bool {
	for _, key := range []string{"contractVersion", "profileId", "projectId", "ownerUserId", "templateVersion", "profileVersion", "status", "pageSize", "spatialProfile", "templatePageReferences", "components", "textFitPolicy", "executionStatus", "sourceVersionId", "sourceSha256", "parserSnapshotChecksum"} {
		if _, ok := object[key]; !ok {
			return false
		}
	}
	if stringValue(object["contractVersion"]) != "1.0.0" || stringValue(object["status"]) != "READY" || stringValue(object["executionStatus"]) != "EXECUTION_READY" {
		return false
	}
	if stringValue(object["profileId"]) == "" || stringValue(object["projectId"]) == "" || stringValue(object["ownerUserId"]) == "" ||
		(ownerID > 0 && stringValue(object["ownerUserId"]) != fmt.Sprint(ownerID)) ||
		!positiveInteger(object["templateVersion"]) || !positiveInteger(object["profileVersion"]) || !positiveInteger(object["sourceVersionId"]) ||
		!isSHA256(strings.ToLower(stringValue(object["sourceSha256"]))) ||
		!isSHA256(strings.ToLower(stringValue(object["parserSnapshotChecksum"]))) {
		return false
	}
	if _, ok := object["pageSize"].(map[string]any); !ok {
		return false
	}
	if _, ok := object["spatialProfile"].(map[string]any); !ok {
		return false
	}
	if _, ok := object["textFitPolicy"].(map[string]any); !ok {
		return false
	}
	if _, ok := object["templatePageReferences"].([]any); !ok {
		return false
	}
	if _, ok := object["components"].([]any); !ok {
		return false
	}
	return true
}

func positiveInteger(value any) bool {
	switch number := value.(type) {
	case float64:
		return number > 0 && !math.IsNaN(number) && !math.IsInf(number, 0) && math.Trunc(number) == number
	case float32:
		value := float64(number)
		return value > 0 && !math.IsNaN(value) && !math.IsInf(value, 0) && math.Trunc(value) == value
	case json.Number:
		parsed, err := strconv.ParseInt(string(number), 10, 64)
		return err == nil && parsed > 0
	case int:
		return number > 0
	case int8:
		return number > 0
	case int16:
		return number > 0
	case int32:
		return number > 0
	case int64:
		return number > 0
	case uint:
		return number > 0
	case uint8:
		return number > 0
	case uint16:
		return number > 0
	case uint32:
		return number > 0
	case uint64:
		return number > 0
	case string:
		parsed, err := strconv.ParseInt(strings.TrimSpace(number), 10, 64)
		return err == nil && parsed > 0
	default:
		return false
	}
}

func stringValue(value any) string {
	if value == nil {
		return ""
	}
	return strings.TrimSpace(fmt.Sprint(value))
}

// decodeResponse accepts the existing Java ApiResponse envelope and the
// explicit internal bridge's bare JSON form. It keeps upstream error details
// out of the Go error surface and does not treat a nonzero Java code as a
// successful profile response.
func decodeResponse(data []byte, out *any) error {
	var envelope struct {
		Code    *int            `json:"code"`
		Message string          `json:"message"`
		Data    json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(data, &envelope); err != nil {
		return err
	}
	if envelope.Code != nil {
		if *envelope.Code != 0 || len(envelope.Data) == 0 || string(envelope.Data) == "null" {
			return ErrUpstreamFailure
		}
		return json.Unmarshal(envelope.Data, out)
	}
	return json.Unmarshal(data, out)
}
