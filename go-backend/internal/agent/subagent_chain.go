package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"lessonforge.local/backend/internal/model"
)

// SubagentRole is the stable role vocabulary for the first production chain.
// The outer AgentRun still owns the lease, cancellation and final Mission
// output; a role only owns one bounded model turn and its allowed tools.
type SubagentRole string

const (
	SubagentRequirementClarifier SubagentRole = "REQUIREMENT_CLARIFIER"
	SubagentMaterialResearcher   SubagentRole = "MATERIAL_RESEARCHER"
	SubagentTemplateAnalyzer     SubagentRole = "TEMPLATE_ANALYZER"
	SubagentPlanComposer         SubagentRole = "PLAN_COMPOSER"
)

type subagentChainInput struct {
	Mission          model.Mission
	Messages         []model.Message
	Files            []model.MissionFile
	Questions        []model.Question
	CurrentDraft     *model.PlanningDraft
	ConversationNote string
}

type subagentClarifierOutput struct {
	Type         string         `json:"type"`
	Question     string         `json:"question,omitempty"`
	QuestionType string         `json:"questionType,omitempty"`
	Options      []string       `json:"options,omitempty"`
	Requirements map[string]any `json:"requirements,omitempty"`
}

type subagentEvidenceOutput struct {
	Type    string `json:"type"`
	Summary string `json:"summary,omitempty"`
	Sources []any  `json:"sources,omitempty"`
	Reason  string `json:"reason,omitempty"`
}

