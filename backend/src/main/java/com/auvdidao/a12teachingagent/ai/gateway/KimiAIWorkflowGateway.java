package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.auvdidao.a12teachingagent.ai.config.WorkflowCode;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeSnippet;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.PlanSection;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryData;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RevisionRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RevisionResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.StructuredArtifactDraft;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.StructuredContentRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.StructuredContentResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentResponse;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.ai.kimi.KimiChatClient;
import com.auvdidao.a12teachingagent.ai.kimi.KimiClientException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class KimiAIWorkflowGateway {

    private static final String SYSTEM_PROMPT = """
            You are the structured AI workflow engine for the A12 teaching-agent system.
            Return exactly one valid JSON object. Do not use Markdown, code fences, XML, comments, or reasoning tags.
            Use Simplified Chinese for natural-language fields. Keep identifiers and enum-like artifact types exactly as requested.
            Use only the controlled input. Never invent uploaded materials, student data, approval state, file contents, or retrieval sources.
            Every field shown in the required JSON shape is mandatory. Use empty arrays rather than omitting array fields.
            """;

    private final ObjectMapper objectMapper;
    private final KimiAssistantProperties properties;
    private final KimiChatClient kimiChatClient;

    public KimiAIWorkflowGateway(
            ObjectMapper objectMapper,
            KimiAssistantProperties properties,
            KimiChatClient kimiChatClient
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.kimiChatClient = kimiChatClient;
    }

    public ClarificationResponse clarifyRequirement(ClarificationRequest request) {
        return execute(
                WorkflowCode.CLARIFICATION,
                "clarification",
                request.projectId(),
                request,
                ClarificationResponse.class,
                this::validateClarification
        );
    }

    public RequirementSummaryResponse summarizeRequirement(RequirementSummaryRequest request) {
        return execute(
                WorkflowCode.REQUIREMENT_SUMMARY,
                "requirement-summary",
                request.projectId(),
                request,
                RequirementSummaryResponse.class,
                this::validateRequirementSummary
        );
    }

    public MaterialAnalysisResponse analyzeMaterial(MaterialAnalysisRequest request) {
        return execute(
                WorkflowCode.MATERIAL_ANALYSIS,
                "material-analysis",
                request.projectId(),
                request,
                MaterialAnalysisResponse.class,
                this::validateMaterialAnalysis
        );
    }

    public KnowledgeRetrievalResponse retrieveKnowledge(KnowledgeRetrievalRequest request) {
        if (request.candidateSnippets() == null || request.candidateSnippets().isEmpty()) {
            return new KnowledgeRetrievalResponse(
                    workflowReference(WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT),
                    List.of(),
                    "当前没有可供筛选的本地知识片段，未调用模型补造知识。"
            );
        }
        return execute(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "knowledge-retrieval",
                request.projectId(),
                request,
                KnowledgeRetrievalResponse.class,
                this::validateKnowledgeRetrieval
        );
    }

    public TeachingIntentResponse buildTeachingIntent(TeachingIntentRequest request) {
        return execute(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "teaching-intent",
                request.projectId(),
                request,
                TeachingIntentResponse.class,
                this::validateTeachingIntent
        );
    }

    public GenerationPlanResponse createGenerationPlan(GenerationPlanRequest request) {
        return execute(
                WorkflowCode.GENERATION_PLAN,
                "generation-plan",
                request.projectId(),
                request,
                GenerationPlanResponse.class,
                this::validateGenerationPlan
        );
    }

    public StructuredContentResponse generateStructuredContent(StructuredContentRequest request) {
        return execute(
                WorkflowCode.CONTENT_DRAFT,
                "structured-content",
                request.projectId(),
                request,
                StructuredContentResponse.class,
                this::validateStructuredContent
        );
    }

    public RevisionResponse reviseArtifact(RevisionRequest request) {
        return execute(
                WorkflowCode.REVISION,
                "revision",
                request.projectId(),
                request,
                RevisionResponse.class,
                this::validateRevision
        );
    }

    private <T> T execute(
            WorkflowCode workflowCode,
            String operation,
            Long projectId,
            Object input,
            Class<T> responseType,
            Consumer<T> validator
    ) {
        if (!properties.isWorkflowConfigured()) {
            throw unavailable(workflowCode, "Kimi workflow provider is not configured");
        }

        String inputJson = serializeInput(workflowCode, input);
        if (inputJson.length() > Math.max(1000, properties.getWorkflowMaxInputCharacters())) {
            throw unavailable(workflowCode, "controlled workflow input is too large");
        }

        String userPrompt = """
                Workflow code: %s
                Operation: %s
                Project ID: %s

                Required JSON shape:
                %s

                Controlled input JSON:
                %s
                """.formatted(workflowCode.code(), operation, projectId, responseShape(operation), inputJson);

        String raw;
        try {
            raw = kimiChatClient.complete(
                    List.of(message("system", SYSTEM_PROMPT), message("user", userPrompt)),
                    properties.getWorkflowModel(),
                    properties.getWorkflowMaxCompletionTokens(),
                    properties.getWorkflowTimeoutSeconds()
            );
        } catch (KimiClientException exception) {
            throw unavailable(workflowCode, sanitizeReason(exception.getMessage()));
        }

        JsonNode parsed;
        try {
            parsed = parseModelJson(raw, workflowCode);
        } catch (AiWorkflowUnavailableException firstFailure) {
            parsed = repairJson(workflowCode, operation, raw, firstFailure.getMessage());
        }

        ObjectNode payload = normalizePayload(parsed, workflowCode, operation, projectId);
        try {
            T response = objectMapper.treeToValue(payload, responseType);
            validator.accept(response);
            return response;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw unavailable(workflowCode, "Kimi output does not match the gateway DTO");
        }
    }

    private JsonNode repairJson(
            WorkflowCode workflowCode,
            String operation,
            String invalidOutput,
            String failureReason
    ) {
        String boundedOutput = invalidOutput == null ? "" : invalidOutput;
        if (boundedOutput.length() > 20000) {
            boundedOutput = boundedOutput.substring(0, 20000);
        }
        String repairPrompt = """
                Repair the following model output into one valid JSON object.
                Preserve the intended teaching content, but do not add facts that are absent from the output.
                Failure reason: %s
                Required JSON shape: %s
                Invalid output:
                %s
                """.formatted(failureReason, responseShape(operation), boundedOutput);
        try {
            String repaired = kimiChatClient.complete(
                    List.of(message("system", SYSTEM_PROMPT), message("user", repairPrompt)),
                    properties.getWorkflowModel(),
                    Math.min(properties.getWorkflowMaxCompletionTokens(), 5000),
                    properties.getWorkflowTimeoutSeconds()
            );
            return parseModelJson(repaired, workflowCode);
        } catch (KimiClientException exception) {
            throw unavailable(workflowCode, "Kimi JSON repair failed: " + sanitizeReason(exception.getMessage()));
        }
    }

    private ObjectNode normalizePayload(
            JsonNode parsed,
            WorkflowCode workflowCode,
            String operation,
            Long projectId
    ) {
        JsonNode payload = "clarification".equals(operation)
                ? unwrapClarificationPayload(parsed, workflowCode)
                : parsed;
        if (!"clarification".equals(operation)
                && payload.isObject()
                && payload.path("success").isBoolean()
                && payload.has("data")) {
            require(payload.path("success").asBoolean(), workflowCode, "Kimi output reports failure");
            payload = payload.get("data");
        } else if (!"clarification".equals(operation)
                && payload.isObject()
                && payload.has("data")
                && payload.get("data").isObject()) {
            payload = payload.get("data");
        }
        require(payload != null && payload.isObject(), workflowCode, "Kimi output must be a JSON object");

        ObjectNode object = ((ObjectNode) payload).deepCopy();
        object.put("workflow", workflowReference(workflowCode));
        if ("teaching-intent".equals(operation) && !StringUtils.hasText(object.path("intentId").asText())) {
            object.put("intentId", "intent-kimi-" + projectId);
        }
        if ("generation-plan".equals(operation) && !StringUtils.hasText(object.path("planId").asText())) {
            object.put("planId", "plan-kimi-" + projectId);
        }
        return object;
    }

    /** Keep provider-envelope compatibility local to WF-01. */
    private JsonNode unwrapClarificationPayload(JsonNode parsed, WorkflowCode workflowCode) {
        JsonNode payload = parsed;
        for (int depth = 0; depth < 3; depth++) {
            require(payload != null, workflowCode, "Kimi clarification output is missing");
            if (payload.isTextual()) {
                payload = parseModelJson(payload.asText(), workflowCode);
                continue;
            }
            if (!payload.isObject()) {
                return payload;
            }
            if (payload.path("success").isBoolean() && payload.has("data")) {
                require(payload.path("success").asBoolean(), workflowCode, "Kimi output reports failure");
                payload = payload.get("data");
                continue;
            }
            JsonNode wrapped = firstObjectOrText(payload, "data", "result", "response");
            if (wrapped != null) {
                payload = wrapped;
                continue;
            }
            return payload;
        }
        throw unavailable(workflowCode, "Kimi clarification output has too many wrapper layers");
    }

    private JsonNode firstObjectOrText(JsonNode object, String... names) {
        for (String name : names) {
            JsonNode candidate = object.get(name);
            if (candidate != null && (candidate.isObject() || candidate.isTextual())) {
                return candidate;
            }
        }
        return null;
    }

    private String serializeInput(WorkflowCode workflowCode, Object input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw unavailable(workflowCode, "workflow input could not be serialized");
        }
    }

    private JsonNode parseModelJson(String value, WorkflowCode workflowCode) {
        String normalized = stripCodeFence(stripModelReasoning(value));
        if (!StringUtils.hasText(normalized)) {
            throw unavailable(workflowCode, "Kimi returned empty JSON");
        }
        try {
            JsonNode parsed = objectMapper.readTree(normalized);
            if (parsed == null) {
                throw unavailable(workflowCode, "Kimi returned empty JSON");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw unavailable(workflowCode, "Kimi returned invalid JSON");
        }
    }

    private String responseShape(String operation) {
        return switch (operation) {
            case "clarification" -> """
                    {
                      "missingFields":["fieldCode"],
                      "questions":[{"targetField":"fieldCode","question":"plain question text"}],
                      "suggestedFields":{"fieldCode":"suggested value"},
                      "nextAction":"one non-empty next action string"
                    }
                    Clarification contract: every question is an object whose targetField explicitly identifies the field answered by that question.
                    targetField must be one of: gradeLevel, topic, lessonDuration, teachingGoals, baselineLevel, difficultPoints, stylePreference, interactionType, outputTypes.
                    Do not derive question ownership from missingFields order. Ask no more than five concise questions.
                    suggestedFields is an object whose keys and values are strings, never an array.
                    Use exactly these camelCase keys. Do not return workflow, success, data, result, response, or snake_case aliases.
                    """;
            case "requirement-summary" -> """
                    {"summary":{"courseName":"","chapterTopic":"","targetAudience":"","lessonDurationMinutes":40,"teachingGoals":[],"keyDifficulties":[],"outputTypes":[],"coursewareStyle":"","interactionType":""},"assumptions":[],"confirmationQuestion":""}
                    """;
            case "material-analysis" -> """
                    {"status":"PARSED","summary":"","keywords":[],"teachingUses":[],"suggestedChunks":[]}
                    """;
            case "knowledge-retrieval" -> """
                    {"snippets":[{"title":"","sourceName":"","content":"","score":0.0}],"retrievalNote":""}
                    Select only from candidateSnippets. Keep sourceName unchanged. Do not create new sources.
                    """;
            case "teaching-intent" -> """
                    {"intentId":"","generationGoals":[],"contentBasis":[],"interactionIdeas":[],"outputTypes":[],"confirmationPrompt":""}
                    """;
            case "generation-plan" -> """
                    {"planId":"","pptOutline":[{"title":"","points":[],"materialReference":""}],"docOutline":[{"title":"","points":[],"materialReference":""}],"interactionPlan":[],"estimatedDuration":"","nextAction":""}
                    """;
            case "structured-content" -> """
                    {
                      "pptContent":{"artifactType":"PPT","title":"","contentJson":{"deckTitle":"","theme":"","slides":[{"index":1,"kind":"CONTENT","title":"","layout":"TITLE_AND_CONTENT","points":[],"speakerNotes":""}]},"assetSuggestions":[]},
                      "docContent":{"artifactType":"DOCX","title":"","contentJson":{"title":"","courseInfo":{"projectName":"","courseName":"","chapterTopic":"","targetAudience":"","lessonDurationMinutes":40,"generationMode":"STANDARD"},"teachingGoals":[],"keyPoints":[],"difficultPoints":[],"methods":[],"teachingProcess":[{"stage":"","durationMinutes":5,"content":"","teacherActivity":"","studentActivity":""}],"classroomActivities":[],"homework":[],"resourceNotes":[],"sections":[{"order":1,"title":"","paragraphs":[]}]},"assetSuggestions":[]},
                      "interactionContent":{"artifactType":"INTERACTION","title":"","contentJson":{"title":"","instructions":"","questions":[{"id":"q1","question":"","options":["",""],"correctOption":0,"correctAnswer":"","explanation":""}]},"assetSuggestions":[]},
                      "fallbackToBackendDrafts":false
                    }
                    """;
            case "revision" -> """
                    {"changeSummary":"","changedSections":[],"revisedContent":"","versionSuggestion":""}
                    """;
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    private void validateClarification(ClarificationResponse response) {
        WorkflowCode code = WorkflowCode.CLARIFICATION;
        require(response != null, code, "clarification output is missing");
        require(response.missingFields() != null, code, "missingFields is missing");
        require(response.questions() != null, code, "questions is missing");
        for (var question : response.questions()) {
            require(question != null, code, "clarification question is missing");
            require(StringUtils.hasText(question.targetField()), code, "question targetField is missing");
            require(StringUtils.hasText(question.question()), code, "question text is missing");
            require(List.of(
                    "gradeLevel",
                    "topic",
                    "lessonDuration",
                    "teachingGoals",
                    "baselineLevel",
                    "difficultPoints",
                    "stylePreference",
                    "interactionType",
                    "outputTypes"
            ).contains(question.targetField()), code, "question targetField is invalid");
        }
        require(response.suggestedFields() != null, code, "suggestedFields is missing");
        require(StringUtils.hasText(response.nextAction()), code, "nextAction is missing");
    }

    private void validateRequirementSummary(RequirementSummaryResponse response) {
        WorkflowCode code = WorkflowCode.REQUIREMENT_SUMMARY;
        require(response != null && response.summary() != null, code, "summary is missing");
        RequirementSummaryData summary = response.summary();
        require(StringUtils.hasText(summary.courseName()), code, "summary.courseName is missing");
        require(StringUtils.hasText(summary.chapterTopic()), code, "summary.chapterTopic is missing");
        require(StringUtils.hasText(summary.targetAudience()), code, "summary.targetAudience is missing");
        require(summary.lessonDurationMinutes() != null && summary.lessonDurationMinutes() > 0,
                code, "summary.lessonDurationMinutes is missing");
        require(summary.teachingGoals() != null, code, "summary.teachingGoals is missing");
        require(summary.keyDifficulties() != null, code, "summary.keyDifficulties is missing");
        require(summary.outputTypes() != null, code, "summary.outputTypes is missing");
        require(StringUtils.hasText(summary.coursewareStyle()), code, "summary.coursewareStyle is missing");
        require(StringUtils.hasText(summary.interactionType()), code, "summary.interactionType is missing");
        require(response.assumptions() != null, code, "assumptions is missing");
        require(StringUtils.hasText(response.confirmationQuestion()), code, "confirmationQuestion is missing");
    }

    private void validateMaterialAnalysis(MaterialAnalysisResponse response) {
        WorkflowCode code = WorkflowCode.MATERIAL_ANALYSIS;
        require(response != null, code, "material analysis output is missing");
        require(StringUtils.hasText(response.status()), code, "status is missing");
        require(StringUtils.hasText(response.summary()), code, "summary is missing");
        require(response.keywords() != null, code, "keywords is missing");
        require(response.teachingUses() != null, code, "teachingUses is missing");
        require(response.suggestedChunks() != null, code, "suggestedChunks is missing");
    }

    private void validateKnowledgeRetrieval(KnowledgeRetrievalResponse response) {
        WorkflowCode code = WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT;
        require(response != null && response.snippets() != null, code, "snippets are missing");
        for (KnowledgeSnippet snippet : response.snippets()) {
            require(snippet != null, code, "a knowledge snippet is missing");
            require(StringUtils.hasText(snippet.title()), code, "knowledge snippet title is missing");
            require(StringUtils.hasText(snippet.sourceName()), code, "knowledge snippet source is missing");
            require(StringUtils.hasText(snippet.content()), code, "knowledge snippet content is missing");
            require(Double.isFinite(snippet.score()), code, "knowledge snippet score is invalid");
        }
        require(StringUtils.hasText(response.retrievalNote()), code, "retrievalNote is missing");
    }

    private void validateTeachingIntent(TeachingIntentResponse response) {
        WorkflowCode code = WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT;
        require(response != null, code, "teaching intent output is missing");
        require(StringUtils.hasText(response.intentId()), code, "intentId is missing");
        require(response.generationGoals() != null, code, "generationGoals is missing");
        require(response.contentBasis() != null, code, "contentBasis is missing");
        require(response.interactionIdeas() != null, code, "interactionIdeas is missing");
        require(response.outputTypes() != null, code, "outputTypes is missing");
        require(StringUtils.hasText(response.confirmationPrompt()), code, "confirmationPrompt is missing");
    }

    private void validateGenerationPlan(GenerationPlanResponse response) {
        WorkflowCode code = WorkflowCode.GENERATION_PLAN;
        require(response != null, code, "generation plan output is missing");
        require(StringUtils.hasText(response.planId()), code, "planId is missing");
        validatePlanSections(response.pptOutline(), "pptOutline", code);
        validatePlanSections(response.docOutline(), "docOutline", code);
        require(response.interactionPlan() != null, code, "interactionPlan is missing");
        require(StringUtils.hasText(response.estimatedDuration()), code, "estimatedDuration is missing");
        require(StringUtils.hasText(response.nextAction()), code, "nextAction is missing");
    }

    private void validatePlanSections(List<PlanSection> sections, String field, WorkflowCode code) {
        require(sections != null, code, field + " is missing");
        for (PlanSection section : sections) {
            require(section != null, code, field + " contains a missing section");
            require(StringUtils.hasText(section.title()), code, field + " section title is missing");
            require(section.points() != null, code, field + " section points are missing");
            require(StringUtils.hasText(section.materialReference()), code, field + " materialReference is missing");
        }
    }

    private void validateRevision(RevisionResponse response) {
        WorkflowCode code = WorkflowCode.REVISION;
        require(response != null, code, "revision output is missing");
        require(StringUtils.hasText(response.changeSummary()), code, "changeSummary is missing");
        require(response.changedSections() != null, code, "changedSections is missing");
        require(StringUtils.hasText(response.revisedContent()), code, "revisedContent is missing");
        require(StringUtils.hasText(response.versionSuggestion()), code, "versionSuggestion is missing");
    }

    private void validateStructuredContent(StructuredContentResponse response) {
        WorkflowCode code = WorkflowCode.CONTENT_DRAFT;
        require(response != null, code, "structured content output is missing");
        require(!response.fallbackToBackendDrafts(), code, "model requested backend fallback drafts");
        validateStructuredDraft(response.pptContent(), "PPT", "slides", code);
        validateStructuredDraft(response.docContent(), "DOCX", "sections", code);
        validateStructuredDraft(response.interactionContent(), "INTERACTION", "questions", code);
    }

    private void validateStructuredDraft(
            StructuredArtifactDraft draft,
            String expectedType,
            String requiredArray,
            WorkflowCode code
    ) {
        require(draft != null, code, expectedType + " draft is missing");
        require(expectedType.equalsIgnoreCase(draft.artifactType()), code, expectedType + " artifactType is invalid");
        require(StringUtils.hasText(draft.title()), code, expectedType + " title is missing");
        require(draft.contentJson() != null && draft.contentJson().isObject(), code, expectedType + " contentJson is invalid");
        require(draft.contentJson().path(requiredArray).isArray() && !draft.contentJson().path(requiredArray).isEmpty(),
                code, expectedType + " contentJson." + requiredArray + " is missing");
        require(draft.assetSuggestions() != null, code, expectedType + " assetSuggestions is missing");
        switch (expectedType) {
            case "PPT" -> validatePptContent(draft.contentJson(), code);
            case "DOCX" -> validateDocContent(draft.contentJson(), code);
            case "INTERACTION" -> validateInteractionContent(draft.contentJson(), code);
            default -> throw unavailable(code, "unsupported structured artifact type");
        }
    }

    private void validatePptContent(JsonNode content, WorkflowCode code) {
        requireText(content, "deckTitle", "PPT", code);
        requireText(content, "theme", "PPT", code);
        for (JsonNode slide : content.path("slides")) {
            require(slide.isObject(), code, "PPT slide is invalid");
            require(slide.path("index").canConvertToInt() && slide.path("index").asInt() > 0,
                    code, "PPT slide index is invalid");
            requireText(slide, "kind", "PPT slide", code);
            requireText(slide, "title", "PPT slide", code);
            requireText(slide, "layout", "PPT slide", code);
            require(slide.path("points").isArray(), code, "PPT slide points are missing");
            requireText(slide, "speakerNotes", "PPT slide", code);
        }
    }

    private void validateDocContent(JsonNode content, WorkflowCode code) {
        requireText(content, "title", "DOCX", code);
        JsonNode courseInfo = content.path("courseInfo");
        require(courseInfo.isObject(), code, "DOCX courseInfo is missing");
        for (String field : List.of("projectName", "courseName", "chapterTopic", "targetAudience", "generationMode")) {
            requireText(courseInfo, field, "DOCX courseInfo", code);
        }
        require(courseInfo.path("lessonDurationMinutes").canConvertToInt()
                        && courseInfo.path("lessonDurationMinutes").asInt() > 0,
                code, "DOCX courseInfo lessonDurationMinutes is invalid");
        for (String field : List.of(
                "teachingGoals", "keyPoints", "difficultPoints", "methods",
                "classroomActivities", "homework", "resourceNotes"
        )) {
            require(content.path(field).isArray(), code, "DOCX " + field + " is missing");
        }
        JsonNode process = content.path("teachingProcess");
        require(process.isArray() && !process.isEmpty(), code, "DOCX teachingProcess is missing");
        for (JsonNode step : process) {
            require(step.isObject(), code, "DOCX teachingProcess step is invalid");
            requireText(step, "stage", "DOCX teachingProcess step", code);
            require(step.path("durationMinutes").canConvertToInt() && step.path("durationMinutes").asInt() > 0,
                    code, "DOCX teachingProcess durationMinutes is invalid");
            requireText(step, "content", "DOCX teachingProcess step", code);
            requireText(step, "teacherActivity", "DOCX teachingProcess step", code);
            requireText(step, "studentActivity", "DOCX teachingProcess step", code);
        }
        for (JsonNode section : content.path("sections")) {
            require(section.isObject(), code, "DOCX section is invalid");
            require(section.path("order").canConvertToInt() && section.path("order").asInt() > 0,
                    code, "DOCX section order is invalid");
            requireText(section, "title", "DOCX section", code);
            require(section.path("paragraphs").isArray(), code, "DOCX section paragraphs are missing");
        }
    }

    private void validateInteractionContent(JsonNode content, WorkflowCode code) {
        requireText(content, "title", "INTERACTION", code);
        requireText(content, "instructions", "INTERACTION", code);
        for (JsonNode question : content.path("questions")) {
            require(question.isObject(), code, "INTERACTION question is invalid");
            requireText(question, "id", "INTERACTION question", code);
            requireText(question, "question", "INTERACTION question", code);
            JsonNode options = question.path("options");
            require(options.isArray() && !options.isEmpty(), code, "INTERACTION options are missing");
            require(question.path("correctOption").canConvertToInt()
                            && question.path("correctOption").asInt() >= 0
                            && question.path("correctOption").asInt() < options.size(),
                    code, "INTERACTION correctOption is invalid");
            requireText(question, "correctAnswer", "INTERACTION question", code);
            requireText(question, "explanation", "INTERACTION question", code);
        }
    }

    private void requireText(JsonNode content, String field, String label, WorkflowCode code) {
        require(content.path(field).isTextual() && StringUtils.hasText(content.path(field).asText()),
                code, label + " " + field + " is missing");
    }

    private static String stripCodeFence(String value) {
        String stripped = value == null ? "" : value.strip();
        if (!stripped.startsWith("```")) {
            return stripped;
        }
        int firstLineBreak = stripped.indexOf('\n');
        int closingFence = stripped.lastIndexOf("```");
        if (firstLineBreak < 0 || closingFence <= firstLineBreak) {
            return stripped;
        }
        return stripped.substring(firstLineBreak + 1, closingFence).strip();
    }

    private static String stripModelReasoning(String value) {
        String stripped = value == null ? "" : value.strip();
        int closingThinkTag = stripped.lastIndexOf("</think>");
        if (closingThinkTag < 0) {
            return stripped;
        }
        return stripped.substring(closingThinkTag + "</think>".length()).strip();
    }

    private String workflowReference(WorkflowCode workflowCode) {
        return "kimi:" + properties.getWorkflowModel() + ":" + workflowCode.code();
    }

    private static Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private static String sanitizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "provider request failed";
        }
        String sanitized = reason.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        return sanitized.length() <= 200 ? sanitized : sanitized.substring(0, 200);
    }

    private static void require(boolean condition, WorkflowCode workflowCode, String reason) {
        if (!condition) {
            throw unavailable(workflowCode, reason);
        }
    }

    private static AiWorkflowUnavailableException unavailable(WorkflowCode workflowCode, String reason) {
        return new AiWorkflowUnavailableException(workflowCode.code() + ": " + reason + ".");
    }
}
