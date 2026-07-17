package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.WorkflowCode;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeSnippet;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryData;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class DifyWorkflowContractAdapter {

    private final ObjectMapper objectMapper;

    public DifyWorkflowContractAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode requestInput(WorkflowCode workflowCode, Object payload) {
        return requestInput(workflowCode, null, payload);
    }

    public JsonNode requestInput(WorkflowCode workflowCode, String operation, Object payload) {
        return switch (workflowCode) {
            case CLARIFICATION -> clarificationInput(payload);
            case REQUIREMENT_SUMMARY -> requirementSummaryInput(payload);
            case MATERIAL_ANALYSIS -> materialAnalysisInput(payload);
            case KNOWLEDGE_AND_TEACHING_INTENT -> knowledgeAndIntentInput(operation, payload);
            default -> objectMapper.valueToTree(payload);
        };
    }

    public ObjectNode responsePayload(WorkflowCode workflowCode, String operation, ObjectNode payload) {
        return switch (workflowCode) {
            case CLARIFICATION -> clarificationResponse(payload);
            case REQUIREMENT_SUMMARY -> requirementSummaryResponse(payload);
            case MATERIAL_ANALYSIS -> materialAnalysisResponse(payload);
            case KNOWLEDGE_AND_TEACHING_INTENT -> knowledgeAndIntentResponse(operation, payload);
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

    private JsonNode materialAnalysisInput(Object payload) {
        if (!(payload instanceof MaterialAnalysisRequest request)) {
            return objectMapper.valueToTree(payload);
        }

        ObjectNode input = objectMapper.createObjectNode();
        ObjectNode materialText = input.putObject("materialText");
        materialText.put("title", request.fileName());
        materialText.put("sourceName", request.fileName());
        materialText.put("materialType", request.materialType());
        materialText.put("content", defaultString(request.materialText()));
        materialText.put("pageMarkersPreserved", false);
        if (StringUtils.hasText(request.purpose())) {
            materialText.put("declaredPurpose", request.purpose().strip());
        }

        List<String> purposeTypes = request.purposeTypes();
        if ((purposeTypes == null || purposeTypes.isEmpty()) && StringUtils.hasText(request.purpose())) {
            purposeTypes = List.of(request.purpose().strip());
        }
        input.set("purposeTypes", objectMapper.valueToTree(purposeTypes == null ? List.of() : purposeTypes));
        input.set("courseContext", summaryNode(request.courseContext(), false));
        return input;
    }

    private JsonNode knowledgeAndIntentInput(String operation, Object payload) {
        if ("knowledge-retrieval".equals(operation) && payload instanceof KnowledgeRetrievalRequest request) {
            return knowledgeRetrievalInput(request);
        }
        if ("teaching-intent".equals(operation) && payload instanceof TeachingIntentRequest request) {
            return teachingIntentInput(request);
        }
        return objectMapper.valueToTree(payload);
    }

    private JsonNode knowledgeRetrievalInput(KnowledgeRetrievalRequest request) {
        ObjectNode input = objectMapper.createObjectNode();
        ObjectNode query = input.putObject("query");
        query.put("courseName", request.courseName());
        query.put("chapterTitle", request.chapterTopic());
        query.set("keywords", objectMapper.valueToTree(request.keywords()));

        ArrayNode candidates = input.putArray("knowledgeCandidates");
        int index = 1;
        for (KnowledgeSnippet snippet : request.candidateSnippets()) {
            if (snippet == null) {
                continue;
            }
            ObjectNode candidate = candidates.addObject();
            candidate.put("sourceId", "candidate-" + index++);
            candidate.put("sourceName", defaultString(snippet.sourceName()));
            candidate.put("title", defaultString(snippet.title()));
            candidate.put("content", defaultString(snippet.content()));
            candidate.put("relevance", snippet.score());
        }
        return input;
    }

    private JsonNode teachingIntentInput(TeachingIntentRequest request) {
        ObjectNode input = objectMapper.createObjectNode();
        ObjectNode projectInfo = input.putObject("projectInfo");
        projectInfo.put("projectRef", String.valueOf(request.projectId()));
        input.set("requirementSummary", summaryNode(request.requirementSummary(), true));

        ArrayNode materialSummaries = input.putArray("materialSummaries");
        LinkedHashSet<String> materialSources = new LinkedHashSet<>();
        for (KnowledgeSnippet snippet : safeSnippets(request.knowledgeSnippets())) {
            String sourceName = defaultString(snippet.sourceName());
            if (!StringUtils.hasText(sourceName) || !materialSources.add(sourceName)) {
                continue;
            }
            ObjectNode material = materialSummaries.addObject();
            material.put("sourceId", "material-" + materialSources.size());
            material.put("title", StringUtils.hasText(snippet.title()) ? snippet.title().strip() : sourceName);
            material.put("confirmed", true);
            material.put("summary", abbreviate(defaultString(snippet.content()), 600));
        }

        ArrayNode knowledgeSnippets = input.putArray("knowledgeSnippets");
        int index = 1;
        for (KnowledgeSnippet snippet : safeSnippets(request.knowledgeSnippets())) {
            ObjectNode knowledge = knowledgeSnippets.addObject();
            knowledge.put("sourceId", "knowledge-" + index++);
            knowledge.put("sourceName", defaultString(snippet.sourceName()));
            knowledge.put("title", defaultString(snippet.title()));
            knowledge.put("content", defaultString(snippet.content()));
            knowledge.put("relevance", snippet.score());
        }
        return input;
    }

    private ObjectNode summaryNode(RequirementSummaryData summary, boolean confirmed) {
        ObjectNode node = objectMapper.createObjectNode();
        if (confirmed) {
            node.put("status", "CONFIRMED");
        }
        if (summary == null) {
            return node;
        }
        putIfText(node, "courseName", summary.courseName());
        putIfText(node, "chapterTitle", summary.chapterTopic());
        putIfText(node, "targetStudents", summary.targetAudience());
        if (summary.lessonDurationMinutes() != null) {
            node.put("lessonDuration", summary.lessonDurationMinutes());
        }
        node.set("teachingGoals", objectMapper.valueToTree(safeStrings(summary.teachingGoals())));
        node.set("keyDifficulties", objectMapper.valueToTree(safeStrings(summary.keyDifficulties())));
        node.set("outputTypes", objectMapper.valueToTree(safeStrings(summary.outputTypes())));
        putIfText(node, "coursewareStyle", summary.coursewareStyle());
        putIfText(node, "interactionType", summary.interactionType());
        return node;
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

    private ObjectNode materialAnalysisResponse(ObjectNode payload) {
        if (payload.has("status") && payload.has("summary")) {
            return payload.deepCopy();
        }
        JsonNode materialSummary = payload.get("materialSummary");
        if (materialSummary == null || !materialSummary.isObject()) {
            return payload.deepCopy();
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "PARSED");
        String overview = materialSummary.path("overview").asText("").strip();
        List<String> riskNotes = textValues(payload.get("riskNotes"));
        response.put(
                "summary",
                riskNotes.isEmpty() ? overview : overview + " 风险提示：" + String.join("；", riskNotes)
        );
        response.set("keywords", arrayCopy(materialSummary.get("keywords")));

        LinkedHashSet<String> teachingUses = new LinkedHashSet<>();
        LinkedHashSet<String> chunks = new LinkedHashSet<>();
        JsonNode fragments = payload.get("usableFragments");
        if (fragments != null && fragments.isArray()) {
            for (JsonNode fragment : fragments) {
                teachingUses.addAll(textValues(fragment.get("purposeTypes")));
                String content = fragment.path("content").asText("").strip();
                if (StringUtils.hasText(content)) {
                    chunks.add(content);
                }
            }
        }
        response.set("teachingUses", objectMapper.valueToTree(teachingUses));
        response.set("suggestedChunks", objectMapper.valueToTree(chunks));
        return response;
    }

    private ObjectNode knowledgeAndIntentResponse(String operation, ObjectNode payload) {
        if ("knowledge-retrieval".equals(operation)) {
            return knowledgeRetrievalResponse(payload);
        }
        if ("teaching-intent".equals(operation)) {
            return teachingIntentResponse(payload);
        }
        return payload.deepCopy();
    }

    private ObjectNode knowledgeRetrievalResponse(ObjectNode payload) {
        if (payload.has("snippets") && payload.has("retrievalNote")) {
            return payload.deepCopy();
        }
        JsonNode retrieval = payload.get("knowledgeRetrieval");
        if (retrieval == null || !retrieval.isObject()) {
            return payload.deepCopy();
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode snippets = response.putArray("snippets");
        JsonNode contractSnippets = retrieval.get("snippets");
        if (contractSnippets != null && contractSnippets.isArray()) {
            for (JsonNode source : contractSnippets) {
                ObjectNode snippet = snippets.addObject();
                copy(snippet, "title", source.get("title"));
                copy(snippet, "sourceName", source.get("sourceName"));
                copy(snippet, "content", source.get("content"));
                JsonNode relevance = source.get("relevance");
                snippet.put("score", relevance != null && relevance.isNumber() ? relevance.asDouble() : 0D);
            }
        }
        String note = retrieval.path("retrievalNote").asText("").strip();
        response.put(
                "retrievalNote",
                StringUtils.hasText(note)
                        ? note
                        : snippets.isEmpty() ? "没有可用的本地知识候选。" : "已按相关性重排本地知识候选。"
        );
        return response;
    }

    private ObjectNode teachingIntentResponse(ObjectNode payload) {
        if (payload.has("intentId") && payload.has("generationGoals")) {
            return payload.deepCopy();
        }
        JsonNode intent = payload.get("teachingIntent");
        if (intent == null || !intent.isObject()) {
            return payload.deepCopy();
        }

        ObjectNode response = objectMapper.createObjectNode();
        copy(response, "intentId", payload.get("intentId"));
        response.set("generationGoals", arrayCopy(intent.get("teachingGoals")));

        ArrayNode contentBasis = response.putArray("contentBasis");
        appendTextValues(contentBasis, intent.get("contentPriorities"), "");
        appendTextValues(contentBasis, intent.get("teachingOrganization"), "教学组织：");
        response.set("interactionIdeas", arrayCopy(intent.get("interactionPlan")));
        response.set("outputTypes", arrayCopy(intent.get("outputTypes")));

        List<String> conflicts = textValues(payload.get("conflictWarnings"));
        response.put(
                "confirmationPrompt",
                conflicts.isEmpty()
                        ? "请确认以上教学意图是否准确。"
                        : "请确认教学意图，并处理以下冲突：" + String.join("；", conflicts)
        );
        return response;
    }

    private ArrayNode arrayOrEmpty(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : objectMapper.createArrayNode();
    }

    private ArrayNode arrayCopy(JsonNode value) {
        return value != null && value.isArray()
                ? ((ArrayNode) value).deepCopy()
                : objectMapper.createArrayNode();
    }

    private List<String> textValues(JsonNode value) {
        if (value == null || !value.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (item.isTextual() && StringUtils.hasText(item.asText())) {
                values.add(item.asText().strip());
            }
        }
        return List.copyOf(values);
    }

    private void appendTextValues(ArrayNode target, JsonNode values, String prefix) {
        for (String value : textValues(values)) {
            target.add(prefix + value);
        }
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

    private static List<String> safeStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::strip)
                .distinct()
                .toList();
    }

    private static List<KnowledgeSnippet> safeSnippets(List<KnowledgeSnippet> snippets) {
        return snippets == null ? List.of() : snippets.stream().filter(java.util.Objects::nonNull).toList();
    }

    private static String defaultString(String value) {
        return value == null ? "" : value.strip();
    }

    private static String abbreviate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private static void putIfText(ObjectNode target, String field, String value) {
        if (StringUtils.hasText(value)) {
            target.put(field, value.strip());
        }
    }

    private static void copy(ObjectNode target, String targetField, JsonNode value) {
        if (value != null && !value.isNull()) {
            target.set(targetField, value.deepCopy());
        }
    }
}