// runSubagentChain is intentionally sequential. Each role receives a bounded
// handoff from the previous role, while the existing AgentRun remains the
// durable queue/lease boundary. This means an unanswered clarification still
// pauses exactly like the old path and a teacher answer creates the normal new
// AgentRun through the existing Store contract.
func (r *Runtime) runSubagentChain(
	ctx context.Context,
	run model.AgentRun,
	owner int64,
	mission model.Mission,
	connection model.ResolvedConnection,
	frozen frozenIdentity,
	messages []model.Message,
	files []model.MissionFile,
	questions []model.Question,
	currentDraft *model.PlanningDraft,
	conversationNote string,
) error {
	if !frozen.Capabilities.SupportsTools || !frozen.Capabilities.SupportsJSONMode {
		return errors.New("MODEL_CAPABILITY_NOT_SUPPORTED")
	}
	base := subagentContext(subagentChainInput{
		Mission:          mission,
		Messages:         messages,
		Files:            files,
		Questions:        questions,
		CurrentDraft:     currentDraft,
		ConversationNote: conversationNote,
	})
	var err error

	// Evidence is collected before clarification. The clarifier must see the
	// actual bounded evidence handoffs, otherwise it can ask a question that
	// the attached material or template already answers.
	research := `{"type":"SKIPPED","reason":"NO_AUTHORIZED_MATERIAL"}`
	if hasResearchMaterial(files) {
		research, err = r.runSubagentStage(ctx, run, owner, connection, SubagentMaterialResearcher, base, subagentToolSet("search_materials", "read_material"))
		if err != nil {
			return err
		}
		var evidence subagentEvidenceOutput
		if err := decodeSubagentJSON(research, &evidence); err != nil || (evidence.Type != "EVIDENCE" && evidence.Type != "SKIPPED") {
			return errors.New("SUBAGENT_MATERIAL_RESEARCHER_INVALID")
		}
	}

	template := `{"type":"SKIPPED","reason":"NO_TEMPLATE_ATTACHED"}`
	if hasTemplateFile(files) {
		template, err = r.runSubagentStage(ctx, run, owner, connection, SubagentTemplateAnalyzer, base, subagentToolSet("get_template_capability"))
		if err != nil {
			return err
		}
		var capability subagentEvidenceOutput
		if err := decodeSubagentJSON(template, &capability); err != nil || (capability.Type != "TEMPLATE_CANDIDATE" && capability.Type != "TEMPLATE" && capability.Type != "SKIPPED") {
			return errors.New("SUBAGENT_TEMPLATE_ANALYZER_INVALID")
		}
	}

	evidenceContext := subagentEvidenceContext(base, research, template)
	clarifier, err := r.runSubagentStage(ctx, run, owner, connection, SubagentRequirementClarifier, evidenceContext, nil)
	if err != nil {
		return err
	}
	var clarification subagentClarifierOutput
	var clarificationRaw map[string]json.RawMessage
	if err := decodeSubagentJSON(clarifier, &clarificationRaw); err != nil || !onlyKeys(clarificationRaw, "type", "question", "questionType", "options", "requirements") {
		return errors.New("SUBAGENT_REQUIREMENT_CLARIFIER_INVALID")
	}
	if err := decodeSubagentJSON(clarifier, &clarification); err != nil {
		return errors.New("SUBAGENT_REQUIREMENT_CLARIFIER_INVALID")
	}
	clarification.Type = strings.ToUpper(strings.TrimSpace(clarification.Type))
	clarification.Question = strings.TrimSpace(clarification.Question)
	clarification.QuestionType = strings.ToUpper(strings.TrimSpace(clarification.QuestionType))
	for index := range clarification.Options {
		clarification.Options[index] = strings.TrimSpace(clarification.Options[index])
	}
	if clarification.Type == "QUESTION" && !onlyKeys(clarificationRaw, "type", "question", "questionType", "options") {
		return errors.New("SUBAGENT_REQUIREMENT_CLARIFIER_INVALID_QUESTION")
	}
	if clarification.Type == "READY" && !onlyKeys(clarificationRaw, "type", "requirements") {
		return errors.New("SUBAGENT_REQUIREMENT_CLARIFIER_INVALID_READY")
	}
	if err := validateSubagentClarifierOutput(clarification); err != nil {
		return err
	}
	if clarification.Type == "QUESTION" {
		questionJSON, err := json.Marshal(map[string]any{
			"type":         "QUESTION",
			"question":     clarification.Question,
			"questionType": clarification.QuestionType,
			"options":      clarification.Options,
		})
		if err != nil {
			return err
		}
		if err := r.persistFinal(ctx, run, string(questionJSON)); err != nil {
			return err
		}
		return ErrWaitingForInput
	}
	if clarification.Type != "READY" {
		return errors.New("SUBAGENT_REQUIREMENT_CLARIFIER_INVALID")
	}

	composerPrompt := evidenceContext +
		"\n\nRequirement clarifier handoff:\n" + truncateRunes(clarifier, 12000) +
		"\n\nYou are now the final plan composer."
	composer, err := r.runSubagentStage(ctx, run, owner, connection, SubagentPlanComposer, composerPrompt, nil)
	if err != nil {
		return err
	}
	if err := r.persistFinal(ctx, run, composer); err != nil {
		if !isRecoverableOutputError(err) {
			return err
		}
		composer, err = r.runSubagentStage(ctx, run, owner, connection, SubagentPlanComposer, composerPrompt+"\n\nYour previous response was rejected as "+err.Error()+". Return only a valid PLAN_DRAFT object with type, markdown, and structuredPlan.slides.", nil)
		if err != nil {
			return err
		}
		return r.persistFinal(ctx, run, composer)
	}
	return nil
}

