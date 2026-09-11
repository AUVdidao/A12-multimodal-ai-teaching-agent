package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/platform/crypto"
	"lessonforge.local/backend/internal/platform/database"
	"lessonforge.local/backend/internal/rag"
	"lessonforge.local/backend/internal/specification"
)

type Runtime struct {
	Store        *database.Store
	Crypto       *crypto.Service
	Models       *model.Client
	RAG          *rag.Client
	Capability   CapabilityResolver
	MaxToolCalls int
	ToolTimeout  time.Duration
	// chat is a same-package integration seam. Production leaves it nil and
	// uses the provider-neutral model client; tests can still drive the full
	// worker/runtime/store output path without a real Provider or API key.
	chat func(context.Context, model.ResolvedConnection, model.ChatRequest) (model.ChatResponse, error)
}

type CapabilityResolver interface {
	GetForOwner(context.Context, int64, int64) (any, error)
}

// ErrWaitingForInput is returned after a QUESTION has been durably persisted.
// The worker translates it into the durable WAITING_INPUTS run state instead
// of treating an unanswered teacher question as a completed run.
var ErrWaitingForInput = errors.New("AGENT_WAITING_INPUTS")

// SetChatForTest installs a controlled transport for bounded integration
// tests. Production leaves it unset and always uses the model client.
func (r *Runtime) SetChatForTest(chat func(context.Context, model.ResolvedConnection, model.ChatRequest) (model.ChatResponse, error)) {
	r.chat = chat
}

type frozenIdentity struct {
	ConnectionID           int64                        `json:"connectionId"`
	Provider               string                       `json:"provider"`
	Protocol               string                       `json:"protocol"`
	BaseURL                string                       `json:"baseUrl"`
	ModelID                string                       `json:"modelId"`
	Capabilities           model.ModelCapabilities      `json:"capabilities"`
	CapabilityVerification model.CapabilityVerification `json:"capabilityVerification"`
	PromptVersion          string                       `json:"promptVersion"`
	ContextPolicyVersion   string                       `json:"contextPolicyVersion"`
	EncryptedAPIKey        string                       `json:"encryptedApiKey"`
}

