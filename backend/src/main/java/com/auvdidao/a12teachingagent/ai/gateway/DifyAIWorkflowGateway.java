package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.AiWorkflowProperties;
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
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentResponse;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class DifyAIWorkflowGateway {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_USER_PREFIX = "a12-project-";

    private final AiWorkflowProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DifyAIWorkflowGateway(AiWorkflowProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = buildRestClient(properties);
    }

    public ClarificationResponse clarifyRequirement(ClarificationRequest request) {
        return execute(
                WorkflowCode.CLARIFICATION,
                "clarification",
                request.projectId(),
                request,
                ClarificationResponse.class,
                response -> validateClarification(response, WorkflowCode.CLARIFICATION)
        );
    }

    public RequirementSummaryResponse summarizeRequirement(RequirementSummaryRequest request) {
        return execute(
                WorkflowCode.REQUIREMENT_SUMMARY,
                "requirement-summary",
                request.projectId(),
                request,
                RequirementSummaryResponse.class,
                response -> validateRequirementSummary(response, WorkflowCode.REQUIREMENT_SUMMARY)
        );
    }

    public MaterialAnalysisResponse analyzeMaterial(MaterialAnalysisRequest request) {
        return execute(
                WorkflowCode.MATERIAL_ANALYSIS,
                "material-analysis",
                request.projectId(),
                request,
                MaterialAnalysisResponse.class,
                response -> validateMaterialAnalysis(response, WorkflowCode.MATERIAL_ANALYSIS)
        );
    }

    public KnowledgeRetrievalResponse retrieveKnowledge(KnowledgeRetrievalRequest request) {
        return execute(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "knowledge-retrieval",
                request.projectId(),
                request,
                KnowledgeRetrievalResponse.class,
                response -> validateKnowledgeRetrieval(response, WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT)
        );
    }

    public TeachingIntentResponse buildTeachingIntent(TeachingIntentRequest request) {
        return execute(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "teaching-intent",
                request.projectId(),
                request,
                TeachingIntentResponse.class,
                response -> validateTeachingIntent(response, WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT)
        );
    }

    public GenerationPlanResponse createGenerationPlan(GenerationPlanRequest request) {
        return execute(
                WorkflowCode.GENERATION_PLAN,
                "generation-plan",
                request.projectId(),
                request,
                GenerationPlanResponse.class,
                response -> validateGenerationPlan(response, WorkflowCode.GENERATION_PLAN)
        );
    }

    public RevisionResponse reviseArtifact(RevisionRequest request) {
        return execute(
                WorkflowCode.REVISION,
                "revision",
                request.projectId(),
                request,
                RevisionResponse.class,
                response -> validateRevision(response, WorkflowCode.REVISION)
        );
    }

    private <T> T execute(
            WorkflowCode workflowCode,
            String operation,
            Long projectId,
            Object payload,
            Class<T> responseType,
            Consumer<T> validator
    ) {
        String configurationIssue = properties.difyConfigurationIssue(workflowCode);
        if (configurationIssue != null) {
            throw unavailable(workflowCode, configurationIssue);
        }

        AiWorkflowProperties.Dify dify = properties.getDify();
        String expectedWorkflowId = dify.resolveWorkflowId(workflowCode);
        String apiKey = dify.resolveApiKey(workflowCode);
        String requestJson = serializeRequestEnvelope(workflowCode, operation, projectId, payload);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("request_json", requestJson);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inputs);
        body.put("user", userId(dify.getUserPrefix(), projectId));
        body.put("response_mode", "blocking");

        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(workflowUri(dify.getBaseUrl(), workflowCode))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            throw unavailable(
                    workflowCode,
                    "Dify returned HTTP " + exception.getStatusCode().value()
            );
        } catch (RestClientException exception) {
            throw unavailable(workflowCode, "Dify is unavailable or timed out");
        } catch (IllegalArgumentException exception) {
            throw unavailable(workflowCode, "Dify endpoint configuration is invalid");
        }

        T response = mapResponse(responseBody, workflowCode, expectedWorkflowId, responseType);
        validator.accept(response);
        return response;
    }

    private <T> T mapResponse(
            String responseBody,
            WorkflowCode workflowCode,
            String expectedWorkflowId,
            Class<T> responseType
    ) {
        JsonNode root = parseJson(responseBody, workflowCode);
        JsonNode data = root.get("data");
        require(data != null && data.isObject(), workflowCode, "Dify response data is missing");
        require(
                "succeeded".equalsIgnoreCase(data.path("status").asText()),
                workflowCode,
                "Dify run did not succeed"
        );

        JsonNode returnedWorkflowId = data.get("workflow_id");
        if (StringUtils.hasText(expectedWorkflowId)
                && returnedWorkflowId != null
                && returnedWorkflowId.isTextual()) {
            require(
                    expectedWorkflowId.equals(returnedWorkflowId.asText()),
                    workflowCode,
                    "Dify response workflow ID does not match the configured expected ID"
            );
        }

        JsonNode outputs = data.get("outputs");
        require(outputs != null && outputs.isObject(), workflowCode, "Dify outputs are missing");
        JsonNode result = firstResult(outputs);
        require(result != null && !result.isNull(), workflowCode, "Dify business output is missing");

        JsonNode payload = parseResult(result, workflowCode);
        if (payload.has("workflowCode")) {
            require(
                    workflowCode.code().equals(payload.path("workflowCode").asText()),
                    workflowCode,
                    "Dify business output belongs to another workflow"
            );
        }
        if (payload.has("success")) {
            require(
                    payload.path("success").isBoolean() && payload.path("success").asBoolean(),
                    workflowCode,
                    "Dify business output reports failure"
            );
            payload = parseResult(payload.get("data"), workflowCode);
        }

        require(payload.isObject(), workflowCode, "Dify business output must be a JSON object");
        ObjectNode responseNode = ((ObjectNode) payload).deepCopy();
        String returnedId = returnedWorkflowId != null && returnedWorkflowId.isTextual()
                ? returnedWorkflowId.asText()
                : null;
        String workflowReference = StringUtils.hasText(returnedId)
                ? returnedId
                : StringUtils.hasText(expectedWorkflowId) ? expectedWorkflowId : workflowCode.code();
        responseNode.put("workflow", workflowReference);
        try {
            return objectMapper.treeToValue(responseNode, responseType);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw unavailable(workflowCode, "Dify business output does not match the gateway DTO");
        }
    }

    private JsonNode firstResult(JsonNode outputs) {
        for (String field : List.of("result", "result_json", "data")) {
            JsonNode value = outputs.get(field);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private JsonNode parseResult(JsonNode result, WorkflowCode workflowCode) {
        require(result != null && !result.isNull(), workflowCode, "Dify business output is missing");
        if (!result.isTextual()) {
            return result;
        }
        return parseJson(stripCodeFence(result.asText()), workflowCode);
    }

    private JsonNode parseJson(String value, WorkflowCode workflowCode) {
        if (!StringUtils.hasText(value)) {
            throw unavailable(workflowCode, "Dify returned empty JSON");
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (parsed == null) {
                throw unavailable(workflowCode, "Dify returned empty JSON");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw unavailable(workflowCode, "Dify returned invalid JSON");
        }
    }

    private String serializeRequestEnvelope(
            WorkflowCode workflowCode,
            String operation,
            Long projectId,
            Object payload
    ) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("workflowCode", workflowCode.code());
            envelope.put("traceHint", "a12-" + workflowCode.code() + "-project-" + projectId);
            envelope.put("operation", operation);
            envelope.set("input", objectMapper.valueToTree(payload));
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw unavailable(workflowCode, "Gateway request payload could not be serialized");
        }
    }

    private URI workflowUri(String baseUrl, WorkflowCode workflowCode) {
        if (!StringUtils.hasText(baseUrl)) {
            throw unavailable(workflowCode, "Dify base URL is missing");
        }
        String normalizedBaseUrl = baseUrl.strip();
        while (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }
        try {
            URI uri = UriComponentsBuilder.fromUriString(normalizedBaseUrl)
                    .pathSegment("workflows", "run")
                    .build()
                    .encode()
                    .toUri();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("Unsupported Dify URI scheme");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw unavailable(workflowCode, "Dify endpoint configuration is invalid");
        }
    }

    private String userId(String configuredPrefix, Long projectId) {
        String prefix = StringUtils.hasText(configuredPrefix) ? configuredPrefix.strip() : DEFAULT_USER_PREFIX;
        return prefix + projectId;
    }

    private static RestClient buildRestClient(AiWorkflowProperties properties) {
        AiWorkflowProperties.Dify dify = properties.getDify();
        Duration connectTimeout = positiveDuration(
                dify == null ? null : dify.getConnectTimeout(),
                DEFAULT_CONNECT_TIMEOUT
        );
        Duration readTimeout = positiveDuration(
                dify == null ? null : dify.getReadTimeout(),
                DEFAULT_READ_TIMEOUT
        );
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build()
        );
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private static Duration positiveDuration(Duration configured, Duration fallback) {
        return configured == null || configured.isZero() || configured.isNegative() ? fallback : configured;
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

    private static void validateClarification(ClarificationResponse response, WorkflowCode workflowCode) {
        require(response != null, workflowCode, "clarification output is missing");
        require(response.missingFields() != null, workflowCode, "missingFields is missing");
        require(response.questions() != null, workflowCode, "questions is missing");
        require(response.suggestedFields() != null, workflowCode, "suggestedFields is missing");
        require(StringUtils.hasText(response.nextAction()), workflowCode, "nextAction is missing");
    }

    private static void validateRequirementSummary(
            RequirementSummaryResponse response,
            WorkflowCode workflowCode
    ) {
        require(response != null && response.summary() != null, workflowCode, "summary is missing");
        RequirementSummaryData summary = response.summary();
        require(StringUtils.hasText(summary.courseName()), workflowCode, "summary.courseName is missing");
        require(StringUtils.hasText(summary.chapterTopic()), workflowCode, "summary.chapterTopic is missing");
        require(StringUtils.hasText(summary.targetAudience()), workflowCode, "summary.targetAudience is missing");
        require(
                summary.lessonDurationMinutes() != null && summary.lessonDurationMinutes() > 0,
                workflowCode,
                "summary.lessonDurationMinutes is missing"
        );
        require(summary.teachingGoals() != null, workflowCode, "summary.teachingGoals is missing");
        require(summary.keyDifficulties() != null, workflowCode, "summary.keyDifficulties is missing");
        require(summary.outputTypes() != null, workflowCode, "summary.outputTypes is missing");
        require(StringUtils.hasText(summary.coursewareStyle()), workflowCode, "summary.coursewareStyle is missing");
        require(StringUtils.hasText(summary.interactionType()), workflowCode, "summary.interactionType is missing");
        require(response.assumptions() != null, workflowCode, "assumptions is missing");
        require(
                StringUtils.hasText(response.confirmationQuestion()),
                workflowCode,
                "confirmationQuestion is missing"
        );
    }

    private static void validateMaterialAnalysis(MaterialAnalysisResponse response, WorkflowCode workflowCode) {
        require(response != null, workflowCode, "material analysis output is missing");
        require(StringUtils.hasText(response.status()), workflowCode, "status is missing");
        require(StringUtils.hasText(response.summary()), workflowCode, "summary is missing");
        require(response.keywords() != null, workflowCode, "keywords is missing");
        require(response.teachingUses() != null, workflowCode, "teachingUses is missing");
        require(response.suggestedChunks() != null, workflowCode, "suggestedChunks is missing");
    }

    private static void validateKnowledgeRetrieval(
            KnowledgeRetrievalResponse response,
            WorkflowCode workflowCode
    ) {
        require(response != null && response.snippets() != null, workflowCode, "snippets are missing");
        for (KnowledgeSnippet snippet : response.snippets()) {
            require(snippet != null, workflowCode, "a knowledge snippet is missing");
            require(StringUtils.hasText(snippet.title()), workflowCode, "knowledge snippet title is missing");
            require(StringUtils.hasText(snippet.sourceName()), workflowCode, "knowledge snippet source is missing");
            require(StringUtils.hasText(snippet.content()), workflowCode, "knowledge snippet content is missing");
            require(Double.isFinite(snippet.score()), workflowCode, "knowledge snippet score is invalid");
        }
        require(StringUtils.hasText(response.retrievalNote()), workflowCode, "retrievalNote is missing");
    }

    private static void validateTeachingIntent(TeachingIntentResponse response, WorkflowCode workflowCode) {
        require(response != null, workflowCode, "teaching intent output is missing");
        require(StringUtils.hasText(response.intentId()), workflowCode, "intentId is missing");
        require(response.generationGoals() != null, workflowCode, "generationGoals is missing");
        require(response.contentBasis() != null, workflowCode, "contentBasis is missing");
        require(response.interactionIdeas() != null, workflowCode, "interactionIdeas is missing");
        require(response.outputTypes() != null, workflowCode, "outputTypes is missing");
        require(StringUtils.hasText(response.confirmationPrompt()), workflowCode, "confirmationPrompt is missing");
    }

    private static void validateGenerationPlan(GenerationPlanResponse response, WorkflowCode workflowCode) {
        require(response != null, workflowCode, "generation plan output is missing");
        require(StringUtils.hasText(response.planId()), workflowCode, "planId is missing");
        validatePlanSections(response.pptOutline(), "pptOutline", workflowCode);
        validatePlanSections(response.docOutline(), "docOutline", workflowCode);
        require(response.interactionPlan() != null, workflowCode, "interactionPlan is missing");
        require(StringUtils.hasText(response.estimatedDuration()), workflowCode, "estimatedDuration is missing");
        require(StringUtils.hasText(response.nextAction()), workflowCode, "nextAction is missing");
    }

    private static void validatePlanSections(
            List<PlanSection> sections,
            String field,
            WorkflowCode workflowCode
    ) {
        require(sections != null, workflowCode, field + " is missing");
        for (PlanSection section : sections) {
            require(section != null, workflowCode, field + " contains a missing section");
            require(StringUtils.hasText(section.title()), workflowCode, field + " section title is missing");
            require(section.points() != null, workflowCode, field + " section points are missing");
            require(
                    StringUtils.hasText(section.materialReference()),
                    workflowCode,
                    field + " materialReference is missing"
            );
        }
    }

    private static void validateRevision(RevisionResponse response, WorkflowCode workflowCode) {
        require(response != null, workflowCode, "revision output is missing");
        require(StringUtils.hasText(response.changeSummary()), workflowCode, "changeSummary is missing");
        require(response.changedSections() != null, workflowCode, "changedSections is missing");
        require(StringUtils.hasText(response.revisedContent()), workflowCode, "revisedContent is missing");
        require(StringUtils.hasText(response.versionSuggestion()), workflowCode, "versionSuggestion is missing");
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
