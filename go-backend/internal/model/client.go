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
	Role             string     `json:"role"`
	Content          string     `json:"content,omitempty"`
	ReasoningContent string     `json:"reasoning_content,omitempty"`
	ToolCallID       string     `json:"tool_call_id,omitempty"`
	ToolCalls        []ToolCall `json:"tool_calls,omitempty"`
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

type CapabilityCheckResult struct {
	NormalChat  bool
	ToolCalling bool
	JSONMode    bool
	HTTPStatus  int
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
	for strings.HasSuffix(path, "/v1/v1") {
		path = strings.TrimSuffix(path, "/v1")
	}
	u.Path = path
	u.RawPath = ""
	u.RawQuery = ""
	u.Fragment = ""
	return strings.TrimRight(u.String(), "/"), nil
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
	normal, err := c.Chat(ctx, connection, ChatRequest{Messages: []ChatMessage{{Role: "user", Content: "Reply with exactly OK."}}, MaxTokens: 8})
	result.HTTPStatus = normal.RawStatus
	if err != nil {
		return result, err
	}
	result.NormalChat = true

	toolResponse, toolErr := c.Chat(ctx, connection, ChatRequest{
		Messages:  []ChatMessage{{Role: "user", Content: "Call the verification_tool exactly once."}},
		Tools:     []ToolDefinition{{Type: "function", Function: ToolFunction{Name: "verification_tool", Description: "Connection verification tool.", Parameters: map[string]any{"type": "object", "additionalProperties": false}}}},
		MaxTokens: 32,
	})
	if toolResponse.RawStatus != 0 {
		result.HTTPStatus = toolResponse.RawStatus
	}
	result.ToolCalling = toolErr == nil && len(toolResponse.ToolCalls) > 0

	jsonResponse, jsonErr := c.Chat(ctx, connection, ChatRequest{
		Messages:  []ChatMessage{{Role: "user", Content: "Return exactly this JSON object: {\"ok\":true}."}},
		MaxTokens: 16,
		JSONMode:  true,
	})
	if jsonResponse.RawStatus != 0 {
		result.HTTPStatus = jsonResponse.RawStatus
	}
	result.JSONMode = jsonErr == nil && json.Valid([]byte(strings.TrimSpace(jsonResponse.Content)))
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
