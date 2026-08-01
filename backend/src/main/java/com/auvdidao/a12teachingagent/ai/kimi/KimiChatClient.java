package com.auvdidao.a12teachingagent.ai.kimi;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KimiChatClient {

    private final ObjectMapper objectMapper;
    private final KimiAssistantProperties properties;
    private final HttpClient httpClient;

    public KimiChatClient(ObjectMapper objectMapper, KimiAssistantProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
                .build();
    }

    public String complete(
            List<Map<String, String>> messages,
            String model,
            int maxCompletionTokens,
            int timeoutSeconds
    ) {
        requireConfiguration(model);
        int attempts = Math.max(1, properties.getRequestAttempts());
        KimiClientException lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model.strip());
                body.put("max_tokens", Math.max(1, maxCompletionTokens));
                body.put("messages", messages);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(normalizedBaseUrl() + "/chat/completions"))
                        .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                        .header("Authorization", "Bearer " + properties.getApiKey().strip())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    lastFailure = new KimiClientException(
                            "KIMI_REQUEST_FAILED",
                            "Kimi returned HTTP " + response.statusCode(),
                            response.statusCode()
                    );
                    if (isRetryableStatus(response.statusCode()) && attempt < attempts) {
                        pauseBeforeRetry();
                        continue;
                    }
                    throw lastFailure;
                }

                JsonNode content = objectMapper.readTree(response.body())
                        .path("choices").path(0).path("message").path("content");
                if (!content.isTextual() || !StringUtils.hasText(content.asText())) {
                    throw new KimiClientException(
                            "KIMI_INVALID_RESPONSE",
                            "Kimi response content is missing",
                            502
                    );
                }
                return content.asText().strip();
            } catch (KimiClientException exception) {
                lastFailure = exception;
                if (isRetryableStatus(exception.getStatusCode()) && attempt < attempts) {
                    pauseBeforeRetry();
                    continue;
                }
                throw exception;
            } catch (java.net.http.HttpTimeoutException exception) {
                lastFailure = new KimiClientException("KIMI_TIMEOUT", "Kimi request timed out", 504);
                if (attempt < attempts) {
                    pauseBeforeRetry();
                    continue;
                }
                throw lastFailure;
            } catch (IOException exception) {
                lastFailure = new KimiClientException("KIMI_UNAVAILABLE", "Kimi cannot be reached", 502);
                if (attempt < attempts) {
                    pauseBeforeRetry();
                    continue;
                }
                throw lastFailure;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new KimiClientException("KIMI_INTERRUPTED", "Kimi request was interrupted", 503);
            } catch (IllegalArgumentException exception) {
                throw new KimiClientException("KIMI_INVALID_CONFIGURATION", "Kimi endpoint is invalid", 503);
            }
        }

        throw lastFailure == null
                ? new KimiClientException("KIMI_UNAVAILABLE", "Kimi cannot be reached", 502)
                : lastFailure;
    }

    private void requireConfiguration(String model) {
        if (!StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(properties.getApiKey())
                || !StringUtils.hasText(model)) {
            throw new KimiClientException("KIMI_NOT_CONFIGURED", "Kimi is not configured", 503);
        }
    }

    private String normalizedBaseUrl() {
        return properties.getBaseUrl().strip().replaceAll("/+$", "");
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private void pauseBeforeRetry() {
        long delay = Math.max(0, properties.getRetryDelayMillis());
        if (delay == 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KimiClientException("KIMI_INTERRUPTED", "Kimi retry was interrupted", 503);
        }
    }
}
