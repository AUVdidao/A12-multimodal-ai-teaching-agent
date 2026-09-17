package model

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type ChatMessage struct {
	Role             string      `json:"role"`
	Content          string      `json:"content,omitempty"`
	Images           []ChatImage `json:"-"`
	ReasoningContent string      `json:"reasoning_content,omitempty"`
	ToolCallID       string      `json:"tool_call_id,omitempty"`
	ToolCalls        []ToolCall  `json:"tool_calls,omitempty"`
}

// ChatImage is an explicitly supplied OpenAI-compatible image part. The
// verification path uses a bounded data URL so the provider never needs to
// fetch an internal LessonForge URL. Normal text turns keep the historical
// string content shape; image turns are encoded as the provider's content
// parts array by MarshalJSON.
type ChatImage struct {
	URL       string
	MediaType string
}

func (m ChatMessage) MarshalJSON() ([]byte, error) {
	type wireMessage struct {
		Role             string     `json:"role"`
		Content          any        `json:"content,omitempty"`
		ReasoningContent string     `json:"reasoning_content,omitempty"`
		ToolCallID       string     `json:"tool_call_id,omitempty"`
		ToolCalls        []ToolCall `json:"tool_calls,omitempty"`
	}
	if len(m.Images) == 0 {
		return json.Marshal(wireMessage{Role: m.Role, Content: m.Content, ReasoningContent: m.ReasoningContent, ToolCallID: m.ToolCallID, ToolCalls: m.ToolCalls})
	}
	parts := make([]map[string]any, 0, 1+len(m.Images))
	if m.Content != "" {
		parts = append(parts, map[string]any{"type": "text", "text": m.Content})
	}
	for _, image := range m.Images {
		imageURL := map[string]any{"url": image.URL}
		parts = append(parts, map[string]any{"type": "image_url", "image_url": imageURL})
	}
	return json.Marshal(wireMessage{Role: m.Role, Content: parts, ReasoningContent: m.ReasoningContent, ToolCallID: m.ToolCallID, ToolCalls: m.ToolCalls})
}

type ToolDefinition struct {
	Type     string       `json:"type"`
	Function ToolFunction `json:"function"`
}
type ToolFunction struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	Parameters  map[string]any `json:"parameters"`
}
type ToolCall struct {
	ID       string           `json:"id"`
	Type     string           `json:"type"`
	Function ToolCallFunction `json:"function"`
}
type ToolCallFunction struct {
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}

type ChatRequest struct {
	Messages    []ChatMessage
	Tools       []ToolDefinition
	Temperature *float64
	MaxTokens   int
	// DisableThinking requests a final answer without provider reasoning. It
	// is intentionally opt-in because most providers do not expose the same
	// thinking contract; the bounded vision capability probe uses it so a
	// small max-token budget cannot consume the whole response before a final
	// content token is emitted.
	DisableThinking bool
	// JSONMode asks an OpenAI-compatible provider to return structured JSON
	// content. It is opt-in because not every compatible endpoint supports the
	// response_format extension.
	JSONMode bool
}
type ChatResponse struct {
	Content          string
	ReasoningContent string
	ToolCalls        []ToolCall
	RawStatus        int
}

type EmbeddingResponse struct {
	Vector    []float64
	RawStatus int
}

type CapabilityCheckResult struct {
	NormalChat         bool
	ChatProbed         bool
	ToolCalling        bool
	JSONMode           bool
	Vision             bool
	VisionProbed       bool
	Embeddings         bool
	EmbeddingsProbed   bool
	EmbeddingDimension int
	HTTPStatus         int
}

type ResolvedConnection struct {
	ID                     int64
	OwnerUserID            int64
	Provider               string
	Protocol               string
	BaseURL                string
	ModelID                string
	Capabilities           ModelCapabilities
	CapabilityVerification CapabilityVerification
	APIKey                 string
}

type Client struct {
	httpClient       *http.Client
	resolver         ipResolver
	timeout          time.Duration
	maxResponseBytes int64
}

type ipResolver interface {
	LookupIPAddr(context.Context, string) ([]net.IPAddr, error)
}