func (r *Runtime) Run(ctx context.Context, run model.AgentRun) error {
	owner, err := r.Store.MissionOwner(ctx, run.MissionID)
	if err != nil {
		return err
	}
	encoded, err := json.Marshal(run.ModelIdentitySnapshot)
	if err != nil {
		return errors.New("MODEL_IDENTITY_SNAPSHOT_INVALID")
	}
	var frozen frozenIdentity
	if err := json.Unmarshal(encoded, &frozen); err != nil || frozen.ConnectionID == 0 || frozen.Protocol == "" || frozen.BaseURL == "" || frozen.ModelID == "" || frozen.EncryptedAPIKey == "" {
		return errors.New("MODEL_IDENTITY_SNAPSHOT_INVALID")
	}
	// Runs created before migration 011 have no capability fields. They retain
	// the old runtime contract; newly created runs always freeze explicit data.
	var rawSnapshot map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &rawSnapshot); err != nil {
		return errors.New("MODEL_IDENTITY_SNAPSHOT_INVALID")
	}
	if _, exists := rawSnapshot["capabilities"]; !exists {
		frozen.Capabilities = model.DefaultModelCapabilities()
	}
	if frozen.PromptVersion == "" {
		frozen.PromptVersion = model.PlanAgentPromptVersion
	}
	if frozen.ContextPolicyVersion == "" {
		frozen.ContextPolicyVersion = model.PlanAgentContextPolicyVersion
	}
	frozen.CapabilityVerification = model.NormalizeCapabilityVerification(frozen.CapabilityVerification)
	if err := r.Store.ConnectionUsable(ctx, owner, frozen.ConnectionID); err != nil {
		return errors.New("MODEL_CONNECTION_NOT_FOUND")
	}
	apiKey, err := r.Crypto.Decrypt(frozen.EncryptedAPIKey)
	if err != nil {
		return errors.New("MODEL_CONNECTION_CREDENTIAL_UNAVAILABLE")
	}
	resolved := model.ResolvedConnection{ID: frozen.ConnectionID, OwnerUserID: owner, Provider: frozen.Provider, Protocol: frozen.Protocol, BaseURL: frozen.BaseURL, ModelID: frozen.ModelID, Capabilities: frozen.Capabilities, CapabilityVerification: frozen.CapabilityVerification, APIKey: apiKey}
	mission, err := r.Store.GetMission(ctx, owner, run.MissionID)
	if err != nil {
		return err
	}
	messages, err := r.Store.Messages(ctx, owner, run.MissionID)
	if err != nil {
		return err
	}
	files, err := r.Store.MissionFiles(ctx, owner, run.MissionID)
	if err != nil {
		return err
	}
	questions, err := r.Store.Questions(ctx, owner, run.MissionID)
	if err != nil {
		return err
	}
	var currentDraft *model.PlanningDraft
	draft, draftErr := r.Store.CurrentDraft(ctx, owner, run.MissionID)
	if draftErr == nil {
		currentDraft = &draft
	} else if !errors.Is(draftErr, pgx.ErrNoRows) {
		return draftErr
	}
	historicalSummary := ""
	conversationSummary, summaryErr := r.Store.ConversationSummary(ctx, owner, run.MissionID)
	if summaryErr == nil {
		historicalSummary = conversationSummary.Summary
	} else if !errors.Is(summaryErr, pgx.ErrNoRows) {
		return summaryErr
	}
	builtContext, err := BuildContext(ContextInput{
		PromptVersion:        frozen.PromptVersion,
		ContextPolicyVersion: frozen.ContextPolicyVersion,
		Mission:              mission,
		HistoricalSummary:    historicalSummary,
		Messages:             messages,
		Files:                files,
		Questions:            questions,
		CurrentDraft:         currentDraft,
	})
	if err != nil {
		return err
	}
	chat := builtContext.Messages
	tools := toolDefinitions()
	if !frozen.Capabilities.SupportsTools || !frozen.Capabilities.SupportsJSONMode {
		return errors.New("MODEL_CAPABILITY_NOT_SUPPORTED")
	}
	limit := r.MaxToolCalls
	if limit <= 0 {
		limit = 8
	}
	outputRepairAttempts := 0
	for calls := 0; calls <= limit; calls++ {
		started := time.Now()
		chatRequest := model.ChatRequest{Messages: chat, Tools: tools, MaxTokens: 4096, JSONMode: frozen.Capabilities.SupportsJSONMode}
		var response model.ChatResponse
		if r.chat != nil {
			response, err = r.chat(ctx, resolved, chatRequest)
		} else {
			response, err = r.Models.Chat(ctx, resolved, chatRequest)
		}
		status := response.RawStatus
		if providerErr, ok := err.(*model.HTTPError); ok {
			status = providerErr.Status
		}
		connection := model.ModelConnection{ID: frozen.ConnectionID, OwnerUserID: owner, Provider: frozen.Provider, Protocol: frozen.Protocol, BaseURL: frozen.BaseURL, ModelID: frozen.ModelID, Capabilities: frozen.Capabilities, CapabilityVerification: frozen.CapabilityVerification, CapabilitiesSet: true}
		if auditErr := r.Store.RecordModelAudit(ctx, uuid.New(), owner, run.MissionID, frozen.ConnectionID, connection, "AGENT_RUN", status, time.Since(started)); auditErr != nil {
			return fmt.Errorf("record model audit: %w", auditErr)
		}
		if err != nil {
			return err
		}
		if err := r.Store.MarkConnectionUsed(ctx, owner, frozen.ConnectionID); err != nil {
			return fmt.Errorf("mark model connection used: %w", err)
		}
		if len(response.ToolCalls) > 0 {
			results := make([]string, 0, len(response.ToolCalls))
			for _, call := range response.ToolCalls {
				results = append(results, r.runTool(ctx, run.MissionID, owner, call))
			}
			chat = appendToolCallTurn(chat, response, results)
			continue
		}
		if err := r.persistFinal(ctx, run, response.Content); err != nil {
			if errors.Is(err, ErrWaitingForInput) {
				_ = r.updateConversationSummary(ctx, owner, run, resolved, messages, frozen.PromptVersion)
				return err
			}
			if outputRepairAttempts >= 1 || !isRecoverableOutputError(err) {
				return err
			}
			outputRepairAttempts++
			chat = appendOutputRepairTurn(chat, response.Content, err.Error())
			continue
		}
		_ = r.updateConversationSummary(ctx, owner, run, resolved, messages, frozen.PromptVersion)
		return nil
	}
	return errors.New("AGENT_TOOL_CALL_LIMIT")
}

