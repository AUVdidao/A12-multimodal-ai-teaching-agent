package model

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestNormalizeBaseURL(t *testing.T) {
	tests := map[string]string{
		"https://api.example.test":                         "https://api.example.test",
		"https://api.example.test/v1/":                     "https://api.example.test/v1",
		"https://api.example.test/v1/v1/chat/completions/": "https://api.example.test/v1",
		"https://api.example.test/chat/completions":        "https://api.example.test",
	}
	for input, want := range tests {
		got, err := NormalizeBaseURL(input)
		if err != nil || got != want {
			t.Fatalf("NormalizeBaseURL(%q) = %q, %v; want %q", input, got, err, want)
		}
	}
}

func TestValidateBaseURLBlocksUnsafeAuthorities(t *testing.T) {
	client := NewClient(0, 1024)
	for _, input := range []string{"http://example.com", "https://127.0.0.1", "https://[::1]", "https://localhost"} {
		if err := client.ValidateBaseURL(context.Background(), input); err == nil {
			t.Fatalf("ValidateBaseURL(%q) unexpectedly succeeded", input)
		}
	}
}

func TestDialContextRejectsPrivateAddressReturnedByDNS(t *testing.T) {
	client := NewClient(time.Second, 1024)
	client.resolver = resolverFunc(func(context.Context, string) ([]net.IPAddr, error) {
		return []net.IPAddr{{IP: net.ParseIP("127.0.0.1")}}, nil
	})
	if _, err := client.dialContext(context.Background(), "tcp", "provider.example.test:443"); err == nil || err.Error() != "SSRF_PRIVATE_ADDRESS_BLOCKED" {
		t.Fatalf("dialContext() error = %v, want SSRF_PRIVATE_ADDRESS_BLOCKED", err)
	}
}

func TestChatBuildsOpenAICompatibleRequest(t *testing.T) {
	var received http.Request
	var body map[string]any
	client := &Client{
		httpClient: &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
			received = *request
			data, err := io.ReadAll(request.Body)
			if err != nil {
				return nil, err
			}
			if err := json.Unmarshal(data, &body); err != nil {
				return nil, err
			}
			return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(`{"choices":[{"message":{"content":"ok"}}]}`)), Header: make(http.Header)}, nil
		})},
		maxResponseBytes: 1024,
	}
	response, err := client.Chat(context.Background(), ResolvedConnection{Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://8.8.8.8/v1/v1", ModelID: "teacher-model", APIKey: "secret"}, ChatRequest{Messages: []ChatMessage{{Role: "user", Content: "hello"}}, MaxTokens: 12})
	if err != nil {
		t.Fatalf("Chat() error = %v", err)
	}
	if response.Content != "ok" || response.RawStatus != http.StatusOK {
		t.Fatalf("unexpected response: %#v", response)
	}
	if received.URL.String() != "https://8.8.8.8/v1/chat/completions" {
		t.Fatalf("request URL = %s", received.URL)
	}
	if got := received.Header.Get("Authorization"); got != "Bearer secret" {
		t.Fatalf("authorization = %q", got)
	}
	if got, _ := body["model"].(string); got != "teacher-model" {
		t.Fatalf("body model = %q", got)
	}
	if got, _ := body["max_tokens"].(float64); got != 12 {
		t.Fatalf("body max_tokens = %v", got)
	}
}

func TestChatSerializesVisionMessageAsContentParts(t *testing.T) {
	var body struct {
		Messages []struct {
			Content []struct {
				Type     string `json:"type"`
				Text     string `json:"text"`
				ImageURL struct {
					URL string `json:"url"`
				} `json:"image_url"`
			} `json:"content"`
		} `json:"messages"`
	}
	client := &Client{
		httpClient: &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
			data, err := io.ReadAll(request.Body)
			if err != nil {
				return nil, err
			}
			if err := json.Unmarshal(data, &body); err != nil {
				return nil, err
			}
			return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(`{"choices":[{"message":{"content":"ok"}}]}`)), Header: make(http.Header)}, nil
		})},
		maxResponseBytes: 4096,
	}
	_, err := client.Chat(context.Background(), ResolvedConnection{Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://8.8.8.8", ModelID: "vision-model", APIKey: "secret"}, ChatRequest{
		Messages: []ChatMessage{{Role: "user", Content: "describe", Images: []ChatImage{{URL: "data:image/png;base64,AAAA", MediaType: "image/png"}}}},
	})
	if err != nil {
		t.Fatalf("Chat() error = %v", err)
	}
	if len(body.Messages) != 1 || len(body.Messages[0].Content) != 2 {
		t.Fatalf("vision content parts = %#v", body.Messages)
	}
	if body.Messages[0].Content[0].Type != "text" || body.Messages[0].Content[0].Text != "describe" {
		t.Fatalf("text part = %#v", body.Messages[0].Content[0])
	}
	if body.Messages[0].Content[1].Type != "image_url" || body.Messages[0].Content[1].ImageURL.URL != "data:image/png;base64,AAAA" {
		t.Fatalf("image part = %#v", body.Messages[0].Content[1])
	}
}

