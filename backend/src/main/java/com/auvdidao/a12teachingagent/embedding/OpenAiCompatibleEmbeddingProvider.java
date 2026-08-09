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
    private static final long MAX_RETRY_AFTER_MILLIS = 30_000;

    private final ObjectMapper objectMapper;
    private final EmbeddingProperties properties;
    private final HttpClient httpClient;
    private final Sleeper sleeper;

    @Autowired
    public OpenAiCompatibleEmbeddingProvider(ObjectMapper objectMapper, EmbeddingProperties properties) {
        this(objectMapper, properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .build(), Thread::sleep);
    }

    OpenAiCompatibleEmbeddingProvider(
            ObjectMapper objectMapper,
            EmbeddingProperties properties,
            HttpClient httpClient
    ) {
        this(objectMapper, properties, httpClient, Thread::sleep);
    }

    OpenAiCompatibleEmbeddingProvider(
            ObjectMapper objectMapper,
            EmbeddingProperties properties,
            Sleeper sleeper
    ) {
        this(objectMapper, properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .build(), sleeper);
    }

    OpenAiCompatibleEmbeddingProvider(
            ObjectMapper objectMapper,
            EmbeddingProperties properties,
            HttpClient httpClient,
            Sleeper sleeper
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = httpClient;
        this.sleeper = sleeper;
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
        validateRetryConfiguration();

        long started = System.nanoTime();
        Map<Integer, EmbeddingVector> byIndex = new HashMap<>();
        String serverModel = null;
        int dimensions = 0;
        int batchCount = 0;

        for (int offset = 0; offset < inputs.size(); offset += batchSize) {
            int end = Math.min(inputs.size(), offset + batchSize);
            List<String> batch = inputs.subList(offset, end);
            BatchResponse response = requestBatch(batch, batchCount + 1);
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

    private BatchResponse requestBatch(List<String> inputs, int batchNumber) {
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

        int attempts = properties.getRequestAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(embeddingsUri())
                        .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                        .header("Authorization", "Bearer " + properties.getApiKey().strip())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    EmbeddingException failure = httpFailure(response.statusCode());
                    if (!shouldRetry(failure, attempt, attempts)) {
                        throw failure;
                    }
                    retry(batchNumber, attempt, attempts, failure, retryAfterMillis(response));
                    continue;
                }
                return parseResponse(response.body(), inputs.size());
            } catch (java.net.http.HttpTimeoutException exception) {
                EmbeddingException failure = new EmbeddingException(
                        EmbeddingFailureKind.TIMEOUT,
                        "Embedding request timed out",
                        exception
                );
                if (!shouldRetry(failure, attempt, attempts)) {
                    throw failure;
                }
                retry(batchNumber, attempt, attempts, failure, 0);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException(EmbeddingFailureKind.TRANSPORT, "Embedding request was interrupted", exception);
            } catch (IOException exception) {
                EmbeddingException failure = new EmbeddingException(
                        EmbeddingFailureKind.TRANSPORT,
                        "Embedding provider transport failed",
                        exception
                );
                if (!shouldRetry(failure, attempt, attempts)) {
                    throw failure;
                }
                retry(batchNumber, attempt, attempts, failure, 0);
            } catch (IllegalArgumentException exception) {
                throw new EmbeddingException(
                        EmbeddingFailureKind.TRANSPORT,
                        "Embedding provider transport failed",
                        exception
                );
            }
        }
        throw failure(EmbeddingFailureKind.TRANSPORT, "Embedding provider retry attempts were exhausted");
    }

    private void validateRetryConfiguration() {
        if (properties.getRequestAttempts() < 1) {
            throw failure(EmbeddingFailureKind.NOT_CONFIGURED, "Embedding request attempts must be at least one");
        }
        if (properties.getRetryDelayMillis() < 0) {
            throw failure(EmbeddingFailureKind.NOT_CONFIGURED, "Embedding retry delay must not be negative");
        }
    }

    private boolean shouldRetry(EmbeddingException failure, int attempt, int attempts) {
        if (attempt >= attempts) {
            return false;
        }
        return switch (failure.getKind()) {
            case RATE_LIMITED, TIMEOUT -> true;
            case TRANSPORT -> failure.getStatusCode() == 0;
            case UPSTREAM_FAILURE -> failure.getStatusCode() == 0
                    || isRetryableHttpStatus(failure.getStatusCode());
            default -> false;
        };
    }

    private boolean isRetryableHttpStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private void retry(
            int batchNumber,
            int attempt,
            int attempts,
            EmbeddingException failure,
            long retryAfterMillis
    ) {
        long delayMillis = Math.max(properties.getRetryDelayMillis(), retryAfterMillis);
        LOGGER.warn(
                "Embedding batch transient failure: provider={}, model={}, batch={}, attempt={}/{}, kind={}, status={}, delayMs={}",
                PROVIDER_NAME,
                properties.getModel().strip(),
                batchNumber,
                attempt,
                attempts,
                failure.getKind(),
                failure.getStatusCode(),
                delayMillis
        );
        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException(
                    EmbeddingFailureKind.TRANSPORT,
                    "Embedding retry was interrupted",
                    exception
            );
        }
    }

    private long retryAfterMillis(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        try {
            long seconds = Long.parseLong(value.strip());
            if (seconds < 0) {
                return 0;
            }
            return Math.min(seconds, MAX_RETRY_AFTER_MILLIS / 1000) * 1000;
        } catch (NumberFormatException exception) {
            return 0;
        }
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

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