type resolverFunc func(context.Context, string) ([]net.IPAddr, error)

func (f resolverFunc) LookupIPAddr(ctx context.Context, host string) ([]net.IPAddr, error) {
	return f(ctx, host)
}

func NewClient(timeout time.Duration, maxResponseBytes int64) *Client {
	resolver := net.DefaultResolver
	result := &Client{resolver: resolver, timeout: timeout, maxResponseBytes: maxResponseBytes}
	result.httpClient = &http.Client{Timeout: timeout, Transport: &http.Transport{Proxy: http.ProxyFromEnvironment, DialContext: result.dialContext, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12}}, CheckRedirect: nil}
	result.httpClient.CheckRedirect = result.checkRedirect
	return result
}

// NewClientWithTransport creates the same provider-neutral client with an
// injected HTTP transport. Production uses NewClient; the injection seam keeps
// the full Runtime -> Client -> JSON wire path testable without a real Provider.
func NewClientWithTransport(timeout time.Duration, maxResponseBytes int64, transport http.RoundTripper) *Client {
	result := NewClient(timeout, maxResponseBytes)
	if transport != nil {
		result.httpClient.Transport = transport
	}
	return result
}

func NormalizeBaseURL(raw string) (string, error) {
	value := strings.TrimSpace(raw)
	if value == "" {
		return "", errors.New("base URL is required")
	}
	u, err := url.Parse(value)
	if err != nil || u.Scheme != "https" || u.User != nil || u.Hostname() == "" || u.RawQuery != "" || u.Fragment != "" {
		return "", errors.New("base URL must be an external HTTPS origin")
	}
	path := strings.TrimRight(u.EscapedPath(), "/")
	for strings.HasSuffix(path, "/chat/completions") {
		path = strings.TrimSuffix(path, "/chat/completions")
		path = strings.TrimRight(path, "/")
	}
	for strings.HasSuffix(path, "/embeddings") {
		path = strings.TrimSuffix(path, "/embeddings")
		path = strings.TrimRight(path, "/")
	}
	for strings.HasSuffix(path, "/v1/v1") {
		path = strings.TrimSuffix(path, "/v1")
	}
	u.Path = path
	u.RawPath = ""
	u.RawQuery = ""
	u.Fragment = ""
	return strings.TrimRight(u.String(), "/"), nil
}

// Embed calls the OpenAI-compatible embeddings endpoint and returns the first
// vector. It is kept as a narrow compatibility wrapper for connection
// verification and callers that only need one input.
func (c *Client) Embed(ctx context.Context, connection ResolvedConnection, input string) (response EmbeddingResponse, err error) {
	vectors, status, err := c.EmbedBatch(ctx, connection, []string{input})
	if err != nil {
		return EmbeddingResponse{RawStatus: status}, err
	}
	if len(vectors) != 1 || len(vectors[0]) == 0 {
		return EmbeddingResponse{RawStatus: status}, errors.New("embedding response contains no vector")
	}
	return EmbeddingResponse{Vector: vectors[0], RawStatus: status}, nil
}

