package com.auvdidao.a12teachingagent.ai;

import com.auvdidao.a12teachingagent.ai.api.AIWorkflowController;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.AiGatewayStatus;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.PlanSection;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.security.TokenAuthenticationService;
import com.auvdidao.a12teachingagent.security.A12SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AIWorkflowController.class)
@AutoConfigureMockMvc(addFilters = false)
class AIWorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AIWorkflowGateway aiWorkflowGateway;

    @MockBean
    private TokenAuthenticationService tokenAuthenticationService;

    @MockBean
    private A12SecurityProperties securityProperties;

    @Test
    void statusReturnsActiveMockProvider() throws Exception {
        when(aiWorkflowGateway.status()).thenReturn(new AiGatewayStatus(
                "MOCK",
                "MOCK",
                true,
                false,
                true,
                "Mock AI workflow is active. No external Dify key is required."
        ));

        mockMvc.perform(get("/api/ai-workflow/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.activeProvider", is("MOCK")))
                .andExpect(jsonPath("$.data.mockEnabled", is(true)))
                .andExpect(jsonPath("$.data.difyConfigured", is(false)));
    }

    @Test
    void clarificationEndpointReturnsStableContract() throws Exception {
        when(aiWorkflowGateway.clarifyRequirement(any(ClarificationRequest.class)))
                .thenReturn(new ClarificationResponse(
                        "mock-ai-workflow",
                        List.of("targetAudience"),
                        List.of("这节课面向哪个年级或学段的学生？"),
                        Map.of("targetAudience", "小学五年级"),
                        "请先补充缺失字段，再生成需求摘要。"
                ));

        mockMvc.perform(post("/api/ai-workflow/clarification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": 1,
                                  "rawRequirement": "帮我做一节数学分数课",
                                  "knownFields": ["courseName"],
                                  "generationMode": "MOCK"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.workflow", is("mock-ai-workflow")))
                .andExpect(jsonPath("$.data.missingFields[0]", is("targetAudience")))
                .andExpect(jsonPath("$.data.suggestedFields.targetAudience", is("小学五年级")));
    }

    @Test
    void generationPlanEndpointReturnsPptDocAndInteractionPlan() throws Exception {
        when(aiWorkflowGateway.createGenerationPlan(any(GenerationPlanRequest.class)))
                .thenReturn(new GenerationPlanResponse(
                        "mock-ai-workflow",
                        "plan-mock-1",
                        List.of(new PlanSection("封面与学习目标", List.of("分数的意义"), "需求摘要")),
                        List.of(new PlanSection("教学目标", List.of("知识目标"), "需求摘要")),
                        List.of("生成 3 道互动问答"),
                        "约 15 秒",
                        "请确认生成方案。"
                ));

        mockMvc.perform(post("/api/ai-workflow/generation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": 1,
                                  "courseName": "数学",
                                  "chapterTopic": "分数的意义",
                                  "targetAudience": "小学五年级",
                                  "outputTypes": ["PPT", "DOCX", "INTERACTION"],
                                  "generationMode": "STANDARD"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.planId", is("plan-mock-1")))
                .andExpect(jsonPath("$.data.pptOutline[0].title", is("封面与学习目标")))
                .andExpect(jsonPath("$.data.interactionPlan[0]", containsString("互动问答")));
    }

    @Test
    void invalidClarificationRequestReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/ai-workflow/clarification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": 1,
                                  "rawRequirement": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.message", containsString("rawRequirement")));
    }
}
