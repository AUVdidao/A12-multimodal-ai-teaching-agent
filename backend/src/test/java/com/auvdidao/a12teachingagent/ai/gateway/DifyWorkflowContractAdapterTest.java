package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.WorkflowCode;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.DialogTurn;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeSnippet;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryData;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentRequest;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                        GenerationMode.HIGH_QUALITY
                )
        );

        assertThat(input.path("projectInfo").path("projectRef").asText()).isEqualTo("78");
        assertThat(input.path("projectInfo").path("generationMode").asText()).isEqualTo("HIGH_QUALITY");
        assertThat(input.path("rawRequirement").asText()).isEqualTo("Create a fraction lesson");
        assertThat(input.path("dialogHistory").get(0).path("role").asText()).isEqualTo("teacher");
        assertThat(input.path("defaultValues").path("coursewareStyle").path("value").asText())
                .isEqualTo("CLEAR_VISUAL");
        assertThat(input.path("defaultValues").path("coursewareStyle").path("source").asText())
                .isEqualTo("system-default");
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