// EmbedBatch calls the provider once for a bounded batch of texts and returns
// vectors in the same order as inputs. The caller must validate the returned
// dimension against its persisted embedding contract before writing vectors.
func (c *Client) EmbedBatch(ctx context.Context, connection ResolvedConnection, inputs []string) (vectors [][]float64, rawStatus int, err error) {
	if connection.Protocol != "OPENAI_COMPATIBLE" {
		return nil, 0, errors.New("unsupported model protocol")
	}
	if len(inputs) == 0 || len(inputs) > 64 {
		return nil, 0, errors.New("embedding input batch is invalid")
	}
	normalized, err := NormalizeBaseURL(connection.BaseURL)
	if err != nil {
		return nil, 0, err
	}
	u, err := url.Parse(normalized + "/embeddings")
	if err != nil {
		return nil, 0, err
	}
	if err := c.validateAuthority(ctx, u); err != nil {
		return nil, 0, err
	}
	encoded, err := json.Marshal(map[string]any{"model": connection.ModelID, "input": inputs})
	if err != nil {
		return nil, 0, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u.String(), bytes.NewReader(encoded))
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+connection.APIKey)
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, 0, classifyTransportError(err)
	}
	rawStatus = resp.StatusCode
	defer func() {
		if closeErr := resp.Body.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close embedding response: %w", closeErr))
		}
	}()
	data, err := io.ReadAll(io.LimitReader(resp.Body, c.maxResponseBytes+1))
	if err != nil {
		return nil, rawStatus, err
	}
	if int64(len(data)) > c.maxResponseBytes {
		return nil, rawStatus, errors.New("model response exceeds configured limit")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, rawStatus, &HTTPError{Status: resp.StatusCode, Body: safeProviderError(data)}
	}
	var decoded struct {
		Data []struct {
			Index     *int      `json:"index"`
			Embedding []float64 `json:"embedding"`
		} `json:"data"`
	}
	if err := json.Unmarshal(data, &decoded); err != nil {
		return nil, rawStatus, fmt.Errorf("embedding response format: %w", err)
	}
	if len(decoded.Data) != len(inputs) {
		return nil, rawStatus, fmt.Errorf("embedding response count mismatch: got %d want %d", len(decoded.Data), len(inputs))
	}
	vectors = make([][]float64, len(inputs))
	for position, item := range decoded.Data {
		index := position
		if item.Index != nil {
			index = *item.Index
		}
		if index < 0 || index >= len(inputs) {
			return nil, rawStatus, errors.New("embedding response vector index is invalid")
		}
		if len(item.Embedding) == 0 || vectors[index] != nil {
			return nil, rawStatus, errors.New("embedding response vector ordering is invalid")
		}
		vectors[index] = item.Embedding
	}
	for index, vector := range vectors {
		if len(vector) == 0 {
			return nil, rawStatus, fmt.Errorf("embedding response missing vector at index %d", index)
		}
	}
	return vectors, rawStatus, nil
}

func (c *Client) ValidateBaseURL(ctx context.Context, base string) error {
	normalized, err := NormalizeBaseURL(base)
	if err != nil {
		return err
	}
	u, err := url.Parse(normalized)
	if err != nil {
		return fmt.Errorf("parse normalized model base URL: %w", err)
	}
	return c.validateAuthority(ctx, u)
}

