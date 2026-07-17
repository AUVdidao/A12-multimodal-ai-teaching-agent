package com.auvdidao.a12teachingagent.ai;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanSnapshot;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.PlanSection;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.StructuredContentRequest;
import com.auvdidao.a12teachingagent.ai.gateway.MockAIWorkflowGateway;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockAIWorkflowGatewayTest {

    private final MockAIWorkflowGateway gateway = new MockAIWorkflowGateway();

    @Test
    void clarificationReturnsMissingFieldsForIncompleteRequirement() {
        var response = gateway.clarifyRequirement(new ClarificationRequest(
                1L,
                "帮我做一节数学分数课",
                List.of("courseName", "chapterTopic"),
                GenerationMode.MOCK
        ));

        assertThat(response.workflow()).isEqualTo("mock-ai-workflow");
        assertThat(response.missingFields())
                .contains("targetAudience", "lessonDurationMinutes", "outputTypes");
        assertThat(response.questions()).hasSize(3);
        assertThat(response.suggestedFields()).containsEntry("targetAudience", "小学五年级");
    }

    @Test
    void generationPlanReturnsStablePptDocAndInteractionOutline() {
        var response = gateway.createGenerationPlan(new GenerationPlanRequest(
                7L,
                "数学",
                "分数的意义",
                "小学五年级",
                List.of("PPT", "DOCX", "INTERACTION"),
                GenerationMode.STANDARD
        ));

        assertThat(response.planId()).isEqualTo("plan-mock-7");
        assertThat(response.pptOutline()).hasSize(6);
        assertThat(response.docOutline()).hasSize(4);
        assertThat(response.interactionPlan()).contains("生成 3 道互动问答");
    }

    @Test
    void structuredContentDelegatesToExistingBackendDraftFactoryInMockMode() {
        var response = gateway.generateStructuredContent(new StructuredContentRequest(
                7L,
                new GenerationPlanSnapshot(
                        "plan-mock-7",
                        List.of(new PlanSection("导入", List.of("分数模型"), "本地资料")),
                        List.of(new PlanSection("教学过程", List.of("解释分数"), "本地资料")),
                        List.of("互动问答")
                ),
                List.of(),
                List.of("PPT", "DOCX", "INTERACTION")
        ));

        assertThat(response.workflow()).isEqualTo("mock-ai-workflow");
        assertThat(response.fallbackToBackendDrafts()).isTrue();
        assertThat(response.pptContent()).isNull();
        assertThat(response.docContent()).isNull();
        assertThat(response.interactionContent()).isNull();
    }
}
