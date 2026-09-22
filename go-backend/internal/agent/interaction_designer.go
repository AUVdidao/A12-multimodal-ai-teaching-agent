package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"lessonforge.local/backend/internal/interaction"
	"lessonforge.local/backend/internal/model"
)

type interactionProposal struct {
	Type      string                 `json:"type"`
	Questions []interaction.Question `json:"questions"`
}

// DesignInteractionQuestions is the single interaction-design subagent entrypoint.
// It runs after a specification is locked and before the HTML renderer. The
// renderer remains deterministic; only the learner-facing question content is
// delegated to the model.
func (r *Runtime) DesignInteractionQuestions(ctx context.Context, owner, missionID int64, lockedSpecification any, gameType string, materialFileID int64) ([]interaction.Question, error) {
	if r == nil || r.Store == nil || r.Crypto == nil || r.Models == nil {
		return nil, errors.New("INTERACTION_DESIGNER_UNAVAILABLE")
	}
	connection, encrypted, err := r.Store.ResolvePlanningConnection(ctx, owner, missionID)
	if err != nil {
		return nil, errors.New("INTERACTION_DESIGNER_MODEL_UNAVAILABLE")
	}
	apiKey, err := r.Crypto.Decrypt(encrypted)
	if err != nil {
		return nil, errors.New("INTERACTION_DESIGNER_CREDENTIAL_UNAVAILABLE")
	}
	resolved := model.ResolvedConnection{
		ID: connection.ID, OwnerUserID: owner, Provider: connection.Provider,
		Protocol: connection.Protocol, BaseURL: connection.BaseURL, ModelID: connection.ModelID,
		Capabilities: connection.Capabilities, CapabilityVerification: connection.CapabilityVerification,
		APIKey: apiKey,
	}
	raw, err := json.Marshal(lockedSpecification)
	if err != nil {
		return nil, errors.New("INTERACTION_DESIGNER_CONTEXT_INVALID")
	}
	base := fmt.Sprintf("Requested game type: %s\nLocked courseware specification:\n%s", strings.ToUpper(strings.TrimSpace(gameType)), string(raw))
	evidence, err := r.materialResearchPreflight(ctx, missionID, owner, base)
	if err != nil {
		return nil, fmt.Errorf("INTERACTION_DESIGNER_MATERIAL_UNAVAILABLE: %w", err)
	}
	contextText := evidence + fmt.Sprintf("\n\nRequested game type: %s\nThe server will render the game after this proposal. Generate one to three questions only. The source file must remain fileId=%d.", strings.ToUpper(strings.TrimSpace(gameType)), materialFileID)
	content, err := r.runGameSubagentStage(ctx, owner, missionID, resolved, contextText, subagentToolSet("search_materials", "read_material"))
	if err != nil {
		return nil, err
	}
	proposal, err := decodeInteractionProposal(content)
	if err == nil {
		err = validateInteractionProposal(proposal, gameType, materialFileID)
	}
	if err != nil {
		// Providers occasionally return an otherwise useful proposal with one
		// extra field, a wrong option shape, or prose around the JSON. Give the
		// existing interaction designer one bounded contract-repair turn before
		// failing the whole game job. The material evidence and source identity
		// remain server-provided; the repair turn may not invent either.
		repairContext := contextText + "\n\n" + interactionProposalRepairPrompt(gameType, materialFileID, content)
		content, retryErr := r.runGameSubagentStage(ctx, owner, missionID, resolved, repairContext, subagentToolSet("search_materials", "read_material"))
		if retryErr != nil {
			return nil, retryErr
		}
		proposal, retryErr = decodeInteractionProposal(content)
		if retryErr != nil {
			return nil, retryErr
		}
		if retryErr = validateInteractionProposal(proposal, gameType, materialFileID); retryErr != nil {
			return nil, retryErr
		}
	}
	return proposal.Questions, nil
}

func interactionProposalRepairPrompt(gameType string, materialFileID int64, rejected string) string {
	return fmt.Sprintf("Contract repair instruction: your previous interaction proposal was rejected. Return exactly one JSON object and no prose or Markdown fences. Use exactly the top-level keys type and questions; set type to INTERACTION_PROPOSAL. Return one to three questions. Every question must contain id, type, prompt, explanation, and source. The source must preserve fileId=%d and an exact locator from the supplied material evidence. For RUNNER use only TRUE_FALSE or SINGLE_CHOICE; for SINGLE_CHOICE use two to four options and exactly one answer; for TRUE_FALSE use options TRUE and FALSE and exactly one answer. Do not add difficulty, tags, hints, or any other fields. Do not copy a slide point verbatim as the whole prompt. Requested game type: %s. Rejected response:\n%s", materialFileID, strings.ToUpper(strings.TrimSpace(gameType)), truncateRunes(rejected, 8000))
}