const conversationSummaryPrompt = "You summarize older LessonForge teacher-agent conversation for a later planning run. Preserve only explicitly confirmed teacher requirements, decisions, constraints, unresolved questions, and important corrections. Do not invent facts, do not treat assumptions as confirmed requirements, and do not replace structured Mission facts or persisted question answers. Return concise plain text only, without Markdown fences, hidden reasoning, or commentary about the summarization process."

func (r *Runtime) updateConversationSummary(ctx context.Context, owner int64, run model.AgentRun, connection model.ResolvedConnection, messages []model.Message, promptVersion string) error {
	policy := DefaultContextPolicy()
	if len(messages) <= policy.RecentMessageLimit {
		return nil
	}
	oldMessages := messages[:len(messages)-policy.RecentMessageLimit]
	if len(oldMessages) == 0 {
		return nil
	}
	current, err := r.Store.ConversationSummary(ctx, owner, run.MissionID)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	if errors.Is(err, pgx.ErrNoRows) {
		current = model.ConversationSummary{}
	}
	lastOldMessageID := oldMessages[len(oldMessages)-1].ID
	if current.SummarizedThroughMessageID != nil && lastOldMessageID <= *current.SummarizedThroughMessageID {
		return nil
	}
	delta := make([]model.Message, 0, len(oldMessages))
	for _, message := range oldMessages {
		if current.SummarizedThroughMessageID == nil || message.ID > *current.SummarizedThroughMessageID {
			delta = append(delta, message)
		}
	}
	if len(delta) == 0 {
		return nil
	}
	prompt := buildConversationSummaryRequest(current.Summary, delta)
	started := time.Now()
	var response model.ChatResponse
	if r.chat != nil {
		response, err = r.chat(ctx, connection, model.ChatRequest{
			Messages:  []model.ChatMessage{{Role: "system", Content: conversationSummaryPrompt}, {Role: "user", Content: prompt}},
			MaxTokens: 1200,
		})
	} else if r.Models != nil {
		response, err = r.Models.Chat(ctx, connection, model.ChatRequest{
			Messages:  []model.ChatMessage{{Role: "system", Content: conversationSummaryPrompt}, {Role: "user", Content: prompt}},
			MaxTokens: 1200,
		})
	} else {
		return errors.New("MODEL_CLIENT_NOT_CONFIGURED")
	}
	status := response.RawStatus
	if providerErr, ok := err.(*model.HTTPError); ok {
		status = providerErr.Status
	}
	if auditErr := r.Store.RecordModelAudit(ctx, uuid.New(), owner, run.MissionID, connection.ID, model.ModelConnection{ID: connection.ID, OwnerUserID: owner, Provider: connection.Provider, Protocol: connection.Protocol, BaseURL: connection.BaseURL, ModelID: connection.ModelID, Capabilities: connection.Capabilities, CapabilityVerification: connection.CapabilityVerification, CapabilitiesSet: true}, "CONVERSATION_SUMMARY", status, time.Since(started)); auditErr != nil {
		return auditErr
	}
	if err != nil {
		return err
	}
	summary := strings.TrimSpace(response.Content)
	if summary == "" {
		return errors.New("CONVERSATION_SUMMARY_EMPTY")
	}
	summary = truncateRunes(summary, 12000)
	return r.Store.SaveConversationSummary(ctx, owner, run.MissionID, &lastOldMessageID, summary, run.ID, promptVersion)
}

func buildConversationSummaryRequest(previous string, messages []model.Message) string {
	var builder strings.Builder
	if strings.TrimSpace(previous) != "" {
		builder.WriteString("Existing historical summary:\n")
		builder.WriteString(truncateRunes(previous, 8000))
		builder.WriteString("\n\nNewly eligible older messages:\n")
	} else {
		builder.WriteString("Newly eligible older messages:\n")
	}
	for _, message := range messages {
		builder.WriteString(strings.ToLower(message.Role))
		builder.WriteString(": ")
		builder.WriteString(truncateRunes(message.Content, 3000))
		builder.WriteString("\n")
	}
	return builder.String()
}

const defaultToolTimeout = 30 * time.Second

