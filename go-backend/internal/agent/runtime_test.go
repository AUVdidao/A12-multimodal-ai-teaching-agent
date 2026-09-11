package agent

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"lessonforge.local/backend/internal/model"
)

func TestRunToolWithTimeoutReturnsStableCodeAndPreservesParentDeadline(t *testing.T) {
	got := runToolWithTimeout(context.Background(), 5*time.Millisecond, func(ctx context.Context) string {
		<-ctx.Done()
		return `{"error":"RAG_TIMEOUT"}`
	})
	if got != `{"error":"TOOL_TIMEOUT"}` {
		t.Fatalf("tool timeout result = %s, want stable TOOL_TIMEOUT", got)
	}

	parent, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
	defer cancel()
	got = runToolWithTimeout(parent, time.Second, func(ctx context.Context) string {
		<-ctx.Done()
		return `{"error":"RAG_TIMEOUT"}`
	})
	if got == `{"error":"TOOL_TIMEOUT"}` {
		t.Fatal("parent AgentRun deadline was mislabeled as a per-tool timeout")
	}
}

func TestSafeToolErrorNeverReturnsWrappedInternalDetails(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want string
	}{
		{name: "nil", want: `{"error":"TOOL_FAILED"}`},
		{name: "sanitized rag prefix", err: errors.New("RAG_SOURCE_FILE_UNAVAILABLE: open C:\\private\\source.pdf: permission denied"), want: `{"error":"RAG_SOURCE_FILE_UNAVAILABLE"}`},
		{name: "unknown internal error", err: errors.New("database connection failed at C:\\secrets\\runtime.sock"), want: `{"error":"TOOL_FAILED"}`},
		{name: "provider auth", err: &model.HTTPError{Status: 401, Body: "do not expose"}, want: `{"error":"AUTHENTICATION_FAILED"}`},
		{name: "provider timeout", err: &model.HTTPError{Status: 504, Body: "upstream detail"}, want: `{"error":"MODEL_TIMEOUT"}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := safeToolError(test.err); got != test.want {
				t.Fatalf("safeToolError() = %s, want %s", got, test.want)
			}
		})
	}
}

func TestValidQuestionRejectsInvalidChoiceOptions(t *testing.T) {
	if validQuestion("", nil) {
		t.Fatal("question without an explicit questionType was accepted")
	}
	if validQuestion("SINGLE_CHOICE", []string{"only one"}) {
		t.Fatal("single-choice question with one option was accepted")
	}
	if !validQuestion("MULTI_CHOICE", []string{"one", "two"}) {
		t.Fatal("valid multi-choice question was rejected")
	}
}

func TestRequiredToolQueryRejectsMissingOrBlankQuery(t *testing.T) {
	for name, args := range map[string]map[string]any{
		"missing":    {},
		"blank":      {"query": "  \t"},
		"wrong type": {"query": 42},
	} {
		t.Run(name, func(t *testing.T) {
			if query, ok := requiredToolQuery(args); ok || query != "" {
				t.Fatalf("requiredToolQuery(%#v) = %q, %v", args, query, ok)
			}
		})
	}
	query, ok := requiredToolQuery(map[string]any{"query": "  tcp handshake  "})
	if !ok || query != "tcp handshake" {
		t.Fatalf("valid query = %q, %v", query, ok)
	}
}

func TestOnlyKeysRejectsUnexpectedAgentOutputFields(t *testing.T) {
	if onlyKeys(map[string]json.RawMessage{"type": {}, "content": {}, "reasoning": {}}, "type", "content") {
		t.Fatal("unexpected agent output field was accepted")
	}
}

func TestSystemPromptDeclaresStrictQuestionEnvelope(t *testing.T) {
	prompt := systemPrompt()
	for _, required := range []string{"exactly one JSON object and nothing else", "questionType", "SINGLE_CHOICE", "options", "After the teacher answers a QUESTION", "exactly these top-level keys", "structuredPlan", "non-empty slides array", "non-empty title"} {
		if !strings.Contains(prompt, required) {
			t.Fatalf("system prompt missing %q", required)
		}
	}
}

func TestOutputRepairIsBoundedAndPlanSpecific(t *testing.T) {
	if !isRecoverableOutputError(errors.New("AGENT_INVALID_PLAN")) {
		t.Fatal("invalid plan should allow one bounded repair")
	}
	if isRecoverableOutputError(errors.New("AGENT_EMPTY_RESPONSE")) {
		t.Fatal("empty response should not be silently retried")
	}
	chat := appendOutputRepairTurn(nil, "invalid model output", "AGENT_INVALID_PLAN")
	if len(chat) != 2 || chat[0].Role != "assistant" || chat[0].Content != "invalid model output" || chat[1].Role != "user" {
		t.Fatalf("repair turn = %#v", chat)
	}
	for _, required := range []string{"PLAN_DRAFT", "markdown", "structuredPlan", "slides", "title", "JSON only"} {
		if !strings.Contains(chat[1].Content, required) {
			t.Fatalf("plan repair prompt missing %q: %s", required, chat[1].Content)
		}
	}
}

func TestAppendToolCallTurnGroupsCallsIntoOneAssistantMessage(t *testing.T) {
	response := model.ChatResponse{
		Content:          "partial",
		ReasoningContent: "reasoning",
		ToolCalls: []model.ToolCall{
			{ID: "call-1", Type: "function", Function: model.ToolCallFunction{Name: "search_materials", Arguments: `{"query":"tcp"}`}},
			{ID: "call-2", Type: "function", Function: model.ToolCallFunction{Name: "get_current_plan", Arguments: `{}`}},
		},
	}
	chat := appendToolCallTurn([]model.ChatMessage{{Role: "system", Content: "system"}}, response, []string{`{"results":[]}`, `{"plan":null}`})
	if len(chat) != 4 {
		t.Fatalf("chat length = %d", len(chat))
	}
	if chat[1].Role != "assistant" || chat[1].Content != "partial" || chat[1].ReasoningContent != "reasoning" || len(chat[1].ToolCalls) != 2 {
		t.Fatalf("assistant tool turn = %#v", chat[1])
	}
	if chat[2].Role != "tool" || chat[2].ToolCallID != "call-1" || chat[2].Content != `{"results":[]}` {
		t.Fatalf("first tool result = %#v", chat[2])
	}
	if chat[3].Role != "tool" || chat[3].ToolCallID != "call-2" || chat[3].Content != `{"plan":null}` {
		t.Fatalf("second tool result = %#v", chat[3])
	}
}

type maintenanceStoreStub struct {
	keys        []string
	err         error
	finalized   []string
	finalizeErr error
}

func (s maintenanceStoreStub) CleanupExpiredUploads(context.Context) ([]string, error) {
	return s.keys, s.err
}

func (s *maintenanceStoreStub) FinalizeExpiredUpload(_ context.Context, key string) error {
	s.finalized = append(s.finalized, key)
	return s.finalizeErr
}

type maintenanceFilesStub struct {
	failKey string
}

func (s maintenanceFilesStub) Remove(key string) error {
	if key == s.failKey {
		return errors.New("disk unavailable")
	}
	return nil
}

func TestRunMaintenanceOnceSurfacesDatabaseAndPhysicalCleanupFailures(t *testing.T) {
	if err := runMaintenanceOnce(context.Background(), &maintenanceStoreStub{err: errors.New("database unavailable")}, maintenanceFilesStub{}); err == nil || !strings.Contains(err.Error(), "database unavailable") {
		t.Fatalf("database maintenance error = %v", err)
	}
	failedPhysical := &maintenanceStoreStub{keys: []string{"uploads/expired.bin"}}
	if err := runMaintenanceOnce(context.Background(), failedPhysical, maintenanceFilesStub{failKey: "uploads/expired.bin"}); err == nil || !strings.Contains(err.Error(), "remove expired upload uploads/expired.bin") || !strings.Contains(err.Error(), "disk unavailable") {
		t.Fatalf("physical maintenance error = %v", err)
	}
	if len(failedPhysical.finalized) != 0 {
		t.Fatalf("finalized after physical failure = %v", failedPhysical.finalized)
	}
	successful := &maintenanceStoreStub{keys: []string{"uploads/expired.bin"}}
	if err := runMaintenanceOnce(context.Background(), successful, maintenanceFilesStub{}); err != nil {
		t.Fatalf("successful maintenance = %v", err)
	}
	if len(successful.finalized) != 1 || successful.finalized[0] != "uploads/expired.bin" {
		t.Fatalf("finalized keys = %v", successful.finalized)
	}
}

func TestRunMaintenanceOnceSurfacesDatabaseFinalizeFailure(t *testing.T) {
	store := &maintenanceStoreStub{keys: []string{"uploads/expired.bin"}, finalizeErr: errors.New("database finalize unavailable")}
	if err := runMaintenanceOnce(context.Background(), store, maintenanceFilesStub{}); err == nil || !strings.Contains(err.Error(), "database finalize unavailable") {
		t.Fatalf("finalize error = %v", err)
	}
}
