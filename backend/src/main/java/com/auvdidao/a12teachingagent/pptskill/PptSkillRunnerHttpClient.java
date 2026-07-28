package com.auvdidao.a12teachingagent.pptskill;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class PptSkillRunnerHttpClient implements PptSkillRunnerClient {

    private static final String GENERATION_PATH = "/internal/ppt-skill/v1/generations";
    private static final Logger LOGGER = LoggerFactory.getLogger(PptSkillRunnerHttpClient.class);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PptGeneratorProperties properties;

    public PptSkillRunnerHttpClient(ObjectMapper objectMapper, PptGeneratorProperties properties) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public PptSkillRunnerDtos.RunnerResult generate(JsonNode outline, String stylePreset) {
        long started = System.nanoTime();
        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "outline", outline,
                    "stylePreset", stylePreset
            ));
            HttpResponse<String> generation = send(
                    HttpRequest.newBuilder(uri(GENERATION_PATH))
                            .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (generation.statusCode() < 200 || generation.statusCode() >= 300) {
                logRunnerFailure(generation);
                throw failure("PPT_BUILD_FAILED", "The PPT skill runner rejected the generation request", null);
            }
            JsonNode result = objectMapper.readTree(generation.body());
            String jobId = text(result, "jobId");
            if (!"SUCCEEDED".equals(text(result, "status")) || jobId == null) {
                throw failure("PPT_BUILD_FAILED", "The PPT skill runner did not complete the generation", null);
            }

            byte[] pptx = download(jobId, "presentation.pptx");
            String outlineJson = new String(download(jobId, "outline.json"), java.nio.charset.StandardCharsets.UTF_8);
            String qaReportJson = new String(download(jobId, "qa-report.json"), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode qa = result.path("qa");
            Map<String, Object> report = objectMapper.convertValue(qa.path("report"), new TypeReference<>() {});
            return new PptSkillRunnerDtos.RunnerResult(
                    jobId,
                    text(result, "status"),
                    text(result, "fileName"),
                    result.path("sizeBytes").asLong(),
                    text(result, "sha256"),
                    new PptSkillRunnerDtos.RunnerQa(qa.path("passed").asBoolean(), text(qa, "qaLevel"), report),
                    result.path("buildDurationMs").asLong(),
                    result.path("qaDurationMs").asLong(),
                    result.path("totalDurationMs").asLong((System.nanoTime() - started) / 1_000_000),
                    pptx,
                    outlineJson,
                    qaReportJson
            );
        } catch (PptSkillGenerationException exception) {
            throw exception;
        } catch (java.net.http.HttpTimeoutException exception) {
            throw failure("RUNNER_TIMEOUT", "PPT generation exceeded the configured runner timeout", exception);
        } catch (java.net.ConnectException exception) {
            throw failure("RUNNER_UNAVAILABLE", "PPT skill runner is unavailable", exception);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw failure("PPT_DOWNLOAD_FAILED", "PPT skill runner communication failed", exception);
        }
    }

    private byte[] download(String jobId, String fileName) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(uri("/internal/ppt-skill/v1/jobs/" + jobId + "/" + fileName))
                        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure("PPT_DOWNLOAD_FAILED", "PPT skill runner output could not be downloaded", null);
        }
        return response.body();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return httpClient.send(request, handler);
    }

    private URI uri(String path) {
        return URI.create(properties.getRunnerBaseUrl().replaceAll("/$", "") + path);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void logRunnerFailure(HttpResponse<String> response) {
        try {
            JsonNode error = objectMapper.readTree(response.body());
            String code = text(error, "code");
            String details = error.path("details").toString().replaceAll("[\\r\\n]+", " ");
            LOGGER.warn("PPT skill runner rejected generation: status={}, code={}, details={}",
                    response.statusCode(), code, details.substring(0, Math.min(details.length(), 1200)));
        } catch (Exception ignored) {
            LOGGER.warn("PPT skill runner rejected generation: status={}", response.statusCode());
        }
    }

    private static PptSkillGenerationException failure(String code, String message, Throwable cause) {
        return new PptSkillGenerationException(code, message, org.springframework.http.HttpStatus.BAD_GATEWAY, cause);
    }
}