func (r *Runtime) runTool(ctx context.Context, missionID, owner int64, call model.ToolCall) string {
	timeout := r.ToolTimeout
	if timeout <= 0 {
		timeout = defaultToolTimeout
	}
	result := runToolWithTimeout(ctx, timeout, func(toolCtx context.Context) string {
		return r.tool(toolCtx, missionID, owner, call)
	})
	return boundToolResult(result, DefaultContextPolicy().ToolResponseCharCap)
}

func runToolWithTimeout(ctx context.Context, timeout time.Duration, invoke func(context.Context) string) string {
	toolCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	result := invoke(toolCtx)
	// Do not relabel the parent AgentRun deadline as a per-tool timeout. A
	// tool timeout is only reported when this call's own deadline expired while
	// the parent run still had time remaining.
	if errors.Is(toolCtx.Err(), context.DeadlineExceeded) && ctx.Err() == nil {
		return `{"error":"TOOL_TIMEOUT"}`
	}
	return result
}

func isRecoverableOutputError(err error) bool {
	if err == nil {
		return false
	}
	switch err.Error() {
	case "AGENT_OUTPUT_PROTOCOL_INVALID", "AGENT_INVALID_MESSAGE", "AGENT_INVALID_QUESTION", "AGENT_INVALID_PLAN", "AGENT_UNKNOWN_OUTPUT_TYPE":
		return true
	default:
		return false
	}
}

func appendOutputRepairTurn(chat []model.ChatMessage, previous, errorCode string) []model.ChatMessage {
	chat = append(chat, model.ChatMessage{Role: "assistant", Content: previous})
	return append(chat, model.ChatMessage{Role: "user", Content: outputRepairPrompt(errorCode)})
}

func outputRepairPrompt(errorCode string) string {
	switch errorCode {
	case "AGENT_INVALID_PLAN":
		return "Your previous output was rejected as AGENT_INVALID_PLAN. Return exactly one JSON object with exactly these top-level keys: type, markdown, structuredPlan. type must be PLAN_DRAFT; markdown must be non-empty; structuredPlan.slides must be a non-empty array and every slide must have a non-empty title. Do not include geometry, OOXML, engine, credential, or API fields. If no authorized source file exists, omit sourceRefs. Return JSON only, with no Markdown fences or prose."
	case "AGENT_INVALID_QUESTION":
		return "Your previous output was rejected as AGENT_INVALID_QUESTION. Return exactly one JSON object of type QUESTION with question, explicit questionType (TEXT, SINGLE_CHOICE, or MULTI_CHOICE), and options matching that type. Return JSON only, with no Markdown fences or prose."
	case "AGENT_INVALID_MESSAGE":
		return "Your previous output was rejected as AGENT_INVALID_MESSAGE. Return exactly {\"type\":\"MESSAGE\",\"content\":\"non-empty text\"} and no other keys. Return JSON only, with no Markdown fences or prose."
	default:
		return "Your previous output was rejected by the structured output protocol. Return exactly one supported JSON object of type MESSAGE, QUESTION, or PLAN_DRAFT according to the system contract. Return JSON only, with no Markdown fences or prose."
	}
}

func appendToolCallTurn(chat []model.ChatMessage, response model.ChatResponse, results []string) []model.ChatMessage {
	chat = append(chat, model.ChatMessage{
		Role:             "assistant",
		Content:          response.Content,
		ReasoningContent: response.ReasoningContent,
		ToolCalls:        append([]model.ToolCall(nil), response.ToolCalls...),
	})
	for index, call := range response.ToolCalls {
		result := ""
		if index < len(results) {
			result = results[index]
		}
		chat = append(chat, model.ChatMessage{Role: "tool", ToolCallID: call.ID, Content: result})
	}
	return chat
}

