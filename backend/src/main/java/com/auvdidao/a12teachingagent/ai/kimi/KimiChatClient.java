package com.auvdidao.a12teachingagent.ai.kimi;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.auvdidao.a12teachingagent.ai.credential.AiApiCredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AiApiCredentialService credentialService;
    private final HttpClient httpClient;

    public KimiChatClient(ObjectMapper objectMapper, KimiAssistantProperties properties) {
        this(objectMapper, properties, null);
    }

    @Autowired
    public KimiChatClient(
            ObjectMapper objectMapper,
            KimiAssistantProperties properties,
            AiApiCredentialService credentialService
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.credentialService = credentialService;
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
        return complete(new KimiChatRequest(
                messages,
                model,
                maxCompletionTokens,
                timeoutSeconds,
                null
        )).content();
    }

    public KimiChatResponse complete(KimiChatRequest chatRequest) {
        if (chatRequest == null) {
            throw new KimiClientException("KIMI_INVALID_CONFIGURATION", "Kimi request is required", 503);
        }
        String apiKey = resolveApiKey();
        requireConfiguration(chatRequest.model(), apiKey);
        int attempts = Math.max(1, properties.getRequestAttempts());
        KimiClientException lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", chatRequest.model().strip());
                body.put("max_completion_tokens", Math.max(1, chatRequest.maxCompletionTokens()));
                body.put("messages", chatRequest.messages());
                if (chatRequest.responseFormat() != null) {
                    body.put("response_format", chatRequest.responseFormat());
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(normalizedBaseUrl() + "/chat/completions"))
                        .timeout(Duration.ofSeconds(Math.max(1, chatRequest.timeoutSeconds())))
                        .header("Authorization", "Bearer " + apiKey.strip())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    lastFailure = new KimiClientException(
                            "KIMI_REQUEST_FAILED",
                            describeHttpFailure(response),
                            response.statusCode()
                    );
                    if (isRetryableStatus(response.statusCode()) && attempt < attempts) {
                        pauseBeforeRetry();
                        continue;
                    }
                    throw lastFailure;
                }

                JsonNode choice = objectMapper.readTree(response.body()).path("choices").path(0);
                JsonNode content = choice.path("message").path("content");
                if (!content.isTextual() || !StringUtils.hasText(content.asText())) {
                    throw new KimiClientException(
                            "KIMI_INVALID_RESPONSE",
                            "Kimi response content is missing",
                            502
                    );
                }
                JsonNode finishReason = choice.path("finish_reason");
                return new KimiChatResponse(
                        content.asText().strip(),
                        finishReason.isTextual() ? finishReason.asText().strip() : null
                );
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
                lastFailure = new KimiClientException(
                        "KIMI_UNAVAILABLE",
                        "Kimi transport failed (" + exception.getClass().getSimpleName() + ")",
                        502
                );
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

    private String resolveApiKey() {
        if (credentialService != null) {
            String stored = credentialService.activeApiKey();
            if (StringUtils.hasText(stored)) {
                return stored;
            }
        }
        return properties.getApiKey();
    }

    private void requireConfiguration(String model, String apiKey) {
        if (!StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(apiKey)
                || !StringUtils.hasText(model)) {
            throw new KimiClientException("KIMI_NOT_CONFIGURED", "Kimi is not configured", 503);
        }
    }

    private String normalizedBaseUrl() {
        return properties.getBaseUrl().strip().replaceAll("/+$", "");
    }

    private String describeHttpFailure(HttpResponse<String> response) {
        String suffix = "";
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode error = root.path("error");
            String providerCode = text(error, "code");
            if (!StringUtils.hasText(providerCode)) {
                providerCode = text(root, "code");
            }
            String providerMessage = text(error, "message");
            if (!StringUtils.hasText(providerMessage)) {
                providerMessage = text(root, "message");
            }
            if (StringUtils.hasText(providerCode) || StringUtils.hasText(providerMessage)) {
                String details = String.join("; ",
                        StringUtils.hasText(providerCode) ? "code=" + redact(providerCode) : "",
                        StringUtils.hasText(providerMessage) ? redact(providerMessage) : ""
                ).replaceAll("^; |; $", "");
                suffix = ": " + details;
            }
        } catch (Exception ignored) {
            // Keep the stable HTTP failure when the provider error body is not JSON.
        }
        return "Kimi returned HTTP " + response.statusCode() + suffix;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText().strip() : "";
    }

    private static String redact(String value) {
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        sanitized = sanitized.replaceAll("(?i)sk-[A-Za-z0-9_-]+", "[REDACTED]");
        sanitized = sanitized.replaceAll("(?i)(api[_ -]?key|authorization|bearer)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
        return sanitized.length() <= 160 ? sanitized : sanitized.substring(0, 160);
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
