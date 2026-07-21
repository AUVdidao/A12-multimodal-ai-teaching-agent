package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.WorkflowCode;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationConstraints;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanSnapshot;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeSnippet;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.PlanSection;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryData;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RevisionRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.StructuredContentRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
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
            case GENERATION_PLAN -> generationPlanInput(payload);
            case CONTENT_DRAFT -> structuredContentInput(payload);
            case REVISION -> revisionInput(payload);
        };
    }

    public ObjectNode responsePayload(WorkflowCode workflowCode, String operation, ObjectNode payload) {
        return switch (workflowCode) {
            case CLARIFICATION -> clarificationResponse(payload);
            case REQUIREMENT_SUMMARY -> requirementSummaryResponse(payload);
            case MATERIAL_ANALYSIS -> materialAnalysisResponse(payload);
            case KNOWLEDGE_AND_TEACHING_INTENT -> knowledgeAndIntentResponse(operation, payload);
            case GENERATION_PLAN -> generationPlanResponse(payload);
            case CONTENT_DRAFT -> structuredContentResponse(payload);
            case REVISION -> revisionResponse(payload);
        };
    }

    private JsonNode clarificationInput(Object payload) {
        if (!(payload instanceof ClarificationRequest request)) {
            return objectMapper.valueToTree(payload);
        }

        ObjectNode input = objectMapper.createObjectNode();
        ObjectNode projectInfo = input.putObject("projectInfo");
        writeProjectInfo(projectInfo, request.projectId(), request.generationMode(), request.projectContext());
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
        writeProjectInfo(projectInfo, request.projectId(), request.generationMode(), request.projectContext());
        input.put("rawRequirement", request.rawRequirement());
        input.set("dialogHistory", objectMapper.valueToTree(request.dialogTurns()));
        ObjectNode defaults = input.putObject("defaultValues");
        RequirementSummaryData context = request.projectContext();
        putSourcedDefault(defaults, "teachingGoals", context == null ? null : context.teachingGoals(), "teacher-structured-input");
        putSourcedDefault(defaults, "keyDifficulties", context == null ? null : context.keyDifficulties(), "teacher-structured-input");
        putSourcedDefault(defaults, "outputTypes", context == null ? null : context.outputTypes(), "teacher-structured-input");
        putSourcedDefault(defaults, "interactionType", context == null ? null : context.interactionType(), "teacher-structured-input");
        String styleValue = context == null ? null : context.coursewareStyle();
        putSourcedDefault(
                defaults,
                "coursewareStyle",
                StringUtils.hasText(styleValue) ? styleValue.strip() : "CLEAR_VISUAL",
                StringUtils.hasText(styleValue) ? "teacher-structured-input" : "system-default"
        );
        return input;
    }

    private void writeProjectInfo(
            ObjectNode projectInfo,
            Long projectId,
            com.auvdidao.a12teachingagent.domain.common.GenerationMode generationMode,
            RequirementSummaryData context
    ) {
        projectInfo.put("projectRef", String.valueOf(projectId));
        putOrNull(projectInfo, "courseName", context == null ? null : context.courseName());
        putOrNull(projectInfo, "chapterTitle", context == null ? null : context.chapterTopic());
        putOrNull(projectInfo, "targetStudents", context == null ? null : context.targetAudience());
        if (context == null || context.lessonDurationMinutes() == null) {
            projectInfo.putNull("lessonDuration");
        } else {
            projectInfo.put("lessonDuration", context.lessonDurationMinutes());
        }
        projectInfo.put("generationMode", generationMode == null ? "STANDARD" : generationMode.name());
    }

    private void putSourcedDefault(ObjectNode defaults, String field, Object value, String source) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && !StringUtils.hasText(text)) {
            return;
        }
        if (value instanceof List<?> values && values.isEmpty()) {
            return;
        }
        ObjectNode entry = defaults.putObject(field);
        entry.set("value", objectMapper.valueToTree(value));
        entry.put("source", source);
    }

    private static void putOrNull(ObjectNode node, String field, String value) {
        if (StringUtils.hasText(value)) {
            node.put(field, value.strip());
        } else {
            node.putNull(field);
        }
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

    private JsonNode generationPlanInput(Object payload) {
        if (!(payload instanceof GenerationPlanRequest request)) {
            return objectMapper.valueToTree(payload);
        }

        ObjectNode input = objectMapper.createObjectNode();
        ObjectNode projectInfo = input.putObject("projectInfo");
        projectInfo.put("projectRef", String.valueOf(request.projectId()));
        projectInfo.put("courseName", request.courseName());
        projectInfo.put("chapterTitle", request.chapterTopic());
        putIfText(projectInfo, "targetStudents", request.targetAudience());

        ObjectNode intent = input.putObject("teachingIntent");
        intent.put("status", "CONFIRMED");
        intent.set("teachingGoals", objectMapper.valueToTree(request.teachingGoals()));
        intent.set("contentPriorities", objectMapper.valueToTree(request.contentPriorities()));
        intent.set("interactionPlan", objectMapper.valueToTree(request.interactionIdeas()));
        intent.set("outputTypes", objectMapper.valueToTree(request.outputTypes()));
        input.put(
                "generationMode",
                request.generationMode() == null ? "STANDARD" : request.generationMode().name()
        );

        GenerationConstraints source = request.constraints();
        ObjectNode constraints = input.putObject("constraints");
        constraints.put("lessonDurationMinutes", positiveOrDefault(
                source == null ? null : source.lessonDurationMinutes(),
                45
        ));
        constraints.put("maximumSlides", positiveOrDefault(
                source == null ? null : source.maximumSlides(),
                12
        ));
        constraints.put("interactionMinutes", positiveOrDefault(
                source == null ? null : source.interactionMinutes(),
                10
        ));
        List<String> targetTypes = source == null || source.targetTypes().isEmpty()
                ? request.outputTypes()
                : source.targetTypes();
        constraints.set("targetTypes", objectMapper.valueToTree(targetTypes));
        return input;
    }

    private JsonNode structuredContentInput(Object payload) {
        if (!(payload instanceof StructuredContentRequest request)) {
            return objectMapper.valueToTree(payload);
        }

        ObjectNode input = objectMapper.createObjectNode();
        GenerationPlanSnapshot source = request.generationPlan();
        ObjectNode plan = input.putObject("generationPlan");
        plan.put("status", "CONFIRMED");
        plan.put("planRef", source.planRef());
        plan.set("pptOutline", contractSections(source.pptOutline(), "slideNo"));
        plan.set("docOutline", contractSections(source.docOutline(), "sectionNo"));
        plan.set("interactionPlan", objectMapper.valueToTree(source.interactionPlan()));

        ArrayNode references = input.putArray("referenceContext");
        int index = 1;
        for (KnowledgeSnippet snippet : safeSnippets(request.referenceContext())) {
            ObjectNode reference = references.addObject();
            reference.put("sourceId", "reference-" + index++);
            reference.put("sourceName", defaultString(snippet.sourceName()));
            reference.put("title", defaultString(snippet.title()));
            reference.put("content", defaultString(snippet.content()));
            reference.put("relevance", snippet.score());
        }
        input.set("targetTypes", objectMapper.valueToTree(request.targetTypes()));
        return input;
    }

    private JsonNode revisionInput(Object payload) {
        if (!(payload instanceof RevisionRequest request)) {
            return objectMapper.valueToTree(payload);
        }

        ObjectNode input = objectMapper.createObjectNode();
        ObjectNode currentVersion = input.putObject("currentVersion");
        currentVersion.put("versionRef", "artifact-" + request.artifactId());
        currentVersion.put("artifactType", defaultIfBlank(request.artifactType(), "UNKNOWN"));
        currentVersion.set("contentJson", parseJsonValue(request.currentContent()));
        input.put("editText", request.instruction());
        ObjectNode locator = input.putObject("locatorContext");
        locator.put("artifactType", defaultIfBlank(request.artifactType(), "UNKNOWN"));
        if (StringUtils.hasText(request.selectedLocator())) {
            locator.put("selectedLocator", request.selectedLocator().strip());
        } else {
            locator.putNull("selectedLocator");
        }
        return input;
    }

    private ArrayNode contractSections(List<PlanSection> sections, String orderField) {
        ArrayNode result = objectMapper.createArrayNode();
        int index = 1;
        for (PlanSection section : sections == null ? List.<PlanSection>of() : sections) {
            if (section == null) {
                continue;
            }
            ObjectNode item = result.addObject();
            item.put(orderField, index++);
            item.put("title", defaultString(section.title()));
            item.put("purpose", defaultString(section.materialReference()));
            item.set("keyPoints", objectMapper.valueToTree(safeStrings(section.points())));
            item.putArray("materialReferences");
        }
        return result;
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
        response.set("generationGoals", textArray(intent.get("teachingGoals")));

        ArrayNode contentBasis = response.putArray("contentBasis");
        appendTextValues(contentBasis, intent.get("contentPriorities"), "");
        appendTextValues(contentBasis, intent.get("teachingOrganization"), "教学组织：");
        response.set("interactionIdeas", structuredTextArray(intent.get("interactionPlan")));
        response.set("outputTypes", textArray(intent.get("outputTypes")));

        List<String> conflicts = textValues(payload.get("conflictWarnings"));
        response.put(
                "confirmationPrompt",
                conflicts.isEmpty()
                        ? "请确认以上教学意图是否准确。"
                        : "请确认教学意图，并处理以下冲突：" + String.join("；", conflicts)
        );
        return response;
    }

    private ObjectNode generationPlanResponse(ObjectNode payload) {
        if (payload.has("planId")
                && payload.path("pptOutline").isArray()
                && payload.path("interactionPlan").isArray()) {
            return payload.deepCopy();
        }

        ObjectNode response = objectMapper.createObjectNode();
        String planId = payload.path("planId").asText("").strip();
        response.put("planId", StringUtils.hasText(planId) ? planId : "dify-generation-plan");
        response.set("pptOutline", gatewayPlanSections(payload.get("pptOutline")));
        response.set("docOutline", gatewayPlanSections(payload.get("docOutline")));

        ArrayNode interactionPlan = response.putArray("interactionPlan");
        JsonNode interaction = payload.get("interactionPlan");
        if (interaction != null && interaction.isArray()) {
            interactionPlan.addAll(arrayCopy(interaction));
        } else if (interaction != null && interaction.isObject()) {
            addIfText(interactionPlan, interaction.path("type").asText());
            for (String point : textValues(interaction.get("knowledgePoints"))) {
                interactionPlan.add("Knowledge point: " + point);
            }
            if (interaction.path("questionCount").canConvertToInt()) {
                interactionPlan.add("Question count: " + interaction.path("questionCount").asInt());
            }
            addIfText(interactionPlan, interaction.path("difficulty").asText());
        }
        if (interactionPlan.isEmpty()) {
            interactionPlan.add("Classroom question and evidence-based discussion");
        }

        int estimatedMinutes = interaction != null && interaction.path("estimatedMinutes").canConvertToInt()
                ? interaction.path("estimatedMinutes").asInt()
                : 10;
        response.put("estimatedDuration", "Approximately " + Math.max(1, estimatedMinutes) + " minutes");
        response.put("nextAction", "Confirm the generation plan before drafting structured content.");
        return response;
    }

    private ArrayNode gatewayPlanSections(JsonNode sections) {
        ArrayNode result = objectMapper.createArrayNode();
        if (sections == null || !sections.isArray()) {
            return result;
        }
        for (JsonNode source : sections) {
            ObjectNode section = result.addObject();
            section.put("title", source.path("title").asText("").strip());
            section.set("points", arrayCopy(source.get("keyPoints")));
            List<String> references = textValues(source.get("materialReferences"));
            String purpose = source.path("purpose").asText("").strip();
            section.put(
                    "materialReference",
                    !references.isEmpty()
                            ? String.join(", ", references)
                            : StringUtils.hasText(purpose) ? purpose : "Confirmed teaching intent"
            );
        }
        return result;
    }

    private ObjectNode structuredContentResponse(ObjectNode payload) {
        if (!payload.has("pptContent")
                || !payload.has("docContent")
                || !payload.has("interactionContent")) {
            return payload.deepCopy();
        }
        ObjectNode response = payload.deepCopy();
        JsonNode docContent = response.path("docContent").path("contentJson");
        if (docContent.isObject()) {
            normalizeTextLists(
                    (ObjectNode) docContent,
                    List.of(
                            "teachingGoals", "keyPoints", "difficultPoints", "methods",
                            "classroomActivities", "homework", "resourceNotes"
                    )
            );
            ensureCanonicalDocSections((ObjectNode) docContent);
        }
        response.put("fallbackToBackendDrafts", false);
        return response;
    }

    private void normalizeTextLists(ObjectNode content, List<String> fields) {
        for (String field : fields) {
            JsonNode value = content.get(field);
            if (value != null && value.isTextual()) {
                content.set(field, textArray(value));
            }
        }
    }

    private void ensureCanonicalDocSections(ObjectNode content) {
        JsonNode sections = content.path("sections");
        if (sections.isArray() && sections.size() >= 9) {
            return;
        }
        if (!content.path("courseInfo").isObject()
                || !content.path("teachingProcess").isArray()
                || !hasTextArrays(content, List.of(
                        "teachingGoals", "keyPoints", "difficultPoints", "methods",
                        "classroomActivities", "homework", "resourceNotes"
                ))) {
            return;
        }

        ArrayNode canonical = objectMapper.createArrayNode();
        addDocSection(canonical, "Course information", courseInfoParagraphs(content.path("courseInfo")));
        addDocSection(canonical, "Teaching goals", paragraphValues(content.path("teachingGoals")));
        addDocSection(canonical, "Key teaching points", paragraphValues(content.path("keyPoints")));
        addDocSection(canonical, "Difficult teaching points", paragraphValues(content.path("difficultPoints")));
        addDocSection(canonical, "Teaching methods", paragraphValues(content.path("methods")));
        addDocSection(canonical, "Teaching process", teachingProcessParagraphs(content.path("teachingProcess")));
        addDocSection(canonical, "Classroom activities", paragraphValues(content.path("classroomActivities")));
        addDocSection(canonical, "Homework", paragraphValues(content.path("homework")));
        addDocSection(canonical, "Teaching resources", paragraphValues(content.path("resourceNotes")));
        content.set("sections", canonical);
    }

    private static boolean hasTextArrays(ObjectNode content, List<String> fields) {
        return fields.stream().allMatch(field -> content.path(field).isArray());
    }

    private void addDocSection(ArrayNode sections, String title, ArrayNode paragraphs) {
        ObjectNode section = sections.addObject();
        section.put("order", sections.size());
        section.put("title", title);
        if (paragraphs.isEmpty()) {
            paragraphs.add("Not specified");
        }
        section.set("paragraphs", paragraphs);
    }

    private ArrayNode courseInfoParagraphs(JsonNode courseInfo) {
        ArrayNode paragraphs = objectMapper.createArrayNode();
        addLabeledParagraph(paragraphs, "Project", courseInfo.path("projectName"));
        addLabeledParagraph(paragraphs, "Course", courseInfo.path("courseName"));
        addLabeledParagraph(paragraphs, "Topic", courseInfo.path("chapterTopic"));
        addLabeledParagraph(paragraphs, "Audience", courseInfo.path("targetAudience"));
        addLabeledParagraph(paragraphs, "Duration", courseInfo.path("lessonDurationMinutes"));
        addLabeledParagraph(paragraphs, "Generation mode", courseInfo.path("generationMode"));
        return paragraphs;
    }

    private static void addLabeledParagraph(ArrayNode paragraphs, String label, JsonNode value) {
        if (value != null && !value.isMissingNode() && !value.isNull() && StringUtils.hasText(value.asText())) {
            paragraphs.add(label + ": " + value.asText());
        }
    }

    private ArrayNode teachingProcessParagraphs(JsonNode process) {
        ArrayNode paragraphs = objectMapper.createArrayNode();
        for (JsonNode step : process) {
            if (!step.isObject()) {
                continue;
            }
            String stage = step.path("stage").asText("Teaching stage");
            String duration = step.path("durationMinutes").asText("");
            String content = step.path("content").asText("");
            String teacher = step.path("teacherActivity").asText("");
            String student = step.path("studentActivity").asText("");
            StringBuilder paragraph = new StringBuilder(stage);
            if (StringUtils.hasText(duration)) {
                paragraph.append(" (").append(duration).append(" min)");
            }
            if (StringUtils.hasText(content)) {
                paragraph.append(": ").append(content);
            }
            if (StringUtils.hasText(teacher)) {
                paragraph.append(" Teacher: ").append(teacher);
            }
            if (StringUtils.hasText(student)) {
                paragraph.append(" Student: ").append(student);
            }
            paragraphs.add(paragraph.toString());
        }
        return paragraphs;
    }

    private ArrayNode paragraphValues(JsonNode values) {
        ArrayNode paragraphs = objectMapper.createArrayNode();
        for (JsonNode value : values) {
            if (value.isValueNode() && StringUtils.hasText(value.asText())) {
                paragraphs.add(value.asText());
            }
        }
        return paragraphs;
    }

    private ObjectNode revisionResponse(ObjectNode payload) {
        if (payload.has("changeSummary") && payload.has("changedSections")) {
            return payload.deepCopy();
        }
        if (!payload.has("editAction") || !payload.has("impactScope")) {
            return payload.deepCopy();
        }

        ObjectNode response = objectMapper.createObjectNode();
        String action = payload.path("editAction").asText("OTHER");
        String scope = payload.path("scope").asText("PARTIAL");
        String locator = payload.path("targetLocator").asText("");
        String reason = payload.path("impactScope").path("reason").asText("");
        StringBuilder summary = new StringBuilder("Interpreted ")
                .append(action)
                .append(" as a ")
                .append(scope)
                .append(" revision");
        if (StringUtils.hasText(locator)) {
            summary.append(" targeting ").append(locator);
        }
        if (StringUtils.hasText(reason)) {
            summary.append(". ").append(reason.strip());
        }
        response.put("changeSummary", summary.toString());
        response.set("changedSections", arrayCopy(payload.path("impactScope").get("sections")));
        response.putNull("revisedContent");
        response.put("versionSuggestion", "Create a new version after applying the interpreted revision intent.");
        return response;
    }

    private ArrayNode arrayOrEmpty(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : objectMapper.createArrayNode();
    }

    private JsonNode parseJsonValue(String value) {
        if (!StringUtils.hasText(value)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed == null ? objectMapper.createObjectNode() : parsed;
        } catch (JsonProcessingException exception) {
            return objectMapper.getNodeFactory().textNode(value);
        }
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }

    private static void addIfText(ArrayNode target, String value) {
        if (StringUtils.hasText(value)) {
            target.add(value.strip());
        }
    }

    private ArrayNode arrayCopy(JsonNode value) {
        return value != null && value.isArray()
                ? ((ArrayNode) value).deepCopy()
                : objectMapper.createArrayNode();
    }

    private ArrayNode textArray(JsonNode value) {
        ArrayNode result = objectMapper.createArrayNode();
        textValues(value).forEach(result::add);
        return result;
    }

    private ArrayNode structuredTextArray(JsonNode value) {
        ArrayNode result = textArray(value);
        if (value == null || !value.isObject()) {
            return result;
        }

        value.fields().forEachRemaining(entry -> {
            String displayed = displayValue(entry.getValue()).strip();
            if (StringUtils.hasText(displayed)) {
                result.add(entry.getKey() + ": " + displayed);
            }
        });
        return result;
    }

    private List<String> textValues(JsonNode value) {
        if (value == null) {
            return List.of();
        }
        if (value.isTextual()) {
            return StringUtils.hasText(value.asText())
                    ? List.of(value.asText().strip())
                    : List.of();
        }
        if (!value.isArray()) {
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
