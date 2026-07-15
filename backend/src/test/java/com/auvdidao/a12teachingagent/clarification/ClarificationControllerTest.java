package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationResponse;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.security.A12SecurityProperties;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.auvdidao.a12teachingagent.security.TokenAuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClarificationController.class)
@Import(ClarificationService.class)
@AutoConfigureMockMvc(addFilters = false)
class ClarificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AIWorkflowGateway aiWorkflowGateway;

    @MockBean
    private ProjectRepository projectRepository;

    @MockBean
    private ProjectAccessService projectAccessService;

    @MockBean
    private TokenAuthenticationService tokenAuthenticationService;

    @MockBean
    private A12SecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        when(projectRepository.existsById(anyLong())).thenReturn(true);
    }

    @Test
    void completeRequirementReturnsCompleteWithoutCallingGateway() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeRequirement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.complete", is(true)))
                .andExpect(jsonPath("$.data.missingFields", hasSize(0)))
                .andExpect(jsonPath("$.data.questions", hasSize(0)));

        verifyNoInteractions(aiWorkflowGateway);
    }

    @Test
    void emptyRequirementReturnsAllRequiredFieldsInStableOrder() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complete", is(false)))
                .andExpect(jsonPath("$.data.missingFields[*].field", contains(
                        "gradeLevel", "subject", "topic", "lessonDuration", "teachingGoals", "outputTypes"
                )))
                .andExpect(jsonPath("$.data.questions", hasSize(0)));
    }

    @Test
    void singleMissingFieldReturnsOnlyThatField() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "subject": "数学",
                                  "topic": "分数的意义",
                                  "lessonDuration": "45分钟",
                                  "teachingGoals": "理解分数表示整体与部分的关系",
                                  "outputTypes": ["PPT"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingFields", hasSize(1)))
                .andExpect(jsonPath("$.data.missingFields[0].field", is("gradeLevel")));
    }

    @Test
    void multipleMissingFieldsReturnOnlyMissingFields() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "subject": "数学", "outputTypes": ["PPT"] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingFields[*].field", contains(
                        "gradeLevel", "topic", "lessonDuration", "teachingGoals"
                )));
    }

    @Test
    void blankAndWhitespaceValuesAreMissing() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gradeLevel": " ", "subject": "", "topic": "   ",
                                  "lessonDuration": " ", "teachingGoals": "", "outputTypes": ["   "]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingFields", hasSize(6)));
    }

    @Test
    void emptyOutputTypesAreMissingWhenRawTextHasNoOutputType() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gradeLevel": "五年级", "subject": "数学", "topic": "分数的意义",
                                  "lessonDuration": "45分钟", "teachingGoals": "理解分数意义",
                                  "outputTypes": [], "rawRequirementText": "帮我上一节五年级数学分数课"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingFields", hasSize(1)))
                .andExpect(jsonPath("$.data.missingFields[0].field", is("outputTypes")));
    }

    @Test
    void rawPptTextInfersOutputType() throws Exception {
        assertRawOutputTypeIsRecognized("帮我做一节数学课 PPT");
    }

    @Test
    void rawCoursewareTextInfersOutputType() throws Exception {
        assertRawOutputTypeIsRecognized("帮我生成一份数学课件");
    }

    @Test
    void negatedPptTextDoesNotInferOutputType() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gradeLevel": "五年级", "subject": "数学", "topic": "分数的意义",
                                  "lessonDuration": "45分钟", "teachingGoals": "理解分数意义",
                                  "rawRequirementText": "不要生成 PPT"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingFields[*].field", hasItem("outputTypes")));
    }

    @Test
    void questionsUseGatewayReturnedText() throws Exception {
        when(aiWorkflowGateway.clarifyRequirement(any(ClarificationRequest.class)))
                .thenReturn(new ClarificationResponse(
                        "mock-ai-workflow",
                        List.of("gradeLevel"),
                        List.of("网关追问：请说明学生年级。"),
                        Map.of(),
                        "请补充缺失字段"
                ));

        mockMvc.perform(post("/api/projects/1/clarification/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "subject": "数学", "topic": "分数的意义", "lessonDuration": "45分钟",
                                  "teachingGoals": "理解分数意义", "outputTypes": ["PPT"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingFields", hasSize(1)))
                .andExpect(jsonPath("$.data.questions", contains("网关追问：请说明学生年级。")));

        ArgumentCaptor<ClarificationRequest> requestCaptor = ArgumentCaptor.forClass(ClarificationRequest.class);
        verify(aiWorkflowGateway).clarifyRequirement(requestCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(requestCaptor.getValue().requestedMissingFields())
                .containsExactly("gradeLevel");
    }

    @Test
    void questionsDoNotRepeatInferredOutputType() throws Exception {
        List<String> missingFields = List.of("gradeLevel", "topic", "lessonDuration", "teachingGoals");
        when(aiWorkflowGateway.clarifyRequirement(any(ClarificationRequest.class)))
                .thenReturn(responseFor(missingFields));

        mockMvc.perform(post("/api/projects/1/clarification/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawRequirementText\":\"帮我生成一份数学课件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingFields[*].field", not(hasItem("outputTypes"))))
                .andExpect(jsonPath("$.data.questions", hasSize(4)));
    }

    @Test
    void completeQuestionsRequestDoesNotCallGateway() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeRequirement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complete", is(true)))
                .andExpect(jsonPath("$.data.questions", hasSize(0)));

        verify(aiWorkflowGateway, never()).clarifyRequirement(any());
    }

    @Test
    void gatewayFailureReturnsServiceUnavailable() throws Exception {
        when(aiWorkflowGateway.clarifyRequirement(any(ClarificationRequest.class)))
                .thenThrow(new AiWorkflowUnavailableException("Mock gateway unavailable"));

        mockMvc.perform(post("/api/projects/1/clarification/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is(503)))
                .andExpect(jsonPath("$.message", containsString("unavailable")));
    }

    @Test
    void zeroProjectIdReturnsBadRequest() throws Exception {
        expectBadRequest("/api/projects/0/clarification/check", "{}");
    }

    @Test
    void negativeProjectIdReturnsBadRequest() throws Exception {
        expectBadRequest("/api/projects/-1/clarification/check", "{}");
    }

    @Test
    void missingProjectReturnsNotFound() throws Exception {
        when(projectRepository.existsById(999999L)).thenReturn(false);

        mockMvc.perform(post("/api/projects/999999/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(404)))
                .andExpect(jsonPath("$.message", containsString("Project not found")));
    }

    @Test
    void nonNumericProjectIdReturnsBadRequest() throws Exception {
        expectBadRequest("/api/projects/not-a-number/clarification/check", "{}");
    }

    @Test
    void emptyRequestBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        expectBadRequest("/api/projects/1/clarification/check", "{\"gradeLevel\":");
    }

    @Test
    void invalidOutputTypesTypeReturnsBadRequest() throws Exception {
        expectBadRequest(
                "/api/projects/1/clarification/check",
                "{\"outputTypes\":\"PPT\",\"rawRequirementText\":\"数学课\"}"
        );
    }

    private void assertRawOutputTypeIsRecognized(String rawRequirementText) throws Exception {
        String request = "{\"rawRequirementText\":\"" + rawRequirementText + "\"}";
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingFields[*].field", not(hasItem("outputTypes"))));
    }

    private void expectBadRequest(String path, String content) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    private String completeRequirement() {
        return """
                {
                  "gradeLevel": "五年级", "subject": "数学", "topic": "分数的意义",
                  "lessonDuration": "45分钟", "teachingGoals": "理解分数表示整体与部分的关系",
                  "keyPoints": "分数、整体、部分", "difficultPoints": "单位一的理解",
                  "outputTypes": ["PPT", "DOCX"]
                }
                """;
    }

    private ClarificationResponse responseFor(List<String> missingFields) {
        List<String> questions = missingFields.stream()
                .map(field -> ClarificationField.fromCode(field).orElseThrow().defaultQuestion())
                .toList();
        return new ClarificationResponse(
                "mock-ai-workflow",
                missingFields,
                questions,
                Map.of(),
                "请补充缺失字段"
        );
    }
}
