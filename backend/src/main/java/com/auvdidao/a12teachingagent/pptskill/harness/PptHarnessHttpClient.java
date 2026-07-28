package com.auvdidao.a12teachingagent.pptskill.harness;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.pptskill.PptSkillGenerationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class PptHarnessHttpClient implements PptHarnessClient {
    private static final String JOBS_PATH = "/api/v1/presentation-jobs";
    private static final String TASK_ID_PATTERN = "^[0-9a-fA-F-]{36}$";

    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final ObjectMapper objectMapper;
    private final PptGeneratorProperties properties;

    public PptHarnessHttpClient(ObjectMapper objectMapper, PptGeneratorProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public PptHarnessDtos.JobResponse start(PptHarnessDtos.StartRequest request) {
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(uri(JOBS_PATH))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 202) {
                throw unavailable("PPT harness rejected the generation task", response.statusCode());
            }
            return objectMapper.readValue(response.body(), PptHarnessDtos.JobResponse.class);
        } catch (JsonProcessingException exception) {
            throw new PptSkillGenerationException("HARNESS_PROTOCOL_ERROR", "PPT harness returned an invalid task response", HttpStatus.BAD_GATEWAY, exception);
        } catch (IOException exception) {
            throw new PptSkillGenerationException("RUNNER_UNAVAILABLE", "PPT harness is unavailable", HttpStatus.SERVICE_UNAVAILABLE, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PptSkillGenerationException("RUNNER_TIMEOUT", "PPT harness request was interrupted", HttpStatus.GATEWAY_TIMEOUT, exception);
        }
    }

    @Override
    public PptHarnessDtos.JobResponse get(String taskId) {
        validateTaskId(taskId);
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(uri(JOBS_PATH + "/" + taskId))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new PptSkillGenerationException("HARNESS_TASK_NOT_FOUND", "PPT generation task was not found", HttpStatus.NOT_FOUND);
            }
            if (response.statusCode() != 200) {
                throw unavailable("PPT harness status request failed", response.statusCode());
            }
            return objectMapper.readValue(response.body(), PptHarnessDtos.JobResponse.class);
        } catch (JsonProcessingException exception) {
            throw new PptSkillGenerationException("HARNESS_PROTOCOL_ERROR", "PPT harness returned an invalid status response", HttpStatus.BAD_GATEWAY, exception);
        } catch (IOException exception) {
            throw new PptSkillGenerationException("RUNNER_UNAVAILABLE", "PPT harness is unavailable", HttpStatus.SERVICE_UNAVAILABLE, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PptSkillGenerationException("RUNNER_TIMEOUT", "PPT harness status request was interrupted", HttpStatus.GATEWAY_TIMEOUT, exception);
        }
    }

    @Override
    public PptHarnessDtos.QaReport qaReport(String taskId) {
        validateTaskId(taskId);
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(uri(JOBS_PATH + "/" + taskId + "/qa-report"))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new PptSkillGenerationException("PPT_QA_FAILED", "PPT harness quality report is unavailable", HttpStatus.BAD_GATEWAY);
            }
            return objectMapper.readValue(response.body(), PptHarnessDtos.QaReport.class);
        } catch (JsonProcessingException exception) {
            throw new PptSkillGenerationException("HARNESS_PROTOCOL_ERROR", "PPT harness returned an invalid QA response", HttpStatus.BAD_GATEWAY, exception);
        } catch (IOException exception) {
            throw new PptSkillGenerationException("RUNNER_UNAVAILABLE", "PPT harness is unavailable", HttpStatus.SERVICE_UNAVAILABLE, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PptSkillGenerationException("RUNNER_TIMEOUT", "PPT harness QA request was interrupted", HttpStatus.GATEWAY_TIMEOUT, exception);
        }
    }

    @Override
    public byte[] download(String taskId) {
        validateTaskId(taskId);
        try {
            HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder(uri(JOBS_PATH + "/" + taskId + "/artifact"))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new PptSkillGenerationException("PPT_DOWNLOAD_FAILED", "PPT harness artifact download failed", HttpStatus.BAD_GATEWAY);
            }
            return response.body();
        } catch (IOException exception) {
            throw new PptSkillGenerationException("PPT_DOWNLOAD_FAILED", "PPT harness artifact download failed", HttpStatus.BAD_GATEWAY, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PptSkillGenerationException("RUNNER_TIMEOUT", "PPT harness artifact download was interrupted", HttpStatus.GATEWAY_TIMEOUT, exception);
        }
    }

    @Override
    public String eventsUrl(String taskId) {
        validateTaskId(taskId);
        return uri(JOBS_PATH + "/" + taskId + "/events").toString();
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode listTemplates() {
        return getJson("/api/v1/presentation-templates");
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode getTemplate(String templateId, String version) {
        if (templateId == null || !templateId.matches("[A-Za-z0-9._-]{1,128}") || version == null || !version.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new PptSkillGenerationException("INVALID_TEMPLATE_SELECTION", "Presentation template reference is invalid", HttpStatus.BAD_REQUEST);
        }
        return getJson("/api/v1/presentation-templates/" + templateId + "?version=" + version);
    }

    private com.fasterxml.jackson.databind.JsonNode getJson(String path) {
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(uri(path))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds())).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new PptSkillGenerationException("TEMPLATE_NOT_FOUND", "Presentation template is not available", HttpStatus.NOT_FOUND);
            }
            if (response.statusCode() != 200) throw unavailable("PPT harness template request failed", response.statusCode());
            return objectMapper.readTree(response.body());
        } catch (JsonProcessingException exception) {
            throw new PptSkillGenerationException("HARNESS_PROTOCOL_ERROR", "PPT harness returned an invalid template response", HttpStatus.BAD_GATEWAY, exception);
        } catch (IOException exception) {
            throw new PptSkillGenerationException("RUNNER_UNAVAILABLE", "PPT harness is unavailable", HttpStatus.SERVICE_UNAVAILABLE, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PptSkillGenerationException("RUNNER_TIMEOUT", "PPT harness request was interrupted", HttpStatus.GATEWAY_TIMEOUT, exception);
        }
    }

    private URI uri(String path) {
        String base = properties.getHarnessBaseUrl();
        if (base == null || !base.matches("^https?://[A-Za-z0-9._:-]+(?:/.*)?$")) {
            throw new PptSkillGenerationException("HARNESS_CONFIGURATION_ERROR", "PPT harness URL is not configured safely", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return URI.create(base.replaceAll("/+$", "") + path);
    }

    private void validateTaskId(String taskId) {
        if (taskId == null || !taskId.matches(TASK_ID_PATTERN)) {
            throw new PptSkillGenerationException("HARNESS_TASK_INVALID", "PPT generation task id is invalid", HttpStatus.BAD_REQUEST);
        }
    }

    private PptSkillGenerationException unavailable(String message, int statusCode) {
        return new PptSkillGenerationException("RUNNER_UNAVAILABLE", message + " (HTTP " + statusCode + ")", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
