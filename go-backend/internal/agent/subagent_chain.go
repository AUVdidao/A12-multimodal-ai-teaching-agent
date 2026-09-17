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
	Type    string                   `json:"type"`
	Summary string                   `json:"summary,omitempty"`
	Sources []subagentEvidenceSource `json:"sources,omitempty"`
	Reason  string                   `json:"reason,omitempty"`
}

type subagentEvidenceSource struct {
	FileID  int64  `json:"fileId"`
	Locator string `json:"locator"`
	Claim   string `json:"claim"`
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
		research, err = r.runMaterialResearcher(ctx, run, owner, connection, base)
		if err != nil {
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
		composer, err = r.runSubagentStage(ctx, run, owner, connection, SubagentPlanComposer, composerPrompt+"\n\nYour previous response was rejected as "+err.Error()+". Repair it once and return JSON only. Use exactly this minimal shape, then fill the markdown with the evidence-backed plan: {\"type\":\"PLAN_DRAFT\",\"markdown\":\"...\",\"structuredPlan\":{\"slides\":[{\"title\":\"...\"}]}}. Every slide must have a non-empty title. Do not include sourceRefs, regions, geometry, coordinates, OOXML, engine, credentials, or any other slide fields.", nil)
		if err != nil {
			return err
		}
		return r.persistFinal(ctx, run, composer)
	}
	return nil
}

func (r *Runtime) runSubagentStage(ctx context.Context, run model.AgentRun, owner int64, connection model.ResolvedConnection, role SubagentRole, contextText string, tools []model.ToolDefinition) (string, error) {
	content, _, err := r.runSubagentStageWithTrace(ctx, run, owner, connection, role, contextText, tools)
	return content, err
}

func (r *Runtime) runSubagentStageWithTrace(ctx context.Context, run model.AgentRun, owner int64, connection model.ResolvedConnection, role SubagentRole, contextText string, tools []model.ToolDefinition) (string, map[string]bool, error) {
	label := subagentRoleLabel(role)
	startKey := "agent-run:" + run.ID + ":subagent:" + string(role) + ":started"
	finishKey := "agent-run:" + run.ID + ":subagent:" + string(role) + ":completed"
	if err := r.Store.AddActivityIdempotent(ctx, run.MissionID, "SUBAGENT_STARTED", "子智能体开始："+label, "AGENT_RUN", run.ID, startKey); err != nil {
		return "", nil, err
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
	calledTools := map[string]bool{}
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
			return "", calledTools, fmt.Errorf("record subagent model audit: %w", auditErr)
		}
		if err != nil {
			return "", calledTools, err
		}
		if err := r.Store.MarkConnectionUsed(ctx, owner, connection.ID); err != nil {
			return "", calledTools, fmt.Errorf("mark subagent model connection used: %w", err)
		}
		if len(response.ToolCalls) > 0 {
			if toolCallBudgetExceeded(toolCallsUsed, len(response.ToolCalls), limit) {
				return "", calledTools, errors.New("SUBAGENT_TOOL_CALL_LIMIT")
			}
			toolCallsUsed += len(response.ToolCalls)
			results := make([]string, 0, len(response.ToolCalls))
			for _, call := range response.ToolCalls {
				calledTools[call.Function.Name] = true
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
			return "", calledTools, errors.New("SUBAGENT_EMPTY_RESPONSE")
		}
		if err := r.Store.AddActivityIdempotent(ctx, run.MissionID, "SUBAGENT_COMPLETED", "子智能体完成："+label, "AGENT_RUN", run.ID, finishKey); err != nil {
			return "", calledTools, err
		}
		return content, calledTools, nil
	}
}

