package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.WorkflowCode;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.DialogTurn;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
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
}
