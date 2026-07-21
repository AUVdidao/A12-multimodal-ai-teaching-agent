package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.WorkflowCode;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.DialogTurn;
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
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DifyWorkflowContractAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DifyWorkflowContractAdapter adapter = new DifyWorkflowContractAdapter(objectMapper);

    @Test
    void buildsWf01InputWithoutInventingUnknownProjectFacts() {
        JsonNode input = adapter.requestInput(
                WorkflowCode.CLARIFICATION,
                new ClarificationRequest(
                        78L,
                        "Create a fraction lesson",
                        List.of("courseName"),
                        GenerationMode.STANDARD,
                        List.of("targetAudience")
                )
        );

        assertThat(input.path("projectInfo").path("projectRef").asText()).isEqualTo("78");
        assertThat(input.path("projectInfo").path("courseName").isNull()).isTrue();
        assertThat(input.path("projectInfo").path("generationMode").asText()).isEqualTo("STANDARD");
        assertThat(input.path("rawRequirement").asText()).isEqualTo("Create a fraction lesson");
        assertThat(input.path("knownFields").get(0).asText()).isEqualTo("courseName");
        assertThat(input.path("requestedMissingFields").get(0).asText()).isEqualTo("targetAudience");
        assertThat(input.path("dialogHistory").isArray()).isTrue();
    }

    @Test
    void buildsWf02InputWithDialogHistoryAndSourcedDefaults() {
        JsonNode input = adapter.requestInput(
                WorkflowCode.REQUIREMENT_SUMMARY,
                new RequirementSummaryRequest(
                        78L,
                        "Create a fraction lesson",
                        List.of(new DialogTurn("teacher", "Use a visual explanation")),
                        GenerationMode.HIGH_QUALITY,
                        new RequirementSummaryData(
                                "Mathematics",
                                "Fractions",
                                "Grade 5",
                                45,
                                List.of("Explain fractions"),
                                List.of("Part-whole relationship"),
                                List.of("PPT", "LESSON_PLAN"),
                                "VISUAL",
                                "QUESTION_AND_ANSWER"
                        )
                )
        );

        assertThat(input.path("projectInfo").path("projectRef").asText()).isEqualTo("78");
        assertThat(input.path("projectInfo").path("courseName").asText()).isEqualTo("Mathematics");
        assertThat(input.path("projectInfo").path("chapterTitle").asText()).isEqualTo("Fractions");
        assertThat(input.path("projectInfo").path("targetStudents").asText()).isEqualTo("Grade 5");
        assertThat(input.path("projectInfo").path("lessonDuration").asInt()).isEqualTo(45);
        assertThat(input.path("projectInfo").path("generationMode").asText()).isEqualTo("HIGH_QUALITY");
        assertThat(input.path("rawRequirement").asText()).isEqualTo("Create a fraction lesson");
        assertThat(input.path("dialogHistory").get(0).path("role").asText()).isEqualTo("teacher");
        assertThat(input.path("defaultValues").path("coursewareStyle").path("value").asText())
                .isEqualTo("VISUAL");
        assertThat(input.path("defaultValues").path("coursewareStyle").path("source").asText())
                .isEqualTo("teacher-structured-input");
        assertThat(input.path("defaultValues").path("teachingGoals").path("value").get(0).asText())
                .isEqualTo("Explain fractions");
        assertThat(input.path("defaultValues").path("outputTypes").path("value").get(1).asText())
                .isEqualTo("LESSON_PLAN");
    }

    @Test
    void mapsWf02OutputToReadableBusinessSummary() throws Exception {
        JsonNode output = objectMapper.readTree("""
                {
                  "requirementSummary": {
                    "courseName": "人工智能基础",
                    "chapterTitle": "机器学习导论",
                    "targetStudents": "大学一年级",
                    "lessonDuration": 90,
                    "teachingGoals": ["理解基本概念"],
                    "keyDifficulties": ["区分监督学习与无监督学习"],
                    "outputTypes": ["PPT", "DOCX"],
                    "coursewareStyle": "清晰直观",
                    "interactionType": "课堂问答"
                  },
                  "uncertainFields": ["先修知识"],
                  "generationHints": ["增加生活化案例"]
                }
                """);

        JsonNode response = adapter.responsePayload(
                WorkflowCode.REQUIREMENT_SUMMARY,
                "requirement-summary",
                (com.fasterxml.jackson.databind.node.ObjectNode) output
        );

        assertThat(response.path("summary").path("chapterTopic").asText()).isEqualTo("机器学习导论");
        assertThat(response.path("assumptions").get(0).asText()).isEqualTo("待确认：先修知识");
        assertThat(response.path("assumptions").get(1).asText()).isEqualTo("生成提示：增加生活化案例");
        assertThat(response.path("confirmationQuestion").asText())
                .isEqualTo("请确认摘要，并检查仍待确认的字段与生成提示。");
    }

    @Test
    void buildsAndMapsWf03MaterialContractWithExtractedText() throws Exception {
        RequirementSummaryData courseContext = summaryData();
        JsonNode input = adapter.requestInput(
                WorkflowCode.MATERIAL_ANALYSIS,
                "material-analysis",
                new MaterialAnalysisRequest(
                        78L,
                        "光合作用教材.pdf",
                        "PDF",
                        "教材依据",
                        "叶绿体吸收光能并转换为化学能。",
                        List.of("TEXTBOOK_BASIS"),
                        courseContext
                )
        );

        assertThat(input.path("materialText").path("content").asText()).contains("叶绿体");
        assertThat(input.path("materialText").path("sourceName").asText()).isEqualTo("光合作用教材.pdf");
        assertThat(input.path("purposeTypes").get(0).asText()).isEqualTo("TEXTBOOK_BASIS");
        assertThat(input.path("courseContext").path("chapterTitle").asText()).isEqualTo("光合作用");

        JsonNode output = objectMapper.readTree("""
                {
                  "materialSummary": {
                    "title": "光合作用",
                    "overview": "资料介绍了光能转换。",
                    "keywords": ["叶绿体", "光能"]
                  },
                  "usableFragments": [{
                    "content": "叶绿体吸收光能。",
                    "purposeTypes": ["TEXTBOOK_BASIS"]
                  }],
                  "riskNotes": ["缺少定量实验数据"]
                }
                """);
        JsonNode response = adapter.responsePayload(
                WorkflowCode.MATERIAL_ANALYSIS,
                "material-analysis",
                (com.fasterxml.jackson.databind.node.ObjectNode) output
        );

        assertThat(response.path("status").asText()).isEqualTo("PARSED");
        assertThat(response.path("summary").asText()).contains("资料介绍了光能转换", "缺少定量实验数据");
        assertThat(response.path("keywords").get(0).asText()).isEqualTo("叶绿体");
        assertThat(response.path("teachingUses").get(0).asText()).isEqualTo("TEXTBOOK_BASIS");
        assertThat(response.path("suggestedChunks").get(0).asText()).isEqualTo("叶绿体吸收光能。");
    }

    @Test
    void buildsWf04InputsWithoutInventingKnowledgeCandidates() {
        JsonNode retrieval = adapter.requestInput(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "knowledge-retrieval",
                new KnowledgeRetrievalRequest(
                        78L,
                        "生物",
                        "光合作用",
                        List.of("叶绿体"),
                        List.of(new KnowledgeSnippet("能量转换", "教材.pdf", "光能转化为化学能。", 0.92))
                )
        );
        assertThat(retrieval.path("query").path("chapterTitle").asText()).isEqualTo("光合作用");
        assertThat(retrieval.path("knowledgeCandidates")).hasSize(1);
        assertThat(retrieval.path("knowledgeCandidates").get(0).path("content").asText()).contains("化学能");

        JsonNode emptyRetrieval = adapter.requestInput(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "knowledge-retrieval",
                new KnowledgeRetrievalRequest(78L, "生物", "光合作用", List.of("叶绿体"))
        );
        assertThat(emptyRetrieval.path("knowledgeCandidates")).isEmpty();

        JsonNode intent = adapter.requestInput(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "teaching-intent",
                new TeachingIntentRequest(
                        78L,
                        summaryData(),
                        List.of(new KnowledgeSnippet("能量转换", "教材.pdf", "光能转化为化学能。", 0.92))
                )
        );
        assertThat(intent.path("requirementSummary").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(intent.path("materialSummaries")).hasSize(1);
        assertThat(intent.path("knowledgeSnippets")).hasSize(1);
    }

    @Test
    void mapsBothWf04OperationsToGatewayDtos() throws Exception {
        JsonNode retrievalOutput = objectMapper.readTree("""
                {
                  "knowledgeRetrieval": {
                    "snippets": [{
                      "title": "能量转换",
                      "sourceName": "教材.pdf",
                      "content": "光能转化为化学能。",
                      "relevance": 0.92
                    }],
                    "retrievalNote": "仅重排了后端提供的候选。"
                  }
                }
                """);
        JsonNode retrieval = adapter.responsePayload(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "knowledge-retrieval",
                (com.fasterxml.jackson.databind.node.ObjectNode) retrievalOutput
        );
        assertThat(retrieval.path("snippets").get(0).path("score").asDouble()).isEqualTo(0.92);
        assertThat(retrieval.path("retrievalNote").asText()).contains("后端提供");

        JsonNode intentOutput = objectMapper.readTree("""
                {
                  "intentId": "intent-wf04-78",
                  "teachingIntent": {
                    "teachingGoals": ["解释光合作用"],
                    "contentPriorities": ["先观察证据"],
                    "teachingOrganization": ["小组讨论"],
                    "interactionPlan": ["证据分类"],
                    "outputTypes": ["PPT", "DOCX"]
                  },
                  "conflictWarnings": []
                }
                """);
        JsonNode intent = adapter.responsePayload(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "teaching-intent",
                (com.fasterxml.jackson.databind.node.ObjectNode) intentOutput
        );
        assertThat(intent.path("intentId").asText()).isEqualTo("intent-wf04-78");
        assertThat(intent.path("generationGoals").get(0).asText()).isEqualTo("解释光合作用");
        assertThat(intent.path("contentBasis").get(1).asText()).isEqualTo("教学组织：小组讨论");
        assertThat(intent.path("confirmationPrompt").asText()).isEqualTo("请确认以上教学意图是否准确。");
    }

    @Test
    void normalizesWf04TextFieldsToTeachingIntentLists() throws Exception {
        JsonNode intentOutput = objectMapper.readTree("""
                {
                  "intentId": "intent-wf04-161",
                  "teachingIntent": {
                    "teachingGoals": ["Explain photosynthesis"],
                    "contentPriorities": ["Evidence before explanation"],
                    "teachingOrganization": "Use observation, prediction, and group discussion.",
                    "interactionPlan": "Students compare evidence in groups and report conclusions.",
                    "outputTypes": ["PPT", "DOCX"]
                  },
                  "conflictWarnings": []
                }
                """);

        JsonNode intent = adapter.responsePayload(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "teaching-intent",
                (com.fasterxml.jackson.databind.node.ObjectNode) intentOutput
        );

        assertThat(intent.path("contentBasis").get(1).asText())
                .contains("Use observation, prediction, and group discussion.");
        assertThat(intent.path("interactionIdeas").get(0).asText())
                .isEqualTo("Students compare evidence in groups and report conclusions.");
    }

    @Test
    void normalizesWf04StructuredInteractionPlanToTeachingIntentLists() throws Exception {
        JsonNode intentOutput = objectMapper.readTree("""
                {
                  "intentId": "intent-wf04-164",
                  "teachingIntent": {
                    "teachingGoals": ["Explain photosynthesis"],
                    "contentPriorities": ["Evidence before explanation"],
                    "teachingOrganization": "Prediction, inquiry, discussion, and feedback.",
                    "interactionPlan": {
                      "prediction": "Students predict variables before inquiry",
                      "observation": "Students observe oxygen-production evidence",
                      "discussion": "Groups explain matter and energy conversion",
                      "feedback": "A short quiz checks understanding"
                    },
                    "outputTypes": ["PPT", "LESSON_PLAN"]
                  },
                  "conflictWarnings": []
                }
                """);

        JsonNode intent = adapter.responsePayload(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "teaching-intent",
                (com.fasterxml.jackson.databind.node.ObjectNode) intentOutput
        );

        assertThat(intent.path("interactionIdeas")).hasSize(4);
        assertThat(intent.path("interactionIdeas").get(0).asText())
                .isEqualTo("prediction: Students predict variables before inquiry");
        assertThat(intent.path("interactionIdeas").get(3).asText())
                .isEqualTo("feedback: A short quiz checks understanding");
    }

    @Test
    void buildsAndMapsWf05GenerationPlanContract() throws Exception {
        JsonNode input = adapter.requestInput(
                WorkflowCode.GENERATION_PLAN,
                "generation-plan",
                new GenerationPlanRequest(
                        78L,
                        "生物",
                        "光合作用",
                        "八年级",
                        List.of("PPT", "DOCX", "INTERACTION"),
                        GenerationMode.HIGH_QUALITY,
                        List.of("解释能量转换"),
                        List.of("先观察实验现象"),
                        List.of("证据分类"),
                        new GenerationConstraints(45, 10, 8, List.of("PPT", "DOCX", "INTERACTION"))
                )
        );

        assertThat(input.path("teachingIntent").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(input.path("teachingIntent").path("teachingGoals").get(0).asText())
                .isEqualTo("解释能量转换");
        assertThat(input.path("constraints").path("maximumSlides").asInt()).isEqualTo(10);

        JsonNode output = objectMapper.readTree("""
                {
                  "planId": "plan-wf05-78",
                  "pptOutline": [{
                    "slideNo": 1,
                    "title": "导入",
                    "purpose": "激活先验知识",
                    "keyPoints": ["观察实验现象"],
                    "materialReferences": ["教材.pdf"]
                  }],
                  "docOutline": [{
                    "sectionNo": 1,
                    "title": "教学过程",
                    "purpose": "组织探究",
                    "keyPoints": ["证据分类"],
                    "materialReferences": ["教材.pdf"]
                  }],
                  "interactionPlan": {
                    "type": "QUIZ",
                    "knowledgePoints": ["能量转换"],
                    "questionCount": 3,
                    "difficulty": "MEDIUM",
                    "estimatedMinutes": 8
                  }
                }
                """);
        JsonNode response = adapter.responsePayload(
                WorkflowCode.GENERATION_PLAN,
                "generation-plan",
                (com.fasterxml.jackson.databind.node.ObjectNode) output
        );

        assertThat(response.path("planId").asText()).isEqualTo("plan-wf05-78");
        assertThat(response.path("pptOutline").get(0).path("materialReference").asText())
                .isEqualTo("教材.pdf");
        assertThat(response.path("interactionPlan").get(0).asText()).isEqualTo("QUIZ");
        assertThat(response.path("estimatedDuration").asText()).contains("8");
    }

    @Test
    void buildsAndMapsWf06StructuredContentContract() throws Exception {
        JsonNode input = adapter.requestInput(
                WorkflowCode.CONTENT_DRAFT,
                "structured-content",
                new StructuredContentRequest(
                        78L,
                        new GenerationPlanSnapshot(
                                "plan-wf05-78",
                                List.of(new PlanSection("导入", List.of("观察现象"), "教材.pdf")),
                                List.of(new PlanSection("教学过程", List.of("证据分类"), "教材.pdf")),
                                List.of("随堂问答")
                        ),
                        List.of(new KnowledgeSnippet("能量转换", "教材.pdf", "光能转化为化学能。", 0.95)),
                        List.of("PPT", "DOCX", "INTERACTION")
                )
        );

        assertThat(input.path("generationPlan").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(input.path("generationPlan").path("planRef").asText()).isEqualTo("plan-wf05-78");
        assertThat(input.path("referenceContext").get(0).path("sourceName").asText())
                .isEqualTo("教材.pdf");

        JsonNode output = objectMapper.readTree("""
                {
                  "pptContent": {
                    "artifactType": "PPT",
                    "title": "光合作用课件",
                    "contentJson": {"deckTitle": "光合作用", "theme": "clear", "slides": [{}]},
                    "assetSuggestions": []
                  },
                  "docContent": {
                    "artifactType": "DOCX",
                    "title": "光合作用教案",
                    "contentJson": {"sections": [{}]},
                    "assetSuggestions": []
                  },
                  "interactionContent": {
                    "artifactType": "INTERACTION",
                    "title": "光合作用问答",
                    "contentJson": {"questions": [{}]},
                    "assetSuggestions": []
                  }
                }
                """);
        JsonNode response = adapter.responsePayload(
                WorkflowCode.CONTENT_DRAFT,
                "structured-content",
                (com.fasterxml.jackson.databind.node.ObjectNode) output
        );

        assertThat(response.path("fallbackToBackendDrafts").asBoolean()).isFalse();
        assertThat(response.path("pptContent").path("contentJson").path("slides")).hasSize(1);
        assertThat(response.path("docContent").path("artifactType").asText()).isEqualTo("DOCX");
    }

    @Test
    void normalizesWf06DocTextFieldsToLists() throws Exception {
        ObjectNode output = (ObjectNode) objectMapper.readTree("""
                {
                  "pptContent": {"artifactType":"PPT","title":"Slides","contentJson":{"slides":[]},"assetSuggestions":[]},
                  "docContent": {
                    "artifactType": "DOCX",
                    "title": "Lesson plan",
                    "contentJson": {
                      "sections": [],
                      "courseInfo": {
                        "projectName": "Photosynthesis lesson",
                        "courseName": "Biology",
                        "chapterTopic": "Photosynthesis",
                        "targetAudience": "Grade 8",
                        "lessonDurationMinutes": 45,
                        "generationMode": "STANDARD"
                      },
                      "teachingGoals": "Explain photosynthesis",
                      "keyPoints": "Energy conversion",
                      "difficultPoints": "Experimental conditions",
                      "methods": "Guided inquiry",
                      "teachingProcess": [{
                        "stage": "Observation",
                        "durationMinutes": 15,
                        "content": "Observe evidence.",
                        "teacherActivity": "Prompt comparison.",
                        "studentActivity": "Record findings."
                      }],
                      "classroomActivities": "Small-group discussion",
                      "homework": "Draw a concept map",
                      "resourceNotes": "Use confirmed evidence"
                    },
                    "assetSuggestions": []
                  },
                  "interactionContent": {"artifactType":"INTERACTION","title":"Quiz","contentJson":{"questions":[]},"assetSuggestions":[]}
                }
                """);

        JsonNode response = adapter.responsePayload(
                WorkflowCode.CONTENT_DRAFT,
                "structured-content",
                output
        );
        JsonNode doc = response.path("docContent").path("contentJson");

        for (String field : List.of(
                "teachingGoals", "keyPoints", "difficultPoints", "methods",
                "classroomActivities", "homework", "resourceNotes"
        )) {
            assertThat(doc.path(field).isArray()).isTrue();
            assertThat(doc.path(field)).hasSize(1);
        }
        assertThat(doc.path("sections")).hasSize(9);
        assertThat(doc.path("sections").get(0).path("title").asText()).isEqualTo("Course information");
        assertThat(doc.path("sections").get(8).path("title").asText()).isEqualTo("Teaching resources");
    }

    @Test
    void buildsAndMapsWf07RevisionIntentContract() throws Exception {
        JsonNode input = adapter.requestInput(
                WorkflowCode.REVISION,
                "revision",
                new RevisionRequest(
                        78L,
                        9L,
                        "在第三页增加案例",
                        "{\"deckTitle\":\"光合作用\",\"slides\":[]}",
                        "PPT",
                        "slide-3"
                )
        );

        assertThat(input.path("currentVersion").path("contentJson").path("deckTitle").asText())
                .isEqualTo("光合作用");
        assertThat(input.path("locatorContext").path("selectedLocator").asText()).isEqualTo("slide-3");

        JsonNode output = objectMapper.readTree("""
                {
                  "editAction": "ADD",
                  "scope": "PARTIAL",
                  "targetLocator": "slide-3",
                  "instructionSummary": "增加一个应用案例",
                  "impactScope": {
                    "sections": ["slide-3"],
                    "reason": "只影响第三页"
                  },
                  "requiresRegeneration": true
                }
                """);
        JsonNode response = adapter.responsePayload(
                WorkflowCode.REVISION,
                "revision",
                (com.fasterxml.jackson.databind.node.ObjectNode) output
        );

        assertThat(response.path("changeSummary").asText()).contains("ADD", "slide-3");
        assertThat(response.path("changedSections").get(0).asText()).isEqualTo("slide-3");
        assertThat(response.path("revisedContent").isNull()).isTrue();
    }

    private static RequirementSummaryData summaryData() {
        return new RequirementSummaryData(
                "生物",
                "光合作用",
                "八年级",
                45,
                List.of("解释光合作用"),
                List.of("能量转换"),
                List.of("PPT", "DOCX"),
                "清晰",
                "课堂问答"
        );
    }
}
