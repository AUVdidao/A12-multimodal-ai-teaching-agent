package com.auvdidao.a12teachingagent.template;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Java-side contract client for the Go-owned model execution boundary. It
 * carries only a short-lived lease and a non-secret preview manifest; it never
 * resolves, stores, or forwards a teacher model credential.
 */
@Component
public final class LessonForgeModelExecutionClient {
    private final RestClient client;
    private final String bearerToken;

    @Autowired
    public LessonForgeModelExecutionClient(
            @Value("${a12.lessonforge.model-execution.base-url:http://server:8090}") String baseUrl,
            @Value("${a12.lessonforge.model-execution.bearer-token:}") String bearerToken,
            @Value("${a12.lessonforge.model-execution.timeout-ms:120000}") long timeoutMs
    ) {
        if (baseUrl == null || baseUrl.isBlank() || timeoutMs < 1 || timeoutMs > 120_000) {
            throw new IllegalArgumentException("LessonForge model execution bridge configuration is invalid");
        }
        Duration timeout = Duration.ofMillis(timeoutMs);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl.strip()).requestFactory(factory).build();
        this.bearerToken = bearerToken == null ? "" : bearerToken.strip();
    }

    public LeaseResponse createLease(LeaseRequest request) {
        try {
            LeaseResponse response = client.post()
                    .uri("/internal/model-execution-leases")
                    .header("Authorization", "Bearer " + bearerToken)
                    .body(request)
                    .retrieve()
                    .body(LeaseResponse.class);
            if (response == null || response.leaseId() == null || response.leaseId().isBlank()
                    || response.nonce() == null || response.nonce().isBlank()) {
                throw new ModelExecutionException("MODEL_EXECUTION_LEASE_RESPONSE_INVALID");
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new ModelExecutionException("MODEL_EXECUTION_BRIDGE_HTTP_" + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new ModelExecutionException("MODEL_EXECUTION_BRIDGE_UNAVAILABLE");
        }
    }

    public ExecutionResponse execute(ExecutionRequest request) {
        try {
            ExecutionResponse response = client.post()
                    .uri("/internal/model-executions/multimodal")
                    .header("Authorization", "Bearer " + bearerToken)
                    .body(request)
                    .retrieve()
                    .body(ExecutionResponse.class);
            if (response == null || response.content() == null || response.provider() == null
                    || response.provider().isBlank() || response.modelId() == null || response.modelId().isBlank()) {
                throw new ModelExecutionException("MODEL_EXECUTION_RESPONSE_INVALID");
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new ModelExecutionException("MODEL_EXECUTION_BRIDGE_HTTP_" + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new ModelExecutionException("MODEL_EXECUTION_BRIDGE_UNAVAILABLE");
        }
    }

    public record LeaseRequest(
            long ownerUserId,
            long missionId,
            long missionFileId,
            String purpose,
            String analysisRunId,
            long sourceVersionId,
            long renderedSlideSetId,
            long processingRunId,
            String sourceSha256,
            String inputSha256,
            String promptSha256,
            String previewStorageKey,
            String previewSha256,
            long previewSizeBytes,
            String previewMediaType,
            String nonce
    ) { }

    public record LeaseResponse(
            String leaseId,
            String nonce,
            String expiresAt,
            String analysisRunId,
            Long modelConnectionId,
            String provider,
            String modelId
    ) { }

    public record ExecutionRequest(
            String leaseId,
            String nonce,
            String prompt,
            String inputSha256,
            String promptSha256,
            int maxCompletionTokens,
            long timeoutMs
    ) { }

    public record ExecutionResponse(
            String requestId,
            String analysisRunId,
            Long modelConnectionId,
            String provider,
            String modelId,
            String content,
            String inputSha256,
            String previewSha256
    ) { }

    public static final class ModelExecutionException extends RuntimeException {
        public ModelExecutionException(String code) {
            super(code);
        }
    }
}
