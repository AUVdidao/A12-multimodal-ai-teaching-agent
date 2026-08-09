package com.auvdidao.a12teachingagent.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingProvider.class);
    private static final String PROVIDER_NAME = "OPENAI_COMPATIBLE";

    private final ObjectMapper objectMapper;
    private final EmbeddingProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public OpenAiCompatibleEmbeddingProvider(ObjectMapper objectMapper, EmbeddingProperties properties) {
        this(objectMapper, properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .build());
    }

    OpenAiCompatibleEmbeddingProvider(
            ObjectMapper objectMapper,
            EmbeddingProperties properties,
            HttpClient httpClient
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public EmbeddingBatchResult embed(List<String> inputs) {
        validateInputs(inputs);
        String configuredProvider = normalize(properties.getProvider());
        if ("DISABLED".equals(configuredProvider)) {
            throw failure(EmbeddingFailureKind.NOT_CONFIGURED, "Embedding provider is disabled");
        }
        if (!PROVIDER_NAME.equals(configuredProvider)) {
            throw failure(EmbeddingFailureKind.NOT_CONFIGURED, "Embedding provider is not supported");
        }

        requireConfiguration();
        int batchSize = properties.getBatchSize();
        if (batchSize <= 0) {
            throw failure(EmbeddingFailureKind.NOT_CONFIGURED, "Embedding batch size must be positive");
        }

        long started = System.nanoTime();
        Map<Integer, EmbeddingVector> byIndex = new HashMap<>();
        String serverModel = null;
        int dimensions = 0;
        int batchCount = 0;

        for (int offset = 0; offset < inputs.size(); offset += batchSize) {
            int end = Math.min(inputs.size(), offset + batchSize);
            List<String> batch = inputs.subList(offset, end);
            BatchResponse response = requestBatch(batch);
            batchCount++;

            if (StringUtils.hasText(response.model())) {
                if (serverModel != null && !serverModel.equals(response.model())) {
                    throw invalidResponse("Embedding response model changed between batches");
                }
                serverModel = response.model();
            }

            for (EmbeddingVector vector : response.vectors()) {
                int globalIndex = offset + vector.index();
                if (byIndex.putIfAbsent(globalIndex, new EmbeddingVector(globalIndex, vector.values())) != null) {
                    throw invalidResponse("Embedding response contains a duplicate index");
                }
                if (dimensions == 0) {
                    dimensions = vector.values().size();
                } else if (dimensions != vector.values().size()) {
                    throw invalidResponse("Embedding response vectors have inconsistent dimensions");
                }
            }
        }

        if (byIndex.size() != inputs.size() || dimensions <= 0) {
            throw invalidResponse("Embedding response does not contain one valid vector per input");
        }

        List<EmbeddingVector> ordered = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            EmbeddingVector vector = byIndex.get(index);
            if (vector == null) {
                throw invalidResponse("Embedding response is missing a vector");
            }
            ordered.add(vector);
        }

        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        LOGGER.info(
                "Embedding request completed: provider={}, model={}, batches={}, inputs={}, dimensions={}, latencyMs={}",
                PROVIDER_NAME,
                StringUtils.hasText(serverModel) ? serverModel : properties.getModel().strip(),
                batchCount,
                inputs.size(),
                dimensions,
                latencyMs
        );
        return new EmbeddingBatchResult(
                PROVIDER_NAME,
                StringUtils.hasText(serverModel) ? serverModel : properties.getModel().strip(),
                dimensions,
                ordered
        );
    }

    private BatchResponse requestBatch(List<String> inputs) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", properties.getModel().strip(),
                    "input", inputs
            ));
        } catch (JsonProcessingException exception) {
            throw new EmbeddingException(
                    EmbeddingFailureKind.TRANSPORT,
                    "Embedding request could not be serialized",
                    exception
            );
        }

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(embeddingsUri())
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .header("Authorization", "Bearer " + properties.getApiKey().strip())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new EmbeddingException(EmbeddingFailureKind.TIMEOUT, "Embedding request timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException(EmbeddingFailureKind.TRANSPORT, "Embedding request was interrupted", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new EmbeddingException(
                    EmbeddingFailureKind.TRANSPORT,
                    "Embedding provider transport failed",
                    exception
            );
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw httpFailure(response.statusCode());
        }
        return parseResponse(response.body(), inputs.size());
    }

    private BatchResponse parseResponse(String responseBody, int expectedCount) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new EmbeddingException(
                    EmbeddingFailureKind.INVALID_RESPONSE,
                    "Embedding provider returned malformed JSON",
                    exception
            );
        }

        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray() || data.size() != expectedCount) {
            throw invalidResponse("Embedding response data count does not match input count");
        }

        Set<Integer> seenIndices = new HashSet<>();
        List<EmbeddingVector> vectors = new ArrayList<>(expectedCount);
        for (JsonNode item : data) {
            JsonNode indexNode = item == null ? null : item.get("index");
            if (indexNode == null || !indexNode.canConvertToInt() || !indexNode.isIntegralNumber()) {
                throw invalidResponse("Embedding response contains an invalid index");
            }
            int index = indexNode.intValue();
            if (index < 0 || index >= expectedCount || !seenIndices.add(index)) {
                throw invalidResponse("Embedding response contains a duplicate or out-of-range index");
            }

            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
                throw invalidResponse("Embedding response contains a missing vector");
            }
            List<Double> values = new ArrayList<>(embedding.size());
            for (JsonNode value : embedding) {
                if (value == null || !value.isNumber()) {
                    throw invalidResponse("Embedding response contains a non-numeric vector value");
                }
                double number = value.doubleValue();
                if (!Double.isFinite(number)) {
                    throw invalidResponse("Embedding response contains a non-finite vector value");
                }
                values.add(number);
            }
            vectors.add(new EmbeddingVector(index, values));
        }

        int dimensions = vectors.get(0).values().size();
        if (dimensions <= 0 || vectors.stream().anyMatch(vector -> vector.values().size() != dimensions)) {
            throw invalidResponse("Embedding response vectors have inconsistent dimensions");
        }
        return new BatchResponse(text(root, "model"), vectors);
    }

    private void validateInputs(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw failure(EmbeddingFailureKind.INVALID_INPUT, "Embedding inputs must not be empty");
        }
        for (int index = 0; index < inputs.size(); index++) {
            if (!StringUtils.hasText(inputs.get(index))) {
                throw failure(EmbeddingFailureKind.INVALID_INPUT, "Embedding input at index " + index + " must not be blank");
            }
        }
    }

    private void requireConfiguration() {
        if (!StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(properties.getApiKey())
                || !StringUtils.hasText(properties.getModel())) {
            throw failure(EmbeddingFailureKind.NOT_CONFIGURED, "Embedding provider is not configured");
        }
        try {
            URI uri = URI.create(normalizedBaseUrl());
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("unsupported endpoint");
            }
        } catch (IllegalArgumentException exception) {
            throw new EmbeddingException(
                    EmbeddingFailureKind.NOT_CONFIGURED,
                    "Embedding provider endpoint is invalid",
                    exception
            );
        }
    }

    private URI embeddingsUri() {
        return URI.create(normalizedBaseUrl() + "/embeddings");
    }

    private String normalizedBaseUrl() {
        return properties.getBaseUrl().strip().replaceAll("/+$", "");
    }

    private EmbeddingException httpFailure(int statusCode) {
        EmbeddingFailureKind kind = switch (statusCode) {
            case 401, 403 -> EmbeddingFailureKind.AUTHENTICATION;
            case 408, 504 -> EmbeddingFailureKind.TIMEOUT;
            case 429 -> EmbeddingFailureKind.RATE_LIMITED;
            default -> EmbeddingFailureKind.UPSTREAM_FAILURE;
        };
        return new EmbeddingException(kind, "Embedding provider returned HTTP " + statusCode, statusCode);
    }

    private EmbeddingException invalidResponse(String message) {
        return failure(EmbeddingFailureKind.INVALID_RESPONSE, message);
    }

    private EmbeddingException failure(EmbeddingFailureKind kind, String message) {
        return new EmbeddingException(kind, message);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase().replace('-', '_');
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText().strip() : null;
    }

    private record BatchResponse(String model, List<EmbeddingVector> vectors) {
    }
}
