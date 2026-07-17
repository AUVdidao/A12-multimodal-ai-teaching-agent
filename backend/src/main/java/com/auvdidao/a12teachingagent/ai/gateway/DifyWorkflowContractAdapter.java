package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.WorkflowCode;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DifyWorkflowContractAdapter {

    private final ObjectMapper objectMapper;

    public DifyWorkflowContractAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode requestInput(WorkflowCode workflowCode, Object payload) {
        return switch (workflowCode) {
            case CLARIFICATION -> clarificationInput(payload);
            case REQUIREMENT_SUMMARY -> requirementSummaryInput(payload);
            default -> objectMapper.valueToTree(payload);
        };
    }

    public ObjectNode responsePayload(WorkflowCode workflowCode, String operation, ObjectNode payload) {
        return switch (workflowCode) {
            case CLARIFICATION -> clarificationResponse(payload);
            case REQUIREMENT_SUMMARY -> requirementSummaryResponse(payload);
            default -> payload.deepCopy();
        };
    }

    private JsonNode clarificationInput(Object payload) {
        if (!(payload instanceof ClarificationRequest request)) {
            return objectMapper.valueToTree(payload);
        }

        ObjectNode input = objectMapper.createObjectNode();
        ObjectNode projectInfo = input.putObject("projectInfo");
        projectInfo.put("projectRef", String.valueOf(request.projectId()));
        projectInfo.putNull("courseName");
        projectInfo.putNull("chapterTitle");
        projectInfo.putNull("targetStudents");
        projectInfo.putNull("lessonDuration");
        projectInfo.put(
                "generationMode",
                request.generationMode() == null ? "STANDARD" : request.generationMode().name()
        );
        input.put("rawRequirement", request.rawRequirement());
        input.set("knownFields", objectMapper.valueToTree(request.knownFields()));
        input.set("requestedMissingFields", objectMapper.valueToTree(request.requestedMissingFields()));
        input.putArray("dialogHistory");
        return input;
    }

    private JsonNode requirementSummaryInput(Object payload) {
        if (!(payload instanceof RequirementSummaryRequest request)) {
            return objectMapper.valueToTree(payload);
        }

        ObjectNode input = objectMapper.createObjectNode();
        ObjectNode projectInfo = input.putObject("projectInfo");
        projectInfo.put("projectRef", String.valueOf(request.projectId()));
        projectInfo.putNull("courseName");
        projectInfo.putNull("chapterTitle");
        projectInfo.putNull("targetStudents");
        projectInfo.putNull("lessonDuration");
        projectInfo.put(
                "generationMode",
                request.generationMode() == null ? "STANDARD" : request.generationMode().name()
        );
        input.put("rawRequirement", request.rawRequirement());
        input.set("dialogHistory", objectMapper.valueToTree(request.dialogTurns()));
        ObjectNode defaults = input.putObject("defaultValues");
        ObjectNode style = defaults.putObject("coursewareStyle");
        style.put("value", "CLEAR_VISUAL");
        style.put("source", "system-default");
        return input;
    }

    private ObjectNode clarificationResponse(ObjectNode payload) {
        if (payload.has("suggestedFields") && payload.has("nextAction")) {
            return payload.deepCopy();
        }
        if (!payload.has("recognizedFields") || !payload.has("questions") || !payload.has("canContinue")) {
            return payload.deepCopy();
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode missingFields = arrayOrEmpty(payload.get("missingFields"));
        response.set("missingFields", missingFields.deepCopy());

        Map<String, String> questionsByField = new LinkedHashMap<>();
        ArrayNode positionalQuestions = objectMapper.createArrayNode();
        JsonNode questions = payload.get("questions");
        if (questions != null && questions.isArray()) {
            for (JsonNode question : questions) {
                if (question.isObject()) {
                    String field = question.path("field").asText();
                    String text = question.path("questionText").asText();
                    if (StringUtils.hasText(field) && StringUtils.hasText(text)) {
                        questionsByField.putIfAbsent(field, text.strip());
                    }
                } else if (question.isTextual() && StringUtils.hasText(question.asText())) {
                    positionalQuestions.add(question.asText().strip());
                }
            }
        }

        ArrayNode questionTexts = response.putArray("questions");
        int positionalIndex = 0;
        for (JsonNode missingField : missingFields) {
            String question = questionsByField.get(missingField.asText());
            if (!StringUtils.hasText(question) && positionalIndex < positionalQuestions.size()) {
                question = positionalQuestions.get(positionalIndex++).asText();
            }
            if (StringUtils.hasText(question)) {
                questionTexts.add(question);
            }
        }

        ObjectNode suggestedFields = response.putObject("suggestedFields");
        JsonNode recognizedFields = payload.get("recognizedFields");
        if (recognizedFields != null && recognizedFields.isObject()) {
            recognizedFields.fields().forEachRemaining(entry ->
                    suggestedFields.put(entry.getKey(), displayValue(entry.getValue()))
            );
        }
        boolean canContinue = payload.path("canContinue").asBoolean(missingFields.isEmpty());
        response.put("nextAction", canContinue ? "CONTINUE_TO_SUMMARY" : "ANSWER_CLARIFICATION_QUESTIONS");
        return response;
    }

    private ObjectNode requirementSummaryResponse(ObjectNode payload) {
        if (payload.has("summary")) {
            return payload.deepCopy();
        }

        JsonNode contractSummary = payload.get("requirementSummary");
        if (contractSummary == null || !contractSummary.isObject()) {
            return payload.deepCopy();
        }

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode summary = response.putObject("summary");
        copy(summary, "courseName", contractSummary.get("courseName"));
        copy(summary, "chapterTopic", contractSummary.get("chapterTitle"));
        copy(summary, "targetAudience", contractSummary.get("targetStudents"));
        copy(summary, "lessonDurationMinutes", contractSummary.get("lessonDuration"));
        copy(summary, "teachingGoals", contractSummary.get("teachingGoals"));
        copy(summary, "keyDifficulties", contractSummary.get("keyDifficulties"));
        copy(summary, "outputTypes", contractSummary.get("outputTypes"));
        copy(summary, "coursewareStyle", contractSummary.get("coursewareStyle"));
        copy(summary, "interactionType", contractSummary.get("interactionType"));

        ArrayNode assumptions = response.putArray("assumptions");
        appendDisplayValues(assumptions, payload.get("uncertainFields"), "待确认：");
        appendDisplayValues(assumptions, payload.get("generationHints"), "生成提示：");
        response.put(
                "confirmationQuestion",
                assumptions.isEmpty()
                        ? "请确认以上教学需求摘要是否准确。"
                        : "请确认摘要，并检查仍待确认的字段与生成提示。"
        );
        return response;
    }

    private ArrayNode arrayOrEmpty(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : objectMapper.createArrayNode();
    }

    private void appendDisplayValues(ArrayNode target, JsonNode values, String prefix) {
        if (values == null || !values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            String displayed = displayValue(value);
            if (StringUtils.hasText(displayed)) {
                target.add(prefix + displayed);
            }
        }
    }

    private String displayValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isValueNode()) {
            return value.asText();
        }
        if (value.isArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode item : value) {
                String displayed = displayValue(item);
                if (!StringUtils.hasText(displayed)) {
                    continue;
                }
                if (!joined.isEmpty()) {
                    joined.append("；");
                }
                joined.append(displayed);
            }
            return joined.toString();
        }
        return value.toString();
    }

    private static void copy(ObjectNode target, String targetField, JsonNode value) {
        if (value != null && !value.isNull()) {
            target.set(targetField, value.deepCopy());
        }
    }
}