func (r *Runtime) persistFinal(ctx context.Context, run model.AgentRun, content string) error {
	content = strings.TrimSpace(content)
	if content == "" {
		return errors.New("AGENT_EMPTY_RESPONSE")
	}
	var envelope struct {
		Type           string          `json:"type"`
		Content        string          `json:"content"`
		Question       string          `json:"question"`
		QuestionType   string          `json:"questionType"`
		Options        []string        `json:"options"`
		Markdown       string          `json:"markdown"`
		StructuredPlan json.RawMessage `json:"structuredPlan"`
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(content), &raw); err != nil {
		return errors.New("AGENT_OUTPUT_PROTOCOL_INVALID")
	}
	if _, ok := raw["type"]; !ok {
		return errors.New("AGENT_OUTPUT_PROTOCOL_INVALID")
	}
	if err := json.Unmarshal([]byte(content), &envelope); err != nil {
		return errors.New("AGENT_OUTPUT_PROTOCOL_INVALID")
	}
	switch envelope.Type {
	case "MESSAGE":
		if !onlyKeys(raw, "type", "content") || envelope.Content == "" {
			return errors.New("AGENT_INVALID_MESSAGE")
		}
		_, err := r.Store.AddAssistantMessageForRun(ctx, run.MissionID, run.ID, run.LeaseToken, envelope.Content, "TEXT", map[string]any{"type": "MESSAGE"})
		return err
	case "QUESTION":
		if !onlyKeys(raw, "type", "question", "questionType", "options") || envelope.Question == "" || !validQuestion(envelope.QuestionType, envelope.Options) {
			return errors.New("AGENT_INVALID_QUESTION")
		}
		_, err := r.Store.SaveQuestionForRun(ctx, run.MissionID, run.ID, run.LeaseToken, envelope.Question, defaultString(envelope.QuestionType, "TEXT"), envelope.Options)
		if err != nil {
			return err
		}
		return ErrWaitingForInput
	case "PLAN_DRAFT":
		if !onlyKeys(raw, "type", "markdown", "structuredPlan") || envelope.Markdown == "" || len(envelope.StructuredPlan) == 0 {
			return errors.New("AGENT_INVALID_PLAN")
		}
		var plan any
		if err := json.Unmarshal(envelope.StructuredPlan, &plan); err != nil || specification.ValidateSemanticPlan(plan) != nil {
			return errors.New("AGENT_INVALID_PLAN")
		}
		_, err := r.Store.SaveDraftForRun(ctx, run.MissionID, run.ID, run.LeaseToken, envelope.Markdown, plan)
		return err
	default:
		return errors.New("AGENT_UNKNOWN_OUTPUT_TYPE")
	}
}

func (r *Runtime) tool(ctx context.Context, missionID, owner int64, call model.ToolCall) string {
	var readFileID int64
	if call.Function.Name == "read_material" {
		var readArgs map[string]any
		if err := json.Unmarshal([]byte(call.Function.Arguments), &readArgs); err != nil {
			return `{"error":"TOOL_ARGUMENTS_INVALID"}`
		}
		var ok bool
		readFileID, ok = requiredToolFileID(readArgs)
		if !ok {
			return `{"error":"TOOL_ARGUMENTS_INVALID"}`
		}
		// Reject the caller before writing the Mission activity audit. An
		// unauthorized Runtime invocation must not be able to pollute another
		// teacher's Mission event stream, even when no RAG request is sent.
		if err := r.authorizeMaterialRead(ctx, owner, missionID, readFileID); err != nil {
			return safeToolError(err)
		}
	}
	// Keep the audit deliberately narrow: tool name and Mission scope are
	// useful for proving the real Tool Calling loop, while arguments and tool
	// results may contain teacher material and must not enter activity logs.
	if err := r.Store.AddActivity(ctx, missionID, "AGENT_TOOL_CALLED", "Agent tool called", "AGENT_TOOL", call.Function.Name); err != nil {
		return `{"error":"AGENT_TOOL_AUDIT_FAILED"}`
	}
	var args map[string]any
	if err := json.Unmarshal([]byte(call.Function.Arguments), &args); err != nil {
		return `{"error":"TOOL_ARGUMENTS_INVALID"}`
	}
	switch call.Function.Name {
	case "search_materials":
		if r.RAG == nil {
			return `{"error":"RAG_NOT_CONFIGURED"}`
		}
		query, ok := requiredToolQuery(args)
		if !ok {
			return `{"error":"TOOL_ARGUMENTS_INVALID"}`
		}
		fileIDs, err := r.Store.AuthorizedMaterialFileIDs(ctx, owner, missionID)
		if err != nil {
			return `{"error":"TOOL_FILE_SCOPE_UNAVAILABLE"}`
		}
		snippets, err := r.RAG.Search(ctx, missionID, query, fileIDs)
		if err != nil {
			return safeToolError(err)
		}
		return marshal(snippets)
	case "read_material":
		if r.RAG == nil {
			return `{"error":"RAG_NOT_CONFIGURED"}`
		}
		locator, _ := args["locator"].(string)
		content, err := r.RAG.Read(ctx, missionID, readFileID, strings.TrimSpace(locator))
		if err != nil {
			return safeToolError(err)
		}
		return marshal(map[string]any{"fileId": readFileID, "locator": strings.TrimSpace(locator), "content": content})
	case "get_current_plan":
		draft, err := r.Store.CurrentDraft(ctx, owner, missionID)
		if err != nil {
			return safeToolError(err)
		}
		return marshal(draft)
	case "get_template_capability":
		if r.Capability == nil {
			return `{"error":"TEMPLATE_CAPABILITY_NOT_CONFIGURED"}`
		}
		result, err := r.Capability.GetForOwner(ctx, owner, missionID)
		if err != nil {
			return safeToolError(err)
		}
		return marshal(result)
	default:
		return `{"error":"TOOL_NOT_ALLOWED"}`
	}
}

