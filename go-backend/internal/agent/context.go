package agent

import (
	"encoding/json"
	"fmt"
	"strings"

	"lessonforge.local/backend/internal/model"
)

// ContextPolicyV1 deliberately bounds only the raw conversation. Mission
// facts, question answers, files, and the current draft remain structured
// server-owned inputs and are not allowed to disappear just because a teacher
// has a long conversation.
type ContextPolicyV1 struct {
	RecentMessageLimit  int
	ConversationCharCap int
	MessageCharCap      int
	ToolResponseCharCap int
}

func DefaultContextPolicy() ContextPolicyV1 {
	return ContextPolicyV1{
		RecentMessageLimit:  24,
		ConversationCharCap: 48000,
		MessageCharCap:      12000,
		ToolResponseCharCap: 16000,
	}
}

type ContextInput struct {
	PromptVersion        string
	ContextPolicyVersion string
	Mission              model.Mission
	HistoricalSummary    string
	Messages             []model.Message
	Files                []model.MissionFile
	Questions            []model.Question
	CurrentDraft         *model.PlanningDraft
	Policy               ContextPolicyV1
}

type BuiltContext struct {
	Messages             []model.ChatMessage
	PromptVersion        string
	ContextPolicyVersion string
	OmittedMessageCount  int
}

func BuildContext(input ContextInput) (BuiltContext, error) {
	promptVersion := strings.TrimSpace(input.PromptVersion)
	if promptVersion == "" {
		promptVersion = model.PlanAgentPromptVersion
	}
	if promptVersion != model.PlanAgentPromptVersion && promptVersion != model.LegacyPlanAgentPromptVersion {
		return BuiltContext{}, fmt.Errorf("AGENT_PROMPT_VERSION_UNSUPPORTED: %s", promptVersion)
	}
	policyVersion := strings.TrimSpace(input.ContextPolicyVersion)
	if policyVersion == "" {
		policyVersion = model.PlanAgentContextPolicyVersion
	}
	if policyVersion != model.PlanAgentContextPolicyVersion {
		return BuiltContext{}, fmt.Errorf("AGENT_CONTEXT_POLICY_UNSUPPORTED: %s", policyVersion)
	}
	policy := input.Policy
	defaults := DefaultContextPolicy()
	if policy.RecentMessageLimit <= 0 {
		policy.RecentMessageLimit = defaults.RecentMessageLimit
	}
	if policy.ConversationCharCap <= 0 {
		policy.ConversationCharCap = defaults.ConversationCharCap
	}
	if policy.MessageCharCap <= 0 {
		policy.MessageCharCap = defaults.MessageCharCap
	}
	if policy.ToolResponseCharCap <= 0 {
		policy.ToolResponseCharCap = defaults.ToolResponseCharCap
	}

	chat := []model.ChatMessage{{Role: "system", Content: systemPromptForVersion(promptVersion)}}
	missionSummary, err := json.Marshal(input.Mission)
	if err != nil {
		return BuiltContext{}, fmt.Errorf("serialize mission context: %w", err)
	}
	chat = append(chat, model.ChatMessage{Role: "system", Content: "Current Mission (server-owned): " + string(missionSummary)})
	if summary := strings.TrimSpace(input.HistoricalSummary); summary != "" {
		chat = append(chat, model.ChatMessage{Role: "system", Content: "Historical conversation summary (non-authoritative reference; structured Mission facts and persisted teacher answers take precedence): " + truncateRunes(summary, 12000)})
	}

	selected, omitted := recentMessages(input.Messages, policy)
	if omitted > 0 {
		chat = append(chat, model.ChatMessage{Role: "system", Content: fmt.Sprintf("Older conversation messages omitted by %s; rely on the server-owned Mission facts, persisted questions, and current draft instead of guessing omitted history. Omitted messages: %d.", policyVersion, omitted)})
	}
	for _, message := range selected {
		chat = append(chat, model.ChatMessage{Role: strings.ToLower(message.Role), Content: truncateRunes(message.Content, policy.MessageCharCap)})
	}

	fileSummary, err := json.Marshal(input.Files)
	if err != nil {
		return BuiltContext{}, fmt.Errorf("serialize mission file summary: %w", err)
	}
	chat = append(chat, model.ChatMessage{Role: "system", Content: "Authorized Mission files (metadata only): " + string(fileSummary)})
	questionSummary, err := json.Marshal(input.Questions)
	if err != nil {
		return BuiltContext{}, fmt.Errorf("serialize question context: %w", err)
	}
	chat = append(chat, model.ChatMessage{Role: "system", Content: "Persisted Mission questions and latest answers: " + string(questionSummary)})
	if input.CurrentDraft != nil {
		draftSummary, err := json.Marshal(input.CurrentDraft)
		if err != nil {
			return BuiltContext{}, fmt.Errorf("serialize current planning draft: %w", err)
		}
		chat = append(chat, model.ChatMessage{Role: "system", Content: "Current Planning Draft (server-owned): " + string(draftSummary)})
	}
	return BuiltContext{Messages: chat, PromptVersion: promptVersion, ContextPolicyVersion: policyVersion, OmittedMessageCount: omitted}, nil
}

func recentMessages(messages []model.Message, policy ContextPolicyV1) ([]model.Message, int) {
	if len(messages) == 0 {
		return nil, 0
	}
	start := len(messages) - policy.RecentMessageLimit
	if start < 0 {
		start = 0
	}
	selected := make([]model.Message, 0, len(messages)-start)
	usedChars := 0
	for index := len(messages) - 1; index >= start; index-- {
		messageChars := len([]rune(messages[index].Content))
		if messageChars > policy.MessageCharCap {
			messageChars = policy.MessageCharCap
		}
		if len(selected) > 0 && usedChars+messageChars > policy.ConversationCharCap {
			break
		}
		selected = append(selected, messages[index])
		usedChars += messageChars
	}
	for left, right := 0, len(selected)-1; left < right; left, right = left+1, right-1 {
		selected[left], selected[right] = selected[right], selected[left]
	}
	return selected, len(messages) - len(selected)
}

func truncateRunes(value string, limit int) string {
	truncated := []rune(value)
	if len(truncated) <= limit {
		return value
	}
	return string(truncated[:limit]) + "\n[message truncated by context policy]"
}

func boundToolResult(value string, limit int) string {
	if limit <= 0 {
		limit = DefaultContextPolicy().ToolResponseCharCap
	}
	if len([]rune(value)) <= limit {
		return value
	}
	return `{"error":"TOOL_RESPONSE_TOO_LARGE","hint":"Use a narrower search query or a more specific material locator."}`
}
