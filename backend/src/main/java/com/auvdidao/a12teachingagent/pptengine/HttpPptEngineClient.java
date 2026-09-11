package com.auvdidao.a12teachingagent.pptengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ExecutionResponse;
import static com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.GenerationRequest;

/** Contract-only HTTP adapter. It deliberately has no credential/header configuration. */
@Component
@ConditionalOnProperty(name = "a12.ppt-engine.mode", havingValue = "remote")
final class HttpPptEngineClient implements PptEngineClient {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String traceFile;

    @Autowired
    HttpPptEngineClient(
            @Value("${a12.ppt-engine.base-url}") String baseUrl,
            @Value("${a12.ppt-engine.timeout-ms:15000}") long timeoutMs,
            @Value("${a12.ppt-engine.trace-file:}") String traceFile,
            ObjectMapper objectMapper
    ) {
        this(baseUrl, timeoutMs, objectMapper, traceFile, true);
    }

    HttpPptEngineClient(String baseUrl, long timeoutMs) {
        this(baseUrl, timeoutMs, new ObjectMapper(), "", true);
    }

    private HttpPptEngineClient(String baseUrl, long timeoutMs, ObjectMapper objectMapper, String traceFile, boolean ignored) {
        if (baseUrl == null || baseUrl.isBlank() || timeoutMs < 1 || timeoutMs > 86_400_000L) {
            throw new IllegalArgumentException("PPT Engine HTTP configuration is invalid");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build());
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.objectMapper = objectMapper;
        this.traceFile = traceFile == null ? "" : traceFile.trim();
    }

    @Override
    public ExecutionResponse execute(GenerationRequest request) {
        try {
            ObservedResponse observed = executeObserved(request);
            if (observed.statusCode() < 200 || observed.statusCode() >= 300) {
                throw new PptEngineException("PPT_ENGINE_HTTP_" + observed.statusCode(), 502,
                        "PPT Engine rejected the generation request");
            }
            ExecutionResponse response = observed.body();
            if (response == null) {
                throw new PptEngineException("PPT_ENGINE_EMPTY_RESPONSE", 502,
                        "PPT Engine returned an empty response");
            }
            return response;
        } catch (PptEngineException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new PptEngineException("PPT_ENGINE_HTTP_" + exception.getStatusCode().value(), 502,
                    "PPT Engine rejected the generation request");
        } catch (RestClientException exception) {
            throw new PptEngineException("PPT_ENGINE_TRANSPORT_FAILED", 503,
                    "PPT Engine is unavailable or timed out");
        }
    }

    /** Package-local HTTP observation used by the contract test to assert the actual response. */
    ObservedResponse executeObserved(GenerationRequest request) {
        ObjectNode envelope = toExecuteEnvelope(request);
        String runId = request == null || request.executionContext() == null
                ? "unknown" : request.executionContext().executionId();
        if (request != null && request.executionBindings() != null) {
            ObjectNode compose = toComposeEnvelope(request);
            String composeJson = json(compose);
            ObservedPlanResponse observedPlan = client.post()
                    .uri("/internal/v1/compose-plan")
                    .body(compose)
                    .exchange((httpRequest, httpResponse) -> {
                        int statusCode = httpResponse.getStatusCode().value();
                        String contentType = httpResponse.getHeaders().getFirst("Content-Type");
                        byte[] responseBytes = httpResponse.getBody() == null
                                ? new byte[0] : httpResponse.getBody().readAllBytes();
                        String responseJson = new String(responseBytes, StandardCharsets.UTF_8);
                        recordWireExchange(runId, httpRequest.getMethod().name(), httpRequest.getURI().toString(),
                                httpRequest.getHeaders().getFirst("Content-Type"), composeJson,
                                statusCode, contentType, responseJson);
                        JsonNode body = responseJson.isBlank() ? null : objectMapper.readTree(responseJson);
                        return new ObservedPlanResponse(statusCode, contentType, body);
                    });
            if (observedPlan.statusCode() < 200 || observedPlan.statusCode() >= 300) {
                throw new PptEngineException("PPT_ENGINE_COMPOSE_HTTP_" + observedPlan.statusCode(), 502,
                        "PPT Engine rejected the composition plan");
            }
            JsonNode plan = observedPlan.body();
            if (plan == null || !plan.hasNonNull("plan")) {
                throw new PptEngineException("PPT_ENGINE_COMPOSE_REJECTED", 502,
                        "PPT Engine rejected the composition plan");
            }
            envelope.set("plan", plan.get("plan"));
        }
        String requestJson = json(envelope);
        return client.post()
                .uri("/internal/v2/execute")
                .body(envelope)
                .exchange((httpRequest, httpResponse) -> {
                    int statusCode = httpResponse.getStatusCode().value();
                    String contentType = httpResponse.getHeaders().getFirst("Content-Type");
                    byte[] responseBytes = httpResponse.getBody() == null
                            ? new byte[0] : httpResponse.getBody().readAllBytes();
                    String responseJson = new String(responseBytes, StandardCharsets.UTF_8);
                    recordWireExchange(runId, httpRequest.getMethod().name(), httpRequest.getURI().toString(),
                            httpRequest.getHeaders().getFirst("Content-Type"), requestJson,
                            statusCode, contentType, responseJson);
                    ExecutionResponse body = null;
                    if (statusCode >= 200 && statusCode < 300) {
                        body = objectMapper.readValue(responseBytes, ExecutionResponse.class);
                    }
                    return new ObservedResponse(statusCode, contentType, body);
                });
    }