func (r *Runtime) runSubagentStage(ctx context.Context, run model.AgentRun, owner int64, connection model.ResolvedConnection, role SubagentRole, contextText string, tools []model.ToolDefinition) (string, error) {
	label := subagentRoleLabel(role)
	startKey := "agent-run:" + run.ID + ":subagent:" + string(role) + ":started"
	finishKey := "agent-run:" + run.ID + ":subagent:" + string(role) + ":completed"
	if err := r.Store.AddActivityIdempotent(ctx, run.MissionID, "SUBAGENT_STARTED", "子智能体开始："+label, "AGENT_RUN", run.ID, startKey); err != nil {
		return "", err
	}
	chat := []model.ChatMessage{{Role: "system", Content: subagentSystemPrompt(role)}, {Role: "user", Content: contextText}}
	allowed := map[string]bool{}
	for _, definition := range tools {
		allowed[definition.Function.Name] = true
	}
	limit := r.MaxToolCalls
	if limit <= 0 {
		limit = 8
	}
	toolCallsUsed := 0
	for {
		started := time.Now()
		request := model.ChatRequest{Messages: chat, Tools: tools, MaxTokens: 4096, JSONMode: true}
		response, err := r.callModel(ctx, connection, request)
		status := response.RawStatus
		if providerErr, ok := err.(*model.HTTPError); ok {
			status = providerErr.Status
		}
		audit := model.ModelConnection{ID: connection.ID, OwnerUserID: owner, Provider: connection.Provider, Protocol: connection.Protocol, BaseURL: connection.BaseURL, ModelID: connection.ModelID, Capabilities: connection.Capabilities, CapabilityVerification: connection.CapabilityVerification, CapabilitiesSet: true}
		if auditErr := r.Store.RecordModelAudit(ctx, uuid.New(), owner, run.MissionID, connection.ID, audit, "SUBAGENT_"+string(role), status, time.Since(started)); auditErr != nil {
			return "", fmt.Errorf("record subagent model audit: %w", auditErr)
		}
		if err != nil {
			return "", err
		}
		if err := r.Store.MarkConnectionUsed(ctx, owner, connection.ID); err != nil {
			return "", fmt.Errorf("mark subagent model connection used: %w", err)
		}
		if len(response.ToolCalls) > 0 {
			if toolCallBudgetExceeded(toolCallsUsed, len(response.ToolCalls), limit) {
				return "", errors.New("SUBAGENT_TOOL_CALL_LIMIT")
			}
			toolCallsUsed += len(response.ToolCalls)
			results := make([]string, 0, len(response.ToolCalls))
			for _, call := range response.ToolCalls {
				if !allowed[call.Function.Name] {
					results = append(results, `{"error":"SUBAGENT_TOOL_NOT_ALLOWED"}`)
					continue
				}
				results = append(results, r.runTool(ctx, run.MissionID, owner, call))
			}
			chat = appendToolCallTurn(chat, response, results)
			continue
		}
		content := strings.TrimSpace(response.Content)
		if content == "" {
			return "", errors.New("SUBAGENT_EMPTY_RESPONSE")
		}
		if err := r.Store.AddActivityIdempotent(ctx, run.MissionID, "SUBAGENT_COMPLETED", "子智能体完成："+label, "AGENT_RUN", run.ID, finishKey); err != nil {
			return "", err
		}
		return content, nil
	}
}

func (r *Runtime) callModel(ctx context.Context, connection model.ResolvedConnection, request model.ChatRequest) (model.ChatResponse, error) {
	if r.chat != nil {
		return r.chat(ctx, connection, request)
	}
	return r.Models.Chat(ctx, connection, request)
}

func subagentSystemPrompt(role SubagentRole) string {
	common := "Return exactly one JSON object and nothing else. Do not output Markdown fences, hidden reasoning, or explanatory prose. Use only server-owned facts and clearly label unavailable evidence."
	switch role {
	case SubagentRequirementClarifier:
		return common + " You are the requirement clarification subagent for LessonForge. Identify only decisions that genuinely block a reliable courseware plan. Do not ask a question merely to collect preferences when a safe default or existing teacher fact is sufficient. If one blocking decision remains, return {\"type\":\"QUESTION\",\"question\":\"...\",\"questionType\":\"SINGLE_CHOICE\",\"options\":[\"...\",\"...\"]} with 2 to 4 mutually exclusive options. Ask at most one question. If planning can proceed, return {\"type\":\"READY\",\"requirements\":{...}}."
	case SubagentMaterialResearcher:
		return common + " You are the material research subagent. Use search_materials and read_material only when attached teaching materials can provide evidence. Do not invent content and do not treat a search hit as verified until bounded material content is read. Return {\"type\":\"EVIDENCE\",\"summary\":\"...\",\"sources\":[{\"fileId\":1,\"locator\":\"...\",\"claim\":\"...\"}]} or {\"type\":\"SKIPPED\",\"reason\":\"...\"}."
	case SubagentTemplateAnalyzer:
		return common + " You are the template capability reader. Use get_template_capability only for the explicitly bound Mission TEMPLATE file. The current server tool returns a Go-owned upstream candidate binding, not an Engine-confirmed native profile: executionReady=false and engineNativeProfilePresent=false must never be described as confirmed generation capability. Do not design slides and do not invent component support. Return {\"type\":\"TEMPLATE_CANDIDATE\",\"summary\":\"...\",\"sources\":[...]} or {\"type\":\"SKIPPED\",\"reason\":\"...\"}."
	case SubagentPlanComposer:
		return common + " You are the final plan composition subagent. Combine the clarification, material, and template handoffs into one semantic courseware plan. Return exactly {\"type\":\"PLAN_DRAFT\",\"markdown\":\"...\",\"structuredPlan\":{\"slides\":[...]}}. The slides array must be non-empty and every slide must have a non-empty title. Do not output coordinates, shape IDs, OOXML, credentials, engine calls, or unsupported template claims."
	default:
		return common
	}
}

