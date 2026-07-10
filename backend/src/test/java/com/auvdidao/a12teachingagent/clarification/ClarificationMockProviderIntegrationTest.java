package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ClarificationMockProviderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AIWorkflowGateway aiWorkflowGateway;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void realMockRouterWorksWithoutDifyKeyAndReturnsQuestions() throws Exception {
        assertThat(aiWorkflowGateway.status().activeProvider()).isEqualTo("MOCK");
        assertThat(aiWorkflowGateway.status().difyConfigured()).isFalse();

        Long projectId = createProject();
        mockMvc.perform(post("/api/projects/{projectId}/clarification/questions", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawRequirementText\":\"帮我生成一份数学课件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complete", is(false)))
                .andExpect(jsonPath("$.data.missingFields[*].field", not(hasItem("outputTypes"))))
                .andExpect(jsonPath("$.data.questions", hasSize(4)))
                .andExpect(jsonPath("$.data.questions[0]", is("请补充本节课面向哪个年级学生？")));
    }

    private Long createProject() {
        Project project = new Project();
        project.setProjectName("TA-008 Mock 集成测试");
        project.setCourseName("数学");
        project.setChapterTopic("分数的意义");
        project.setTargetAudience("小学五年级");
        project.setLessonDurationMinutes(45);
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.CREATED);
        return projectRepository.save(project).getId();
    }
}