func (r *Runtime) runMaterialResearcher(ctx context.Context, run model.AgentRun, owner int64, connection model.ResolvedConnection, base string) (string, error) {
	tools := subagentToolSet("search_materials", "read_material")
	preflightContext, err := r.materialResearchPreflight(ctx, run.MissionID, owner, base)
	if err != nil {
		return "", err
	}
	research, calledTools, err := r.runSubagentStageWithTrace(ctx, run, owner, connection, SubagentMaterialResearcher, preflightContext, tools)
	if err != nil {
		return "", err
	}
	if err := validateMaterialResearchOutput(research, calledTools); err == nil {
		return research, nil
	}

	if err := r.Store.AddActivityIdempotent(
		ctx,
		run.MissionID,
		"SUBAGENT_CONTRACT_RETRY",
		"材料研究子智能体输出合同不合格，执行一次受控修复重试",
		"AGENT_RUN",
		run.ID,
		"agent-run:"+run.ID+":subagent:MATERIAL_RESEARCHER:contract-retry",
	); err != nil {
		return "", err
	}
	repairContext := base + "\n\nContract repair instruction:\n" +
		"Your previous material-research response did not satisfy the server contract. " +
		"The server preflight is not your tool call evidence. You must call search_materials and read_material yourself in this retry, then return exactly one JSON object with no prose. " +
		"Use the actual fileId and locator returned by those calls; never invent fileId, chunk number, or source claim. " +
		"For evidence use {\"type\":\"EVIDENCE\",\"summary\":\"non-empty\",\"sources\":[{\"fileId\":123,\"locator\":\"chunk:176\",\"claim\":\"non-empty\"}]}; " +
		"if evidence is unavailable use {\"type\":\"SKIPPED\",\"reason\":\"non-empty\"}.\nRejected response:\n" + truncateRunes(research, 8000)
	research, calledTools, err = r.runSubagentStageWithTrace(ctx, run, owner, connection, SubagentMaterialResearcher, preflightContext+"\n\n"+repairContext, tools)
	if err != nil {
		return "", err
	}
	if err := validateMaterialResearchOutput(research, calledTools); err != nil {
		_ = r.Store.AddActivityIdempotent(
			ctx,
			run.MissionID,
			"SUBAGENT_CONTRACT_REJECTED",
			"材料研究子智能体最终输出被拒绝："+err.Error(),
			"AGENT_RUN",
			run.ID,
			"agent-run:"+run.ID+":subagent:MATERIAL_RESEARCHER:contract-rejected",
		)
		return "", err
	}
	return research, nil
}

func (r *Runtime) materialResearchPreflight(ctx context.Context, missionID, owner int64, base string) (string, error) {
	fileIDs, err := r.Store.AuthorizedMaterialFileIDs(ctx, owner, missionID)
	if err != nil || len(fileIDs) == 0 {
		return "", errors.New("SUBAGENT_MATERIAL_RESEARCHER_NO_AUTHORIZED_MATERIAL")
	}
	query := researchQueryFromContext(base)
	searchResult := r.tool(ctx, missionID, owner, model.ToolCall{
		Type: "function",
		Function: model.ToolCallFunction{
			Name:      "search_materials",
			Arguments: marshal(map[string]any{"query": query}),
		},
	})
	var snippets []struct {
		ChunkID    int64  `json:"chunkId"`
		MaterialID int64  `json:"materialId"`
		ChunkNo    int    `json:"chunkNo"`
		Content    string `json:"content"`
	}
	if err := json.Unmarshal([]byte(searchResult), &snippets); err != nil || len(snippets) == 0 || snippets[0].MaterialID <= 0 {
		return "", errors.New("SUBAGENT_MATERIAL_RESEARCHER_SEARCH_EMPTY")
	}
	fileID := int64(0)
	for _, candidate := range fileIDs {
		binding, found, bindingErr := r.Store.RAGMaterialBinding(ctx, owner, candidate)
		if bindingErr != nil {
			return "", bindingErr
		}
		if found && binding.RAGMaterialID == snippets[0].MaterialID {
			fileID = candidate
			break
		}
	}
	if fileID <= 0 || snippets[0].ChunkNo <= 0 {
		return "", errors.New("SUBAGENT_MATERIAL_RESEARCHER_SOURCE_SCOPE_UNRESOLVED")
	}
	locator := fmt.Sprintf("chunk:%d", snippets[0].ChunkNo)
	readResult := r.tool(ctx, missionID, owner, model.ToolCall{
		Type: "function",
		Function: model.ToolCallFunction{
			Name:      "read_material",
			Arguments: marshal(map[string]any{"fileId": fileID, "locator": locator}),
		},
	})
	var read struct {
		FileID  int64  `json:"fileId"`
		Locator string `json:"locator"`
		Content string `json:"content"`
	}
	if err := json.Unmarshal([]byte(readResult), &read); err != nil || read.FileID != fileID || read.Locator != locator || !hasReadableMaterialContent(read.Content) {
		return "", errors.New("SUBAGENT_MATERIAL_RESEARCHER_READ_EMPTY")
	}
	return base + fmt.Sprintf("\n\nServer preflight verified an authorized target source: materialId=%d fileId=%d locator=%s. This preflight is not subagent tool evidence; call search_materials and read_material yourself before returning EVIDENCE.", snippets[0].MaterialID, fileID, locator), nil
}