func subagentToolSet(names ...string) []model.ToolDefinition {
	all := toolDefinitions()
	wanted := map[string]bool{}
	for _, name := range names {
		wanted[name] = true
	}
	result := make([]model.ToolDefinition, 0, len(names))
	for _, definition := range all {
		if wanted[definition.Function.Name] {
			result = append(result, definition)
		}
	}
	return result
}

func subagentContext(input subagentChainInput) string {
	mission, _ := json.Marshal(input.Mission)
	files, _ := json.Marshal(input.Files)
	questions, _ := json.Marshal(input.Questions)
	messages, _ := json.Marshal(input.Messages)
	contextText := "Server-owned Mission:\n" + string(mission) +
		"\n\nTeacher conversation:\n" + truncateRunes(string(messages), 18000) +
		"\n\nAuthorized files:\n" + truncateRunes(string(files), 12000) +
		"\n\nPersisted questions and answers:\n" + truncateRunes(string(questions), 12000)
	if input.CurrentDraft != nil {
		draft, _ := json.Marshal(input.CurrentDraft)
		contextText += "\n\nCurrent planning draft:\n" + truncateRunes(string(draft), 16000)
	}
	if note := strings.TrimSpace(input.ConversationNote); note != "" {
		contextText += "\n\nHistorical conversation summary:\n" + truncateRunes(note, 12000)
	}
	return contextText
}

func subagentEvidenceContext(base, research, template string) string {
	return base +
		"\n\nMaterial researcher handoff:\n" + truncateRunes(research, 16000) +
		"\n\nTemplate capability handoff:\n" + truncateRunes(template, 16000)
}

func decodeSubagentJSON(content string, target any) error {
	content = strings.TrimSpace(content)
	if strings.HasPrefix(content, "```") {
		content = strings.TrimPrefix(content, "```")
		content = strings.TrimPrefix(content, "json")
		content = strings.TrimSuffix(strings.TrimSpace(content), "```")
	}
	return json.Unmarshal([]byte(strings.TrimSpace(content)), target)
}

func hasResearchMaterial(files []model.MissionFile) bool {
	for _, file := range files {
		if strings.EqualFold(file.Role, "MATERIAL") || strings.EqualFold(file.Role, "TEACHING_PLAN") {
			return true
		}
	}
	return false
}

func hasTemplateFile(files []model.MissionFile) bool {
	for _, file := range files {
		// A .pptx can be ordinary teaching material. Only the server-owned
		// Mission role is an authorization to inspect it as a template.
		if strings.EqualFold(strings.TrimSpace(file.Role), "TEMPLATE") {
			return true
		}
	}
	return false
}

func validateSubagentClarifierOutput(output subagentClarifierOutput) error {
	switch strings.ToUpper(strings.TrimSpace(output.Type)) {
	case "QUESTION":
		if strings.TrimSpace(output.Question) == "" || !validClarifierQuestion(output.QuestionType, output.Options) {
			return errors.New("SUBAGENT_REQUIREMENT_CLARIFIER_INVALID_QUESTION")
		}
		return nil
	case "READY":
		if output.Requirements == nil {
			return errors.New("SUBAGENT_REQUIREMENT_CLARIFIER_INVALID_READY")
		}
		return nil
	default:
		return errors.New("SUBAGENT_REQUIREMENT_CLARIFIER_INVALID")
	}
}

func validClarifierQuestion(questionType string, options []string) bool {
	return strings.ToUpper(strings.TrimSpace(questionType)) == "SINGLE_CHOICE" && len(options) >= 2 && len(options) <= 4 && validQuestion(questionType, options)
}

func subagentRoleLabel(role SubagentRole) string {
	switch role {
	case SubagentRequirementClarifier:
		return "需求澄清"
	case SubagentMaterialResearcher:
		return "材料研究"
	case SubagentTemplateAnalyzer:
		return "模板分析"
	case SubagentPlanComposer:
		return "方案编排"
	default:
		return string(role)
	}
}