func (r *Runtime) runGameSubagentStage(ctx context.Context, owner, missionID int64, connection model.ResolvedConnection, contextText string, tools []model.ToolDefinition) (string, error) {
	chat := []model.ChatMessage{{Role: "system", Content: subagentSystemPrompt(SubagentInteractionDesigner)}, {Role: "user", Content: contextText}}
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
		response, err := r.callModel(ctx, connection, model.ChatRequest{Messages: chat, Tools: tools, MaxTokens: 4096, JSONMode: true})
		status := response.RawStatus
		if providerErr, ok := err.(*model.HTTPError); ok {
			status = providerErr.Status
		}
		audit := model.ModelConnection{ID: connection.ID, OwnerUserID: owner, Provider: connection.Provider, Protocol: connection.Protocol, BaseURL: connection.BaseURL, ModelID: connection.ModelID, Capabilities: connection.Capabilities, CapabilityVerification: connection.CapabilityVerification, CapabilitiesSet: true}
		if auditErr := r.Store.RecordModelAudit(ctx, uuid.New(), owner, missionID, connection.ID, audit, "INTERACTION_DESIGNER", status, time.Since(started)); auditErr != nil {
			return "", fmt.Errorf("record interaction designer audit: %w", auditErr)
		}
		if err != nil {
			return "", err
		}
		if err := r.Store.MarkConnectionUsed(ctx, owner, connection.ID); err != nil {
			return "", fmt.Errorf("mark interaction designer connection used: %w", err)
		}
		if len(response.ToolCalls) > 0 {
			if toolCallBudgetExceeded(toolCallsUsed, len(response.ToolCalls), limit) {
				return "", errors.New("INTERACTION_DESIGNER_TOOL_CALL_LIMIT")
			}
			toolCallsUsed += len(response.ToolCalls)
			results := make([]string, 0, len(response.ToolCalls))
			for _, call := range response.ToolCalls {
				if !allowed[call.Function.Name] {
					results = append(results, `{"error":"INTERACTION_DESIGNER_TOOL_NOT_ALLOWED"}`)
					continue
				}
				results = append(results, r.tool(ctx, missionID, owner, call))
			}
			chat = appendToolCallTurn(chat, response, results)
			continue
		}
		content := strings.TrimSpace(response.Content)
		if content == "" {
			return "", errors.New("INTERACTION_DESIGNER_EMPTY_RESPONSE")
		}
		return content, nil
	}
}

func decodeInteractionProposal(content string) (interactionProposal, error) {
	var raw map[string]json.RawMessage
	if err := decodeSubagentJSON(content, &raw); err != nil || !onlyKeys(raw, "type", "questions") {
		return interactionProposal{}, errors.New("INTERACTION_DESIGNER_INVALID")
	}
	var proposal interactionProposal
	if err := decodeSubagentJSON(content, &proposal); err != nil || strings.ToUpper(strings.TrimSpace(proposal.Type)) != "INTERACTION_PROPOSAL" {
		return interactionProposal{}, errors.New("INTERACTION_DESIGNER_INVALID")
	}
	return proposal, nil
}

func validateInteractionProposal(proposal interactionProposal, gameType string, materialFileID int64) error {
	if len(proposal.Questions) == 0 || len(proposal.Questions) > 3 || materialFileID <= 0 {
		return errors.New("INTERACTION_DESIGNER_INVALID")
	}
	gameType = strings.ToUpper(strings.TrimSpace(gameType))
	for _, question := range proposal.Questions {
		if strings.TrimSpace(question.ID) == "" || strings.TrimSpace(question.Prompt) == "" || strings.TrimSpace(question.Explanation) == "" || question.Source.FileID != materialFileID || strings.TrimSpace(question.Source.Locator) == "" || strings.TrimSpace(question.Source.Claim) == "" {
			return errors.New("INTERACTION_DESIGNER_INVALID")
		}
		questionType := strings.ToUpper(strings.TrimSpace(question.Type))
		if gameType == interaction.GameTypeRunner {
			if questionType != interaction.GameTypeTrueFalse && questionType != interaction.GameTypeSingleChoice {
				return errors.New("INTERACTION_DESIGNER_INVALID")
			}
		} else if questionType != gameType {
			return errors.New("INTERACTION_DESIGNER_INVALID")
		}
		switch questionType {
		case interaction.GameTypeTrueFalse:
			if len(question.Options) != 2 || len(question.Answer) != 1 || question.Options[0].ID != "TRUE" || question.Options[1].ID != "FALSE" || (question.Answer[0] != "TRUE" && question.Answer[0] != "FALSE") {
				return errors.New("INTERACTION_DESIGNER_INVALID")
			}
		case interaction.GameTypeSingleChoice:
			if len(question.Options) < 2 || len(question.Options) > 4 || len(question.Answer) != 1 {
				return errors.New("INTERACTION_DESIGNER_INVALID")
			}
			seen := map[string]bool{}
			answerFound := false
			for _, option := range question.Options {
				if strings.TrimSpace(option.ID) == "" || strings.TrimSpace(option.Text) == "" || seen[option.ID] {
					return errors.New("INTERACTION_DESIGNER_INVALID")
				}
				seen[option.ID] = true
				if option.ID == question.Answer[0] {
					answerFound = true
				}
			}
			if !answerFound {
				return errors.New("INTERACTION_DESIGNER_INVALID")
			}
		case interaction.GameTypeMatching:
			if len(question.Pairs) < 2 || len(question.Pairs) > 4 {
				return errors.New("INTERACTION_DESIGNER_INVALID")
			}
		default:
			return errors.New("INTERACTION_DESIGNER_INVALID")
		}
	}
	return nil
}
