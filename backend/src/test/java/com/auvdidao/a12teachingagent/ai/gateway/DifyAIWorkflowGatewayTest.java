package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.AiWorkflowProperties;
import com.auvdidao.a12teachingagent.ai.config.AiWorkflowProperties.Workflow;
import com.auvdidao.a12teachingagent.ai.config.WorkflowCode;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeSnippet;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryData;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RevisionRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentRequest;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DifyAIWorkflowGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private HttpServer server;
    private AiWorkflowProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.get().handle(exchange));
        server.start();

        properties = new AiWorkflowProperties();
        properties.getDify().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.getDify().setUserPrefix("teacher-project-");
        properties.getDify().setConnectTimeout(Duration.ofSeconds(1));
        properties.getDify().setReadTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void clarificationUsesPublishedWorkflowEndpointPerWorkflowKeyAndContractEnvelope() {
        configure(WorkflowCode.CLARIFICATION, "published-wf-01-v3", "wf-01-key");
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        handler.set(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(readJson(exchange));
            ObjectNode business = objectNode("""
                    {
                      "workflowCode": "WF-01",
                      "success": true,
                      "data": {
                        "recognizedFields": {"courseName": "Mathematics"},
                        "missingFields": ["targetAudience"],
                        "questions": [{
                          "questionId": "q-target-audience",
                          "field": "targetAudience",
                          "questionText": "Which audience?"
                        }],
                        "canContinue": false
                      },
                      "warnings": [],
                      "errors": [],
                      "confidence": 0.94,
                      "traceHint": "a12-WF-01-project-78"
                    }
                    """);
            sendJson(exchange, 200, successWithTextResult(
                    "published-wf-01-v3",
                    "```json\n" + business + "\n```"
            ));
        });

        var response = gateway().clarifyRequirement(new ClarificationRequest(
                78L,
                "Create a fraction lesson",
                List.of("courseName"),
                GenerationMode.STANDARD
        ));

        assertThat(path.get()).isEqualTo("/v1/workflows/run");
        assertThat(authorization.get()).isEqualTo("Bearer wf-01-key");
        assertThat(requestBody.get().path("response_mode").asText()).isEqualTo("blocking");
        assertThat(requestBody.get().path("user").asText()).isEqualTo("teacher-project-78");
        JsonNode inputs = requestBody.get().path("inputs");
        assertThat(inputs.size()).isEqualTo(1);
        JsonNode envelope = objectNode(inputs.path("request_json").asText());
        assertThat(envelope.path("workflowCode").asText()).isEqualTo("WF-01");
        assertThat(envelope.path("traceHint").asText()).isEqualTo("a12-WF-01-project-78");
        assertThat(envelope.path("operation").asText()).isEqualTo("clarification");
        assertThat(envelope.path("input").path("rawRequirement").asText())
                .isEqualTo("Create a fraction lesson");
        assertThat(response.workflow()).isEqualTo("published-wf-01-v3");
        assertThat(response.missingFields()).containsExactly("targetAudience");
        assertThat(response.questions()).containsExactly("Which audience?");
        assertThat(response.suggestedFields()).containsEntry("courseName", "Mathematics");
        assertThat(response.nextAction()).isEqualTo("ANSWER_CLARIFICATION_QUESTIONS");
    }

    @Test
    void mapsEveryRemainingGatewayMethodWithIndependentApplicationKeys() {
        configure(WorkflowCode.REQUIREMENT_SUMMARY, "published-wf-02", "wf-02-key");
        configure(WorkflowCode.MATERIAL_ANALYSIS, "published-wf-03", "wf-03-key");
        configure(WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT, "published-wf-04", "wf-04-key");
        configure(WorkflowCode.GENERATION_PLAN, "published-wf-05", "wf-05-key");
        configure(WorkflowCode.REVISION, "published-wf-07", "wf-07-key");
        Set<String> operations = ConcurrentHashMap.newKeySet();
        Set<String> paths = ConcurrentHashMap.newKeySet();
        List<String> authorizations = new ArrayList<>();
        Map<String, JsonNode> requestEnvelopes = new ConcurrentHashMap<>();
        handler.set(exchange -> {
            JsonNode request = readJson(exchange);
            JsonNode envelope = objectNode(request.path("inputs").path("request_json").asText());
            String operation = envelope.path("operation").asText();
            String workflowId = workflowIdForOperation(operation);
            operations.add(operation);
            paths.add(exchange.getRequestURI().getPath());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requestEnvelopes.put(operation, envelope);
            ObjectNode output = outputFor(operation);
            if ("material-analysis".equals(operation)) {
                sendJson(exchange, 200, successWithObjectOutput(workflowId, "data", output));
            } else {
                sendJson(exchange, 200, successWithObjectOutput(workflowId, "result", output));
            }
        });

        DifyAIWorkflowGateway gateway = gateway();
        var summary = gateway.summarizeRequirement(new RequirementSummaryRequest(
                78L,
                "Create a fraction lesson",
                List.of(),
                GenerationMode.STANDARD
        ));
        var material = gateway.analyzeMaterial(new MaterialAnalysisRequest(
                78L,
                "fractions.pdf",
                "PDF",
                "lesson evidence",
                "A fraction represents part of a whole.",
                List.of("TEXTBOOK_BASIS"),
                requirementSummaryData()
        ));
        var knowledge = gateway.retrieveKnowledge(new KnowledgeRetrievalRequest(
                78L,
                "Mathematics",
                "Fractions",
                List.of("fraction"),
                List.of(new KnowledgeSnippet(
                        "Fraction definition",
                        "fractions.pdf",
                        "A fraction represents part of a whole.",
                        0.95
                ))
        ));
        var intent = gateway.buildTeachingIntent(new TeachingIntentRequest(
                78L,
                requirementSummaryData(),
                knowledge.snippets()
        ));
        var plan = gateway.createGenerationPlan(new GenerationPlanRequest(
                78L,
                "Mathematics",
                "Fractions",
                "Grade 5",
                List.of("PPT", "DOCX", "INTERACTION"),
                GenerationMode.STANDARD
        ));
        var revision = gateway.reviseArtifact(new RevisionRequest(
                78L,
                9L,
                "Add one example",
                "Current content"
        ));

        assertThat(summary.workflow()).isEqualTo("published-wf-02");
        assertThat(material.workflow()).isEqualTo("published-wf-03");
        assertThat(knowledge.workflow()).isEqualTo("published-wf-04");
        assertThat(intent.workflow()).isEqualTo("published-wf-04");
        assertThat(plan.workflow()).isEqualTo("published-wf-05");
        assertThat(revision.workflow()).isEqualTo("published-wf-07");
        assertThat(operations).containsExactlyInAnyOrder(
                "requirement-summary",
                "material-analysis",
                "knowledge-retrieval",
                "teaching-intent",
                "generation-plan",
                "revision"
        );
        assertThat(paths).containsExactlyInAnyOrder(
                "/v1/workflows/run"
        );
        assertThat(authorizations).containsExactlyInAnyOrder(
                "Bearer wf-02-key",
                "Bearer wf-03-key",
                "Bearer wf-04-key",
                "Bearer wf-04-key",
                "Bearer wf-05-key",
                "Bearer wf-07-key"
        );
        assertThat(requestEnvelopes.get("material-analysis")
                .path("input").path("materialText").path("content").asText())
                .contains("part of a whole");
        assertThat(requestEnvelopes.get("knowledge-retrieval")
                .path("input").path("knowledgeCandidates").size()).isEqualTo(1);
        assertThat(requestEnvelopes.get("teaching-intent")
                .path("input").path("requirementSummary").path("status").asText())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void missingPerWorkflowApiKeyFailsBeforeAnyHttpCall() {
        properties.getDify().setWorkflowId("legacy-shared-id-must-not-be-used");
        properties.getDify().setApiKey("legacy-shared-key-must-not-be-used");
        AtomicInteger calls = new AtomicInteger();
        handler.set(exchange -> calls.incrementAndGet());

        assertThatThrownBy(() -> gateway().clarifyRequirement(new ClarificationRequest(
                78L,
                "Create a fraction lesson",
                List.of(),
                GenerationMode.STANDARD
        )))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("API key is missing for WF-01")
                .hasMessageNotContaining("legacy-shared-key");
        assertThat(calls).hasValue(0);
    }

    @Test
    void expectedWorkflowIdIsOptionalAndReturnedIdBecomesResponseReference() {
        configure(WorkflowCode.CLARIFICATION, null, "wf-01-key");
        handler.set(exchange -> sendJson(exchange, 200, successWithObjectOutput(
                "runtime-wf-01",
                "result",
                outputForClarification()
        )));

        var response = gateway().clarifyRequirement(new ClarificationRequest(
                78L,
                "Create a fraction lesson",
                List.of(),
                GenerationMode.STANDARD
        ));

        assertThat(response.workflow()).isEqualTo("runtime-wf-01");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500})
    void httpFailuresExposeOnlyStatusAndNeverProviderBodyOrKey(int status) {
        configure(WorkflowCode.CLARIFICATION, "published-wf-01", "private-key-value");
        handler.set(exchange -> sendRaw(exchange, status, "{\"error\":\"provider-secret-body\"}"));

        assertThatThrownBy(() -> gateway().clarifyRequirement(new ClarificationRequest(
                78L,
                "Create a fraction lesson",
                List.of(),
                GenerationMode.STANDARD
        )))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("HTTP " + status)
                .hasMessageNotContaining("provider-secret-body")
                .hasMessageNotContaining("private-key-value");
    }

    @Test
    void connectionFailureIsReportedAsUnavailableWithoutEndpointDetails() {
        configure(WorkflowCode.CLARIFICATION, "published-wf-01", "wf-01-key");
        int closedPort = server.getAddress().getPort();
        server.stop(0);
        server = null;
        properties.getDify().setBaseUrl("http://127.0.0.1:" + closedPort + "/v1");

        assertThatThrownBy(() -> gateway().clarifyRequirement(new ClarificationRequest(
                78L,
                "Create a fraction lesson",
                List.of(),
                GenerationMode.STANDARD
        )))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("unavailable or timed out")
                .hasMessageNotContaining(String.valueOf(closedPort));
    }

    @Test
    void invalidJsonAndIncompleteBusinessOutputAreRejected() {
        configure(WorkflowCode.CLARIFICATION, "published-wf-01", "wf-01-key");
        AtomicInteger calls = new AtomicInteger();
        handler.set(exchange -> {
            if (calls.getAndIncrement() == 0) {
                sendRaw(exchange, 200, "not-json");
                return;
            }
            sendJson(exchange, 200, successWithObjectOutput(
                    "published-wf-01",
                    "result",
                    objectNode("{\"missingFields\":[]}")
            ));
        });

        DifyAIWorkflowGateway gateway = gateway();
        ClarificationRequest request = new ClarificationRequest(
                78L,
                "Create a fraction lesson",
                List.of(),
                GenerationMode.STANDARD
        );
        assertThatThrownBy(() -> gateway.clarifyRequirement(request))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("invalid JSON");
        assertThatThrownBy(() -> gateway.clarifyRequirement(request))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("questions is missing");
    }

    private DifyAIWorkflowGateway gateway() {
        return new DifyAIWorkflowGateway(
                properties,
                objectMapper,
                new DifyWorkflowContractAdapter(objectMapper)
        );
    }

    private void configure(WorkflowCode workflowCode, String workflowId, String apiKey) {
        Workflow workflow = switch (workflowCode) {
            case CLARIFICATION -> properties.getDify().getWorkflows().getClarification();
            case REQUIREMENT_SUMMARY -> properties.getDify().getWorkflows().getSummary();
            case MATERIAL_ANALYSIS -> properties.getDify().getWorkflows().getMaterial();
            case KNOWLEDGE_AND_TEACHING_INTENT -> properties.getDify().getWorkflows().getKnowledgeIntent();
            case GENERATION_PLAN -> properties.getDify().getWorkflows().getGenerationPlan();
            case CONTENT_DRAFT -> properties.getDify().getWorkflows().getContentDraft();
            case REVISION -> properties.getDify().getWorkflows().getRevision();
        };
        workflow.setWorkflowId(workflowId);
        workflow.setApiKey(apiKey);
    }

    private ObjectNode outputFor(String operation) {
        return switch (operation) {
            case "requirement-summary" -> objectNode("""
                    {
                      "workflowCode": "WF-02",
                      "success": true,
                      "data": {
                        "requirementSummary": {
                          "courseName": "Mathematics",
                          "chapterTitle": "Fractions",
                          "targetStudents": "Grade 5",
                          "lessonDuration": 40,
                          "teachingGoals": ["Understand fractions"],
                          "knowledgePoints": ["Fraction definition"],
                          "keyDifficulties": ["Equivalent fractions"],
                          "outputTypes": ["PPT", "DOCX", "INTERACTION"],
                          "coursewareStyle": "Clear",
                          "interactionType": "Quiz",
                          "generationMode": "STANDARD"
                        },
                        "uncertainFields": [],
                        "generationHints": ["Use a visual fraction model"]
                      },
                      "warnings": [],
                      "errors": [],
                      "confidence": 0.96,
                      "traceHint": "a12-WF-02-project-78"
                    }
                    """);
            case "material-analysis" -> objectNode("""
                    {
                      "workflowCode": "WF-03",
                      "success": true,
                      "data": {
                        "materialSummary": {
                          "title": "Fraction notes",
                          "overview": "Material summary",
                          "keywords": ["fraction"]
                        },
                        "usableFragments": [{
                          "fragmentId": "fragment-1",
                          "content": "A fraction represents part of a whole.",
                          "sourceName": "fractions.pdf",
                          "pageNumber": 1,
                          "purposeTypes": ["TEXTBOOK_BASIS"]
                        }],
                        "riskNotes": []
                      },
                      "warnings": [],
                      "errors": [],
                      "confidence": 0.95,
                      "traceHint": "a12-WF-03-project-78"
                    }
                    """);
            case "knowledge-retrieval" -> objectNode("""
                    {
                      "workflowCode": "WF-04",
                      "success": true,
                      "data": {
                        "knowledgeRetrieval": {
                          "snippets": [{
                            "sourceId": "candidate-1",
                            "title": "Fraction definition",
                            "sourceName": "fractions.pdf",
                            "content": "A fraction represents part of a whole.",
                            "relevance": 0.95
                          }],
                          "retrievalNote": "One grounded result"
                        }
                      },
                      "warnings": [],
                      "errors": [],
                      "confidence": 0.95,
                      "traceHint": "a12-WF-04-project-78"
                    }
                    """);
            case "teaching-intent" -> objectNode("""
                    {
                      "workflowCode": "WF-04",
                      "success": true,
                      "data": {
                        "intentId": "intent-dify-78",
                        "teachingIntent": {
                          "teachingGoals": ["Explain fractions"],
                          "contentPriorities": ["Use fractions.pdf as evidence"],
                          "keyDifficulties": ["Equivalent fractions"],
                          "teachingOrganization": ["Whole-class explanation"],
                          "interactionPlan": ["Quick quiz"],
                          "outputTypes": ["PPT", "DOCX", "INTERACTION"],
                          "constraints": ["40 minutes"]
                        },
                        "usedReferences": [],
                        "conflictWarnings": []
                      },
                      "warnings": [],
                      "errors": [],
                      "confidence": 0.93,
                      "traceHint": "a12-WF-04-project-78"
                    }
                    """);
            case "generation-plan" -> objectNode("""
                    {
                      "planId": "plan-dify-78",
                      "pptOutline": [{
                        "title": "Introduction",
                        "points": ["Learning goal"],
                        "materialReference": "fractions.pdf"
                      }],
                      "docOutline": [{
                        "title": "Lesson process",
                        "points": ["Explain"],
                        "materialReference": "fractions.pdf"
                      }],
                      "interactionPlan": ["Quick quiz"],
                      "estimatedDuration": "20 seconds",
                      "nextAction": "Confirm plan"
                    }
                    """);
            case "revision" -> objectNode("""
                    {
                      "changeSummary": "Added one example",
                      "changedSections": ["Examples"],
                      "revisedContent": "Current content plus an example",
                      "versionSuggestion": "Create a new draft"
                    }
                    """);
            default -> throw new IllegalArgumentException("Unexpected operation: " + operation);
        };
    }

    private String workflowIdForOperation(String operation) {
        return switch (operation) {
            case "requirement-summary" -> "published-wf-02";
            case "material-analysis" -> "published-wf-03";
            case "knowledge-retrieval", "teaching-intent" -> "published-wf-04";
            case "generation-plan" -> "published-wf-05";
            case "revision" -> "published-wf-07";
            default -> throw new IllegalArgumentException("Unexpected operation: " + operation);
        };
    }

    private ObjectNode outputForClarification() {
        return objectNode("""
                {
                  "missingFields": ["targetAudience"],
                  "questions": ["Which audience?"],
                  "suggestedFields": {"targetAudience": "Grade 5"},
                  "nextAction": "Confirm the missing field"
                }
                """);
    }

    private RequirementSummaryData requirementSummaryData() {
        return new RequirementSummaryData(
                "Mathematics",
                "Fractions",
                "Grade 5",
                40,
                List.of("Understand fractions"),
                List.of("Equivalent fractions"),
                List.of("PPT", "DOCX", "INTERACTION"),
                "Clear",
                "Quiz"
        );
    }

    private ObjectNode successWithTextResult(String workflowId, String result) {
        ObjectNode response = successEnvelope(workflowId);
        ((ObjectNode) response.path("data").path("outputs")).put("result", result);
        return response;
    }

    private ObjectNode successWithObjectOutput(String workflowId, String field, JsonNode output) {
        ObjectNode response = successEnvelope(workflowId);
        ((ObjectNode) response.path("data").path("outputs")).set(field, output);
        return response;
    }

    private ObjectNode successEnvelope(String workflowId) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode data = response.putObject("data");
        data.put("workflow_id", workflowId);
        data.put("status", "succeeded");
        data.putObject("outputs");
        return response;
    }

    private ObjectNode objectNode(String json) {
        try {
            return (ObjectNode) objectMapper.readTree(json);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        return objectMapper.readTree(exchange.getRequestBody());
    }

    private void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        sendRaw(exchange, status, objectMapper.writeValueAsString(body));
    }

    private void sendRaw(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
