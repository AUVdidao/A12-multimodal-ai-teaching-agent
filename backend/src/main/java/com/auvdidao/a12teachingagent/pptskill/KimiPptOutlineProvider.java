package com.auvdidao.a12teachingagent.pptskill;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class KimiPptOutlineProvider implements PptOutlineProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(KimiPptOutlineProvider.class);

    private static final String SYSTEM_PROMPT = """
            You are the A12 teaching-courseware outline planner. Return only one JSON object that conforms to the supplied JSON Schema.
            Do not return Markdown fences, explanations, revision notes, prompts, workflow logs, internal reasoning, or external links.
            Write all learner-facing slide text in Simplified Chinese by default.
            Produce exactly eight slides in this instructional sequence: hook, learning objectives, core knowledge, inquiry activity,
            formative assessment, evidence comparison, summary, and homework. To pass the automated layout gate, use this exact stable
            sequence of slide type and variant: title; content/standard; content/cards-3; content/split; content/timeline;
            content/chart; content/comparison-2col; content/cards-3. Do not add visual_strategy, content, generated_image, image,
            hero_image, diagram, logo, mermaid_source, table, image-sidebar, flow, or any unrecognised field. Use only native PowerPoint
            shapes and the fields in the supplied schema. Keep titles to 20 Chinese characters or fewer, subtitles to 28 characters or
            fewer, bullets to four items of 28 characters or fewer, and highlights to two items of 28 characters or fewer. A card body
            must be 44 Chinese characters or fewer; a milestone body must be 36 Chinese characters or fewer; comparison columns and chart
            captions must be concise. Do not use the same variant on consecutive slides and do not turn every slide into a bullet list.
            Variant field contracts are strict: for split, populate bullets (three or four concise left-column bullet strings) and highlights
            (two concise right-panel strings); never use left or right for split. For comparison-2col, populate left and right only, each with
            a title and three or four bullets. For cards-3, populate exactly three cards. For timeline, populate three or four milestones.
            For chart, populate one numerical series with matching labels and values plus a concise caption. Do not use fields that the selected
            variant does not render.
            Do not output file://, http://, or https:// values. Every statement must be suitable for teacher review.
            """;

    private final ObjectMapper objectMapper;
    private final PptGeneratorProperties properties;
    private final PptOutlineSchemaValidator schemaValidator;
    private final TeachingIntentRepository intentRepository;
    private final GenerationPlanRepository planRepository;
    private final HttpClient httpClient;

    @Autowired
    public KimiPptOutlineProvider(ObjectMapper objectMapper, PptGeneratorProperties properties,
                                  PptOutlineSchemaValidator schemaValidator, TeachingIntentRepository intentRepository,
                                  GenerationPlanRepository planRepository) {
        this(objectMapper, properties, schemaValidator, intentRepository, planRepository,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    KimiPptOutlineProvider(ObjectMapper objectMapper, PptGeneratorProperties properties,
                            PptOutlineSchemaValidator schemaValidator, TeachingIntentRepository intentRepository,
                            GenerationPlanRepository planRepository, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.schemaValidator = schemaValidator;
        this.intentRepository = intentRepository;
        this.planRepository = planRepository;
        this.httpClient = httpClient;
    }

    @Override
    public String providerId() {
        return "KIMI";
    }

    @Override
    public JsonNode getOutline(Project project) {
        requireConfiguration();
        String projectContext = serialize(projectContext(project));
        String firstResponse = requestOutline(projectContext, null);
        try {
            return parseAndValidate(firstResponse);
        } catch (PptSkillGenerationException firstFailure) {
            return parseAndValidate(requestOutline(projectContext, repairInstruction(firstResponse, firstFailure.getMessage())));
        }
    }

    private String requestOutline(String projectContext, String repairInstruction) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", properties.getKimiModel());
            // Kimi K2.6 accepts the documented chat-completions fields below.
            // Do not send preview-only thinking or custom temperature parameters.
            body.put("max_tokens", properties.getKimiMaxCompletionTokens());
            body.set("response_format", responseFormat());
            var messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
            messages.addObject().put("role", "user").put("content",
                    "The exact JSON Schema you must follow is:\n" + objectMapper.writeValueAsString(schemaValidator.schemaForModel()));
            messages.addObject().put("role", "user").put("content", projectContext);
            if (repairInstruction != null) {
                messages.addObject().put("role", "user").put("content", repairInstruction);
            }

            HttpRequest request = HttpRequest.newBuilder(kimiUri())
                    .timeout(Duration.ofSeconds(properties.getKimiTimeoutSeconds()))
                    .header("Authorization", "Bearer " + properties.getKimiApiKey().trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("Kimi PPT outline request was rejected with HTTP status {}", response.statusCode());
                throw new PptSkillGenerationException(
                        "KIMI_REQUEST_FAILED",
                        "Kimi PPT outline request was rejected (HTTP " + response.statusCode() + ")",
                        HttpStatus.BAD_GATEWAY
                );
            }
            JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new PptSkillGenerationException("INVALID_OUTLINE", "Kimi returned an empty PPT outline", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return content.asText();
        } catch (PptSkillGenerationException exception) {
            throw exception;
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new PptSkillGenerationException("KIMI_TIMEOUT", "Kimi PPT outline generation timed out", HttpStatus.GATEWAY_TIMEOUT, exception);
        } catch (ConnectException exception) {
            throw new PptSkillGenerationException("KIMI_UNAVAILABLE", "Kimi PPT outline service is unavailable", HttpStatus.BAD_GATEWAY, exception);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PptSkillGenerationException("KIMI_REQUEST_FAILED", "Kimi PPT outline generation failed", HttpStatus.BAD_GATEWAY, exception);
        }
    }

    private JsonNode parseAndValidate(String content) {
        try {
            JsonNode outline = objectMapper.readTree(content);
            if (outline == null || !outline.isObject()) {
                throw new PptSkillGenerationException("INVALID_OUTLINE", "Kimi returned a non-object PPT outline", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            schemaValidator.validate(outline);
            return outline;
        } catch (JsonProcessingException exception) {
            throw new PptSkillGenerationException("INVALID_OUTLINE", "Kimi returned malformed PPT outline JSON", HttpStatus.UNPROCESSABLE_ENTITY, exception);
        }
    }

    private JsonNode responseFormat() {
        ObjectNode responseFormat = objectMapper.createObjectNode();
        // Kimi exposes JSON mode as json_object; strict schema validation remains local.
        responseFormat.put("type", "json_object");
        return responseFormat;
    }

    private Map<String, Object> projectContext(Project project) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("instruction", "Generate a presentation-skill Phase-1 outline JSON from this controlled project context.");
        context.put("projectName", firstNonBlank(project.getProjectName(), project.getCourseName(), "Untitled teaching project"));
        context.put("courseName", blankToNull(project.getCourseName()));
        context.put("chapterTopic", blankToNull(project.getChapterTopic()));
        context.put("targetAudience", blankToNull(project.getTargetAudience()));
        context.put("lessonDurationMinutes", project.getLessonDurationMinutes());
        context.put("projectDescription", clamp(project.getProjectDescription(), 3000));
        context.put("generationMode", project.getGenerationMode() == null ? null : project.getGenerationMode().name());

        intentRepository.findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(
                project.getId(), TeachingIntentStatus.CONFIRMED).ifPresent(intent -> context.put("confirmedTeachingIntent", intentContext(intent)));
        planRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId()).ifPresent(plan -> {
            if (plan.getPptOutline() != null && !plan.getPptOutline().isBlank()) {
                context.put("existingPptPlan", clamp(plan.getPptOutline(), 4000));
            }
        });
        return context;
    }

    private static Map<String, Object> intentContext(TeachingIntent intent) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("generationGoals", intent.getGenerationGoals());
        context.put("contentBasis", blankToNull(intent.getContentBasis()));
        context.put("primaryBasis", blankToNull(intent.getPrimaryBasis()));
        context.put("teachingApproach", blankToNull(intent.getTeachingApproach()));
        context.put("interactionMode", blankToNull(intent.getInteractionMode()));
        context.put("targetAudience", blankToNull(intent.getTargetAudience()));
        context.put("totalHours", intent.getTotalHours());
        context.put("teachingFormat", blankToNull(intent.getTeachingFormat()));
        context.put("outputTypes", intent.getOutputTypes());
        context.put("stylePreference", blankToNull(intent.getStylePreference()));
        context.put("notes", clamp(intent.getNotes(), 2000));
        return context;
    }

    private String repairInstruction(String invalidResponse, String validationMessage) {
        return "The previous response was invalid. Return only one complete JSON object that conforms to the supplied schema. Validation error: "
                + clamp(validationMessage, 800) + ". Invalid response: " + clamp(invalidResponse, 6000);
    }

    private void requireConfiguration() {
        if (properties.getKimiApiKey() == null || properties.getKimiApiKey().isBlank()) {
            throw new PptSkillGenerationException("KIMI_CONFIG_MISSING", "Kimi PPT outline API key is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (properties.getKimiBaseUrl() == null || properties.getKimiBaseUrl().isBlank()
                || properties.getKimiModel() == null || properties.getKimiModel().isBlank()) {
            throw new PptSkillGenerationException("KIMI_CONFIG_MISSING", "Kimi PPT outline configuration is incomplete", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private URI kimiUri() {
        return URI.create(properties.getKimiBaseUrl().replaceAll("/$", "") + "/chat/completions");
    }

    private String serialize(Map<String, Object> context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exception) {
            throw new PptSkillGenerationException("INVALID_OUTLINE", "PPT project context could not be serialized", HttpStatus.INTERNAL_SERVER_ERROR, exception);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clamp(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= max ? value.trim() : value.substring(0, max);
    }
}