func TestVerifyConnectionProbesVisionOnlyWhenDeclared(t *testing.T) {
	var imageProbeSeen bool
	client := &Client{
		httpClient: &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
			data, err := io.ReadAll(request.Body)
			if err != nil {
				return nil, err
			}
			if strings.Contains(string(data), `"image_url"`) {
				imageProbeSeen = true
			}
			return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(`{"choices":[{"message":{"content":"ok"}}]}`)), Header: make(http.Header)}, nil
		})},
		maxResponseBytes: 4096,
	}
	checks, err := client.VerifyConnection(context.Background(), ResolvedConnection{
		Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://8.8.8.8", ModelID: "vision-model", APIKey: "secret",
		Capabilities: ModelCapabilities{SupportsVision: true},
	})
	if err != nil {
		t.Fatalf("VerifyConnection() error = %v", err)
	}
	if !checks.VisionProbed || !checks.Vision || !imageProbeSeen {
		t.Fatalf("vision check = %#v, imageProbeSeen=%v", checks, imageProbeSeen)
	}
}

func TestChatClassifiesProviderTimeout(t *testing.T) {
	client := NewClientWithTransport(time.Second, 1024, roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, context.DeadlineExceeded
	}))
	_, err := client.Chat(context.Background(), ResolvedConnection{Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://8.8.8.8", ModelID: "teacher-model", APIKey: "secret"}, ChatRequest{Messages: []ChatMessage{{Role: "user", Content: "hello"}}})
	if err == nil || err.Error() != "MODEL_TIMEOUT" {
		t.Fatalf("provider timeout error = %v, want MODEL_TIMEOUT", err)
	}
}

func TestChatPreservesHTTPStatusAndOnlyReturnsSafeProviderCode(t *testing.T) {
	tests := []struct {
		name   string
		status int
		code   string
	}{
		{name: "authentication", status: http.StatusUnauthorized, code: "invalid_api_key"},
		{name: "balance", status: http.StatusPaymentRequired, code: "insufficient_balance"},
		{name: "model", status: http.StatusNotFound, code: "model_not_found"},
		{name: "timeout", status: http.StatusRequestTimeout, code: "provider_timeout"},
		{name: "rate", status: http.StatusTooManyRequests, code: "rate_limit"},
		{name: "server", status: http.StatusBadGateway, code: "upstream_failure"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			const secret = "sk-provider-secret/path-and-header"
			client := NewClientWithTransport(time.Second, 4096, roundTripFunc(func(*http.Request) (*http.Response, error) {
				return &http.Response{
					StatusCode: tt.status,
					Body:       io.NopCloser(strings.NewReader(`{"error":{"code":"` + tt.code + `","message":"` + secret + `"}}`)),
					Header:     make(http.Header),
				}, nil
			}))
			response, err := client.Chat(context.Background(), ResolvedConnection{Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://8.8.8.8", ModelID: "teacher-model", APIKey: secret}, ChatRequest{Messages: []ChatMessage{{Role: "user", Content: "hello"}}})
			if err == nil {
				t.Fatal("Chat() unexpectedly succeeded")
			}
			if response.RawStatus != tt.status {
				t.Fatalf("raw status = %d, want %d", response.RawStatus, tt.status)
			}
			httpErr, ok := err.(*HTTPError)
			if !ok || httpErr.Status != tt.status || httpErr.Body != tt.code {
				t.Fatalf("safe HTTP error = %#v, want status=%d code=%q", err, tt.status, tt.code)
			}
			if strings.Contains(err.Error(), secret) || strings.Contains(httpErr.Body, secret) {
				t.Fatalf("provider secret leaked through error: %v", err)
			}
		})
	}
}

func TestChatAddsJSONModeOnlyWhenRequested(t *testing.T) {
	var body map[string]any
	client := &Client{
		httpClient: &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
			data, err := io.ReadAll(request.Body)
			if err != nil {
				return nil, err
			}
			if err := json.Unmarshal(data, &body); err != nil {
				return nil, err
			}
			return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(`{"choices":[{"message":{"content":"{\"type\":\"MESSAGE\",\"content\":\"ok\"}"}}]}`)), Header: make(http.Header)}, nil
		})},
		maxResponseBytes: 4096,
	}
	if _, err := client.Chat(context.Background(), ResolvedConnection{Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://8.8.8.8", ModelID: "teacher-model", APIKey: "secret"}, ChatRequest{Messages: []ChatMessage{{Role: "user", Content: "hello"}}, JSONMode: true}); err != nil {
		t.Fatalf("Chat() error = %v", err)
	}
	format, ok := body["response_format"].(map[string]any)
	if !ok || format["type"] != "json_object" {
		t.Fatalf("response_format = %#v", body["response_format"])
	}
}