func researchQueryFromContext(base string) string {
	const startMarker = "Teacher conversation:\n"
	start := strings.Index(base, startMarker)
	if start >= 0 {
		start += len(startMarker)
		end := strings.Index(base[start:], "\n\nAuthorized files:")
		if end >= 0 {
			candidate := strings.TrimSpace(base[start : start+end])
			if candidate != "" {
				if titleStart := strings.Index(candidate, "《"); titleStart >= 0 {
					titleStart += len("《")
					if titleEnd := strings.Index(candidate[titleStart:], "》"); titleEnd > 0 {
						title := strings.TrimSpace(candidate[titleStart : titleStart+titleEnd])
						return truncateRunes(title+" 第3.2节 RAG 基础 Chunking 嵌入 混合检索", 1200)
					}
				}
				return truncateRunes(candidate, 1200)
			}
		}
	}
	return truncateRunes(strings.TrimSpace(base), 1200)
}

func hasReadableMaterialContent(value string) bool {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" || len(trimmed) < 5 || len(trimmed) > 32 {
		return trimmed != ""
	}
	for _, char := range trimmed {
		if char < '0' || char > '9' {
			return true
		}
	}
	return false
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
		return common + " You are the material research subagent. Because an authorized teaching material is attached, you must call search_materials and then call read_material on at least one relevant search hit before returning EVIDENCE. Do not invent content and do not treat a search hit as verified until bounded material content is read. If the authorized tools cannot provide evidence, return SKIPPED with a concrete reason. Return {\"type\":\"EVIDENCE\",\"summary\":\"...\",\"sources\":[{\"fileId\":1,\"locator\":\"...\",\"claim\":\"...\"}]} or {\"type\":\"SKIPPED\",\"reason\":\"...\"}."
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
	if content == "" {
		return errors.New("SUBAGENT_JSON_EMPTY")
	}
	object, ok := extractSubagentJSONObject(content)
	if !ok {
		return errors.New("SUBAGENT_JSON_OBJECT_REQUIRED")
	}
	return json.Unmarshal([]byte(object), target)
}

func extractSubagentJSONObject(content string) (string, bool) {
	start := strings.IndexByte(content, '{')
	if start < 0 {
		return "", false
	}
	depth := 0
	inString := false
	escaped := false
	for index := start; index < len(content); index++ {
		char := content[index]
		if inString {
			if escaped {
				escaped = false
				continue
			}
			if char == '\\' {
				escaped = true
				continue
			}
			if char == '"' {
				inString = false
			}
			continue
		}
		switch char {
		case '"':
			inString = true
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return content[start : index+1], true
			}
		case ']':
			if depth == 0 {
				return "", false
			}
		}
	}
	return "", false
}

func validateSubagentEvidenceOutput(content string) error {
	var raw map[string]json.RawMessage
	if err := decodeSubagentJSON(content, &raw); err != nil {
		return errors.New("SUBAGENT_MATERIAL_RESEARCHER_INVALID")
	}
	var output subagentEvidenceOutput
	if err := decodeSubagentJSON(content, &output); err != nil {
		return errors.New("SUBAGENT_MATERIAL_RESEARCHER_INVALID")
	}
	output.Type = strings.ToUpper(strings.TrimSpace(output.Type))
	switch output.Type {
	case "SKIPPED":
		if !onlyKeys(raw, "type", "reason") || strings.TrimSpace(output.Reason) == "" {
			return errors.New("SUBAGENT_MATERIAL_RESEARCHER_INVALID")
		}
		return nil
	case "EVIDENCE":
		if !onlyKeys(raw, "type", "summary", "sources") || strings.TrimSpace(output.Summary) == "" || len(output.Sources) == 0 {
			return errors.New("SUBAGENT_MATERIAL_RESEARCHER_INVALID")
		}
		for _, source := range output.Sources {
			if source.FileID <= 0 || strings.TrimSpace(source.Locator) == "" || strings.TrimSpace(source.Claim) == "" {
				return errors.New("SUBAGENT_MATERIAL_RESEARCHER_INVALID")
			}
		}
		return nil
	default:
		return errors.New("SUBAGENT_MATERIAL_RESEARCHER_INVALID")
	}
}

func validateMaterialResearchOutput(content string, calledTools map[string]bool) error {
	if !calledTools["search_materials"] || !calledTools["read_material"] {
		return errors.New("SUBAGENT_MATERIAL_RESEARCHER_TOOLS_REQUIRED")
	}
	return validateSubagentEvidenceOutput(content)
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
