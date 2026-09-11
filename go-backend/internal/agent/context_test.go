package agent

import (
	"strings"
	"testing"

	"lessonforge.local/backend/internal/model"
)

func TestBuildContextKeepsServerFactsAndBoundsConversation(t *testing.T) {
	messages := make([]model.Message, 0, 5)
	for index := 1; index <= 5; index++ {
		messages = append(messages, model.Message{Role: "USER", Content: "message-" + string(rune('0'+index))})
	}
	mission := model.Mission{ID: 42, Title: "TCP lesson"}
	context, err := BuildContext(ContextInput{
		Mission:           mission,
		Messages:          messages,
		Files:             []model.MissionFile{{ID: 7, Role: "MATERIAL", ParseStatus: "READY"}},
		Questions:         []model.Question{{ID: "q-1", Text: "grade?", Type: "TEXT"}},
		CurrentDraft:      &model.PlanningDraft{ID: "draft-1", Markdown: "draft"},
		HistoricalSummary: "已确认：面向高中生，课程时长 45 分钟。",
		Policy:            ContextPolicyV1{RecentMessageLimit: 2, ConversationCharCap: 100, MessageCharCap: 100},
	})
	if err != nil {
		t.Fatalf("BuildContext: %v", err)
	}
	if context.PromptVersion != model.PlanAgentPromptVersion || context.ContextPolicyVersion != model.PlanAgentContextPolicyVersion {
		t.Fatalf("context versions = %q/%q", context.PromptVersion, context.ContextPolicyVersion)
	}
	if context.OmittedMessageCount != 3 {
		t.Fatalf("omitted message count = %d, want 3", context.OmittedMessageCount)
	}
	joined := make([]string, 0, len(context.Messages))
	for _, message := range context.Messages {
		joined = append(joined, message.Content)
	}
	all := strings.Join(joined, "\n")
	for _, required := range []string{"message-4", "message-5", "TCP lesson", "MATERIAL", "grade?", "draft", "已确认：面向高中生", "Omitted messages: 3"} {
		if !strings.Contains(all, required) {
			t.Fatalf("context missing %q: %s", required, all)
		}
	}
	for _, omitted := range []string{"message-1", "message-2", "message-3"} {
		if strings.Contains(all, omitted) {
			t.Fatalf("old message %q was not bounded: %s", omitted, all)
		}
	}
}

func TestBuildContextTruncatesOneOversizedMessageWithoutBreakingUTF8(t *testing.T) {
	context, err := BuildContext(ContextInput{
		Messages: []model.Message{{Role: "USER", Content: "你好世界"}},
		Policy:   ContextPolicyV1{RecentMessageLimit: 1, ConversationCharCap: 100, MessageCharCap: 2, ToolResponseCharCap: 100},
	})
	if err != nil {
		t.Fatalf("BuildContext: %v", err)
	}
	if got := context.Messages[2].Content; got != "你好\n[message truncated by context policy]" {
		t.Fatalf("truncated message = %q", got)
	}
}

func TestBoundToolResultPreservesValidJSONErrorWhenContentIsTooLarge(t *testing.T) {
	got := boundToolResult(strings.Repeat("x", 101), 100)
	if got != `{"error":"TOOL_RESPONSE_TOO_LARGE","hint":"Use a narrower search query or a more specific material locator."}` {
		t.Fatalf("bounded tool result = %s", got)
	}
}

func TestBuildContextRejectsUnknownFrozenVersions(t *testing.T) {
	if _, err := BuildContext(ContextInput{PromptVersion: "PLAN_AGENT_V0"}); err == nil || !strings.Contains(err.Error(), "AGENT_PROMPT_VERSION_UNSUPPORTED") {
		t.Fatalf("prompt version error = %v", err)
	}
	if _, err := BuildContext(ContextInput{ContextPolicyVersion: "CONTEXT_V0"}); err == nil || !strings.Contains(err.Error(), "AGENT_CONTEXT_POLICY_UNSUPPORTED") {
		t.Fatalf("context policy error = %v", err)
	}
}