func TestChatPreservesReasoningContentAndToolCalls(t *testing.T) {
	client := &Client{
		httpClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			return &http.Response{
				StatusCode: http.StatusOK,
				Body:       io.NopCloser(strings.NewReader(`{"choices":[{"message":{"content":"","reasoning_content":"opaque reasoning","tool_calls":[{"id":"call-1","type":"function","function":{"name":"search_materials","arguments":"{}"}},{"id":"call-2","type":"function","function":{"name":"get_current_plan","arguments":"{}"}}]}}]}`)),
				Header:     make(http.Header),
			}, nil
		})},
		maxResponseBytes: 4096,
	}
	response, err := client.Chat(context.Background(), ResolvedConnection{Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://8.8.8.8", ModelID: "teacher-model", APIKey: "secret"}, ChatRequest{Messages: []ChatMessage{{Role: "user", Content: "hello"}}})
	if err != nil {
		t.Fatalf("Chat() error = %v", err)
	}
	if response.ReasoningContent != "opaque reasoning" {
		t.Fatalf("reasoning_content = %q", response.ReasoningContent)
	}
	if len(response.ToolCalls) != 2 || response.ToolCalls[0].ID != "call-1" || response.ToolCalls[1].ID != "call-2" {
		t.Fatalf("tool calls = %#v", response.ToolCalls)
	}
}

func TestChatSerializesToolReplayWirePayload(t *testing.T) {
	var payload struct {
		Messages []struct {
			Role             string     `json:"role"`
			ReasoningContent string     `json:"reasoning_content"`
			ToolCallID       string     `json:"tool_call_id"`
			ToolCalls        []ToolCall `json:"tool_calls"`
		} `json:"messages"`
	}
	client := &Client{
		httpClient: &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
			data, err := io.ReadAll(request.Body)
			if err != nil {
				return nil, err
			}
			if err := json.Unmarshal(data, &payload); err != nil {
				return nil, err
			}
			return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(`{"choices":[{"message":{"content":"ok"}}]}`)), Header: make(http.Header)}, nil
		})},
		maxResponseBytes: 4096,
	}
	_, err := client.Chat(context.Background(), ResolvedConnection{Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://8.8.8.8", ModelID: "teacher-model", APIKey: "secret"}, ChatRequest{Messages: []ChatMessage{
		{Role: "user", Content: "use the tools"},
		{Role: "assistant", ReasoningContent: "opaque reasoning", ToolCalls: []ToolCall{
			{ID: "call-1", Type: "function", Function: ToolCallFunction{Name: "search_materials", Arguments: `{"query":"tcp"}`}},
			{ID: "call-2", Type: "function", Function: ToolCallFunction{Name: "get_current_plan", Arguments: `{}`}},
		}},
		{Role: "tool", ToolCallID: "call-1", Content: `{"results":[]}`},
		{Role: "tool", ToolCallID: "call-2", Content: `{"plan":null}`},
	}})
	if err != nil {
		t.Fatalf("Chat() error = %v", err)
	}
	if len(payload.Messages) != 4 {
		t.Fatalf("wire message count = %d", len(payload.Messages))
	}
	assistant := payload.Messages[1]
	if assistant.Role != "assistant" || assistant.ReasoningContent != "opaque reasoning" || len(assistant.ToolCalls) != 2 {
		t.Fatalf("wire assistant tool turn = %#v", assistant)
	}
	if payload.Messages[2].Role != "tool" || payload.Messages[2].ToolCallID != "call-1" || payload.Messages[3].ToolCallID != "call-2" {
		t.Fatalf("wire tool replay = %#v", payload.Messages[2:])
	}
}