    private void recordWireExchange(String runId, String method, String uri, String requestContentType, String requestJson,
                                    int responseStatus, String responseContentType, String responseJson) {
        if (traceFile.isBlank()) return;
        try {
            Path path = Path.of(traceFile).toAbsolutePath().normalize();
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            ObjectNode record = objectMapper.createObjectNode();
            record.put("runId", runId == null || runId.isBlank() ? "unknown" : runId);
            record.put("capturedAtUtc", OffsetDateTime.now(ZoneOffset.UTC).toString());
            record.put("method", method);
            record.put("uri", uri);
            record.put("requestContentType", requestContentType == null ? "" : requestContentType);
            record.set("requestJson", sanitizedJson(requestJson));
            record.put("responseStatus", responseStatus);
            record.put("responseContentType", responseContentType == null ? "" : responseContentType);
            record.set("responseJson", sanitizedJson(responseJson));
            Files.writeString(path, objectMapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception exception) {
            throw new PptEngineException("PPT_ENGINE_TRACE_FAILED", 502,
                    "PPT Engine exchange evidence could not be persisted");
        }
    }

    private JsonNode sanitizedJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "null" : json);
            return sanitizeNode(node);
        } catch (Exception exception) {
            return objectMapper.getNodeFactory().textNode("[UNPARSEABLE_REDACTED]");
        }
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new PptEngineException("PPT_ENGINE_TRACE_FAILED", 502,
                    "PPT Engine request could not be serialized for exchange evidence");
        }
    }

    private JsonNode sanitizeNode(JsonNode node) {
        if (node == null || node.isValueNode()) return node;
        if (node.isArray()) {
            var array = objectMapper.createArrayNode();
            node.forEach(item -> array.add(sanitizeNode(item)));
            return array;
        }
        ObjectNode object = objectMapper.createObjectNode();
        node.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            if (name.matches("(?i)^(api[_-]?key|authorization|bearer|credential|password|secret|access[_-]?token|refresh[_-]?token|token|private[_-]?key|client[_-]?secret)$")) {
                object.put(name, "[REDACTED]");
            } else {
                object.set(name, sanitizeNode(entry.getValue()));
            }
        });
        return object;
    }

    /**
     * Complete wire inputs are produced by the main domain binding adapter.
     * The legacy branch remains deliberately incomplete for unavailable/test
     * inputs and is rejected by the Engine Contract Gate rather than faking a plan.
     */
    private ObjectNode toExecuteEnvelope(GenerationRequest request) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("contractVersion", "2.0.0");
        String requestId = request == null || request.executionContext() == null
                ? "unknown" : request.executionContext().executionId();
        envelope.put("requestId", requestId == null || requestId.isBlank() ? "unknown" : requestId);
        if (request != null && request.executionBindings() != null) {
            var bindings = request.executionBindings();
            envelope.set("generationJob", bindings.generationJob());
            envelope.set("specification", bindings.specification());
            envelope.set("templateProfile", bindings.templateProfile());
            envelope.set("approvedAssetManifest", bindings.approvedAssetManifest());
            envelope.set("templateSource", bindings.templateSource());
            var assets = objectMapper.createArrayNode();
            bindings.approvedAssetFiles().forEach(assets::add);
            envelope.set("approvedAssetFiles", assets);
        } else if (request != null && request.executionContext() != null) {
            var context = request.executionContext();
            ObjectNode job = envelope.putObject("generationJob");
            if (context.jobId() != null) job.put("generationJobId", context.jobId().toString());
            if (context.executionId() != null) job.put("executionAttemptId", context.executionId());
            if (context.projectId() != null) job.put("requestedBy", context.projectId().toString());
            if (context.engineVersion() != null) job.put("engineBuildVersion", context.engineVersion());
            envelope.set("specification", objectMapper.valueToTree(request.specification()));
            envelope.set("templateProfile", objectMapper.valueToTree(request.templateProfile()));
            envelope.set("approvedAssetManifest", objectMapper.valueToTree(request.approvedAssetManifest()));
        }
        return envelope;
    }

    private ObjectNode toComposeEnvelope(GenerationRequest request) {
        var bindings = request.executionBindings();
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("contractVersion", "2.0.0");
        envelope.put("requestId", request.executionContext().executionId());
        envelope.set("generationJob", bindings.generationJob());
        envelope.set("specification", bindings.specification());
        envelope.set("templateProfile", bindings.templateProfile());
        envelope.set("approvedAssetManifest", bindings.approvedAssetManifest());
        return envelope;
    }

    record ObservedResponse(int statusCode, String contentType, ExecutionResponse body) { }

    record ObservedPlanResponse(int statusCode, String contentType, JsonNode body) { }
}