func (c *Client) Chat(ctx context.Context, connection ResolvedConnection, request ChatRequest) (response ChatResponse, err error) {
	if connection.Protocol != "OPENAI_COMPATIBLE" {
		return ChatResponse{}, errors.New("unsupported model protocol")
	}
	normalized, err := NormalizeBaseURL(connection.BaseURL)
	if err != nil {
		return ChatResponse{}, err
	}
	u, err := url.Parse(normalized + "/chat/completions")
	if err != nil {
		return ChatResponse{}, err
	}
	if err := c.validateAuthority(ctx, u); err != nil {
		return ChatResponse{}, err
	}
	body := map[string]any{"model": connection.ModelID, "messages": request.Messages}
	if len(request.Tools) > 0 {
		body["tools"] = request.Tools
		body["tool_choice"] = "auto"
	}
	if request.Temperature != nil {
		body["temperature"] = *request.Temperature
	}
	if request.MaxTokens > 0 {
		body["max_tokens"] = request.MaxTokens
	}
	if request.JSONMode {
		body["response_format"] = map[string]string{"type": "json_object"}
	}
	if request.DisableThinking {
		body["thinking"] = map[string]string{"type": "disabled"}
	}
	encoded, err := json.Marshal(body)
	if err != nil {
		return ChatResponse{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u.String(), bytes.NewReader(encoded))
	if err != nil {
		return ChatResponse{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+connection.APIKey)
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return ChatResponse{}, classifyTransportError(err)
	}
	defer func() {
		if closeErr := resp.Body.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close model response: %w", closeErr))
		}
	}()
	data, err := io.ReadAll(io.LimitReader(resp.Body, c.maxResponseBytes+1))
	if err != nil {
		return ChatResponse{RawStatus: resp.StatusCode}, err
	}
	if int64(len(data)) > c.maxResponseBytes {
		return ChatResponse{RawStatus: resp.StatusCode}, errors.New("model response exceeds configured limit")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return ChatResponse{RawStatus: resp.StatusCode}, &HTTPError{Status: resp.StatusCode, Body: safeProviderError(data)}
	}
	var decoded struct {
		Choices []struct {
			Message struct {
				Content          string     `json:"content"`
				ReasoningContent string     `json:"reasoning_content"`
				ToolCalls        []ToolCall `json:"tool_calls"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(data, &decoded); err != nil {
		return ChatResponse{RawStatus: resp.StatusCode}, fmt.Errorf("model response format: %w", err)
	}
	if len(decoded.Choices) == 0 {
		return ChatResponse{RawStatus: resp.StatusCode}, errors.New("model response contains no choices")
	}
	return ChatResponse{
		Content:          decoded.Choices[0].Message.Content,
		ReasoningContent: decoded.Choices[0].Message.ReasoningContent,
		ToolCalls:        decoded.Choices[0].Message.ToolCalls,
		RawStatus:        resp.StatusCode,
	}, nil
}

// VerifyConnection performs the small provider handshake used by the
// connection UI. Normal chat proves credentials/model access; the tool and
// JSON probes distinguish a merely reachable endpoint from one that can run
// the structured AgentRuntime contract.
func (c *Client) VerifyConnection(ctx context.Context, connection ResolvedConnection) (CapabilityCheckResult, error) {
	result := CapabilityCheckResult{}
	needsChat := connection.Capabilities.SupportsChat || connection.Capabilities.SupportsTools || connection.Capabilities.SupportsJSONMode || connection.Capabilities.SupportsVision
	if needsChat {
		result.ChatProbed = true
		normal, err := c.Chat(ctx, connection, ChatRequest{Messages: []ChatMessage{{Role: "user", Content: "Reply with exactly OK."}}, MaxTokens: 8})
		result.HTTPStatus = normal.RawStatus
		if err != nil {
			return result, err
		}
		result.NormalChat = true
	}

	if connection.Capabilities.SupportsTools {
		toolResponse, toolErr := c.Chat(ctx, connection, ChatRequest{
			Messages:  []ChatMessage{{Role: "user", Content: "Call the verification_tool exactly once."}},
			Tools:     []ToolDefinition{{Type: "function", Function: ToolFunction{Name: "verification_tool", Description: "Connection verification tool.", Parameters: map[string]any{"type": "object", "additionalProperties": false}}}},
			MaxTokens: 32,
		})
		if toolResponse.RawStatus != 0 {
			result.HTTPStatus = toolResponse.RawStatus
		}
		result.ToolCalling = toolErr == nil && len(toolResponse.ToolCalls) > 0
	}

	if connection.Capabilities.SupportsJSONMode {
		jsonResponse, jsonErr := c.Chat(ctx, connection, ChatRequest{
			Messages:  []ChatMessage{{Role: "user", Content: "Return exactly this JSON object: {\"ok\":true}."}},
			MaxTokens: 16,
			JSONMode:  true,
		})
		if jsonResponse.RawStatus != 0 {
			result.HTTPStatus = jsonResponse.RawStatus
		}
		result.JSONMode = jsonErr == nil && json.Valid([]byte(strings.TrimSpace(jsonResponse.Content)))
	}
	if connection.Capabilities.SupportsVision {
		result.VisionProbed = true
		visionResponse, visionErr := c.Chat(ctx, connection, ChatRequest{
			Messages: []ChatMessage{{Role: "user", Content: "Describe the supplied image in one word.", Images: []ChatImage{{
				URL:       "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
				MediaType: "image/png",
			}}}},
			MaxTokens:       32,
			DisableThinking: true,
		})
		if visionResponse.RawStatus != 0 {
			result.HTTPStatus = visionResponse.RawStatus
		}
		result.Vision = visionErr == nil && strings.TrimSpace(visionResponse.Content) != ""
	}
	if connection.Capabilities.SupportsEmbeddings {
		result.EmbeddingsProbed = true
		embeddingResponse, embeddingErr := c.Embed(ctx, connection, "LessonForge connection verification")
		if embeddingResponse.RawStatus != 0 {
			result.HTTPStatus = embeddingResponse.RawStatus
		}
		result.Embeddings = embeddingErr == nil && len(embeddingResponse.Vector) > 0
		result.EmbeddingDimension = len(embeddingResponse.Vector)
		if embeddingErr != nil {
			return result, embeddingErr
		}
	}
	if !needsChat && !connection.Capabilities.SupportsEmbeddings {
		return result, errors.New("MODEL_CAPABILITY_REQUIRED")
	}
	return result, nil
}

type HTTPError struct {
	Status int
	Body   string
}

func (e *HTTPError) Error() string { return "model provider returned HTTP " + strconv.Itoa(e.Status) }

func (c *Client) checkRedirect(req *http.Request, via []*http.Request) error {
	if len(via) >= 3 {
		return errors.New("redirect limit exceeded")
	}
	if len(via) == 0 {
		return nil
	}
	previous := via[0].URL
	if req.URL.Scheme != previous.Scheme || !strings.EqualFold(req.URL.Hostname(), previous.Hostname()) || effectivePort(req.URL) != effectivePort(previous) {
		return errors.New("REDIRECT_AUTHORITY_NOT_ALLOWED")
	}
	if err := c.validateAuthority(req.Context(), req.URL); err != nil {
		return err
	}
	return nil
}

func (c *Client) validateAuthority(ctx context.Context, u *url.URL) error {
	if u == nil || u.Scheme != "https" || u.User != nil || u.Hostname() == "" {
		return errors.New("unsafe model authority")
	}
	host := u.Hostname()
	if ip := net.ParseIP(host); ip != nil {
		if blockedIP(ip) {
			return errors.New("SSRF_PRIVATE_ADDRESS_BLOCKED")
		}
		return nil
	}
	addresses, err := c.resolver.LookupIPAddr(ctx, host)
	if err != nil {
		return fmt.Errorf("model endpoint DNS resolution failed: %w", err)
	}
	if len(addresses) == 0 {
		return errors.New("model endpoint has no address")
	}
	for _, address := range addresses {
		if blockedIP(address.IP) {
			return errors.New("SSRF_PRIVATE_ADDRESS_BLOCKED")
		}
	}
	return nil
}

// dialContext performs the same SSRF check at the actual socket boundary and
// dials the checked IP directly. The URL hostname is retained for TLS SNI and
// HTTP Host semantics, while a DNS change between validation and connection
// cannot silently redirect the credentialed request to a private address.
func (c *Client) dialContext(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, errors.New("MODEL_NETWORK_ERROR")
	}
	addresses, err := c.resolver.LookupIPAddr(ctx, host)
	if err != nil || len(addresses) == 0 {
		return nil, errors.New("MODEL_NETWORK_ERROR")
	}
	dialer := &net.Dialer{Timeout: c.timeout}
	var lastErr error
	for _, address := range addresses {
		if blockedIP(address.IP) {
			return nil, errors.New("SSRF_PRIVATE_ADDRESS_BLOCKED")
		}
		conn, dialErr := dialer.DialContext(ctx, network, net.JoinHostPort(address.IP.String(), port))
		if dialErr == nil {
			return conn, nil
		}
		lastErr = dialErr
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, errors.New("MODEL_NETWORK_ERROR")
}

func effectivePort(u *url.URL) string {
	if p := u.Port(); p != "" {
		return p
	}
	if u.Scheme == "https" {
		return "443"
	}
	return "80"
}
func blockedIP(ip net.IP) bool {
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsUnspecified() || ip.IsMulticast()
}
func classifyTransportError(err error) error {
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return ErrTimeout
	}
	return errors.New("MODEL_NETWORK_ERROR")
}
func safeProviderError(data []byte) string {
	var body struct {
		Error struct {
			Code    string `json:"code"`
			Type    string `json:"type"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if json.Unmarshal(data, &body) == nil {
		code := body.Error.Code
		if code == "" {
			code = body.Error.Type
		}
		if len(code) > 80 {
			code = code[:80]
		}
		if code != "" {
			return code
		}
	}
	return "PROVIDER_ERROR"
}