func (r *Runtime) authorizeMaterialRead(ctx context.Context, owner, missionID, fileID int64) error {
	if owner <= 0 || missionID <= 0 || fileID <= 0 {
		return errors.New("RAG_FILE_NOT_AUTHORIZED")
	}
	files, err := r.Store.MissionFiles(ctx, owner, missionID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return errors.New("RAG_FILE_NOT_AUTHORIZED")
		}
		return errors.New("TOOL_FILE_SCOPE_UNAVAILABLE")
	}
	for _, file := range files {
		if file.ParseStatus != "READY" ||
			(file.Role != "MATERIAL" && file.Role != "TEACHING_PLAN") ||
			(file.Provenance != "TEACHER" && file.Provenance != "MATERIAL") {
			continue
		}
		if file.ID == fileID || file.FileObject.ID == fileID {
			return nil
		}
	}
	return errors.New("RAG_FILE_NOT_AUTHORIZED")
}

func toolDefinitions() []model.ToolDefinition {
	return []model.ToolDefinition{
		{Type: "function", Function: model.ToolFunction{Name: "search_materials", Description: "Search only authorized materials attached to the current Mission. Use read_material for bounded source content when a search hit needs verification.", Parameters: map[string]any{"type": "object", "additionalProperties": false, "required": []string{"query"}, "properties": map[string]any{"query": map[string]any{"type": "string"}}}}},
		{Type: "function", Function: model.ToolFunction{Name: "read_material", Description: "Read authorized content from one attached Mission material. fileId may be the Mission file id or its file object id; locator may be a chunk number such as chunk:3 or a text locator.", Parameters: map[string]any{"type": "object", "additionalProperties": false, "required": []string{"fileId"}, "properties": map[string]any{"fileId": map[string]any{"type": "integer", "minimum": 1}, "locator": map[string]any{"type": "string"}}}}},
		{Type: "function", Function: model.ToolFunction{Name: "get_template_capability", Description: "Read semantic capability of the Mission template.", Parameters: map[string]any{"type": "object", "additionalProperties": false}}},
		{Type: "function", Function: model.ToolFunction{Name: "get_current_plan", Description: "Read the current planning draft for this Mission.", Parameters: map[string]any{"type": "object", "additionalProperties": false}}},
	}
}
func systemPrompt() string {
	return systemPromptForVersion(model.PlanAgentPromptVersion)
}

func systemPromptForVersion(version string) string {
	if version != model.PlanAgentPromptVersion {
		return ""
	}
	return "You are the LessonForge planning agent. Work only with the current Mission context. Use search_materials to locate authorized material evidence and read_material when the relevant attached file needs bounded content. Use tools when needed, but do not block a semantic plan on unavailable optional sources. After the teacher answers a QUESTION, continue to the next necessary stage; if no required clarification remains, return a PLAN_DRAFT instead of repeating the question or returning prose. Return exactly one JSON object and nothing else: no explanation, no Markdown fences, and no prose before or after it. The object type must be MESSAGE, QUESTION, or PLAN_DRAFT. MESSAGE must be exactly {\"type\":\"MESSAGE\",\"content\":\"...\"}. QUESTION must be exactly {\"type\":\"QUESTION\",\"question\":\"...\",\"questionType\":\"TEXT\",\"options\":[]} for a free-text question, or use questionType SINGLE_CHOICE/MULTI_CHOICE with 2-12 options. PLAN_DRAFT must have exactly these top-level keys: type, markdown, structuredPlan; type must be PLAN_DRAFT, markdown must be non-empty, and structuredPlan must contain a non-empty slides array where every slide has a non-empty title. Keep PLAN_DRAFT semantic only and omit sourceRefs when no authorized source file exists. Never output shape IDs, coordinates, geometry, OOXML, credentials, API fields, or hidden reasoning. Do not invent material facts; label teacher or AI provenance."
}
func marshal(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"error":"TOOL_RESPONSE_SERIALIZATION_FAILED"}`
	}
	return string(b)
}
func safeToolError(err error) string {
	return marshal(map[string]string{"error": safeToolErrorCode(err)})
}

func safeToolErrorCode(err error) string {
	if err == nil {
		return "TOOL_FAILED"
	}
	var providerErr *model.HTTPError
	if errors.As(err, &providerErr) {
		switch providerErr.Status {
		case 401, 403:
			return "AUTHENTICATION_FAILED"
		case 402:
			return "INSUFFICIENT_BALANCE"
		case 404:
			return "MODEL_NOT_FOUND"
		case 408, 504:
			return "MODEL_TIMEOUT"
		case 429:
			return "RATE_LIMITED"
		case 500, 502, 503:
			return "PROVIDER_UNAVAILABLE"
		default:
			return "MODEL_PROVIDER_ERROR"
		}
	}
	if errors.Is(err, model.ErrTimeout) {
		return "MODEL_TIMEOUT"
	}
	if errors.Is(err, rag.ErrTimeout) {
		return "RAG_TIMEOUT"
	}

	// Preserve only the stable, already-sanitized code prefix from internal
	// adapters. Never return a wrapped error string: it may contain file paths,
	// SQL details, Java response text, or other implementation data.
	code := strings.TrimSpace(strings.SplitN(err.Error(), ":", 2)[0])
	if safeToolCode(code) {
		return code
	}
	return "TOOL_FAILED"
}

func safeToolCode(code string) bool {
	if code == "" || len(code) > 96 {
		return false
	}
	if !(strings.HasPrefix(code, "RAG_") || strings.HasPrefix(code, "TEMPLATE_") || strings.HasPrefix(code, "TOOL_")) {
		return false
	}
	for _, r := range code {
		if (r < 'A' || r > 'Z') && (r < '0' || r > '9') && r != '_' {
			return false
		}
	}
	return true
}
func defaultString(a, b string) string {
	if a == "" {
		return b
	}
	return a
}

func onlyKeys(values map[string]json.RawMessage, allowed ...string) bool {
	set := make(map[string]struct{}, len(allowed))
	for _, key := range allowed {
		set[key] = struct{}{}
	}
	for key := range values {
		if _, ok := set[key]; !ok {
			return false
		}
	}
	return true
}

func validQuestion(questionType string, options []string) bool {
	if strings.TrimSpace(questionType) == "" {
		return false
	}
	switch questionType {
	case "TEXT":
		return len(options) == 0
	case "SINGLE_CHOICE", "MULTI_CHOICE":
		if len(options) < 2 || len(options) > 12 {
			return false
		}
		seen := map[string]bool{}
		for _, option := range options {
			if strings.TrimSpace(option) == "" || seen[option] {
				return false
			}
			seen[option] = true
		}
		return true
	default:
		return false
	}
}

func requiredToolQuery(args map[string]any) (string, bool) {
	query, ok := args["query"].(string)
	query = strings.TrimSpace(query)
	return query, ok && query != ""
}

func requiredToolFileID(args map[string]any) (int64, bool) {
	value, ok := args["fileId"]
	if !ok {
		return 0, false
	}
	switch number := value.(type) {
	case float64:
		if number <= 0 || number != float64(int64(number)) {
			return 0, false
		}
		return int64(number), true
	case int64:
		return number, number > 0
	case int:
		return int64(number), number > 0
	case string:
		parsed, err := strconv.ParseInt(strings.TrimSpace(number), 10, 64)
		return parsed, err == nil && parsed > 0
	default:
		return 0, false
	}
}
