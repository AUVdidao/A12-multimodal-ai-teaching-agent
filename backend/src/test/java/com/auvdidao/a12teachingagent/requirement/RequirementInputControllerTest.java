package com.auvdidao.a12teachingagent.requirement;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Rollback
class RequirementInputControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void savesCompleteRequirementAndOutputTypes() throws Exception {
        Long projectId = createProject();

        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeRequirement("分数的意义")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId", is(projectId.intValue())))
                .andExpect(jsonPath("$.data.topic", is("分数的意义")))
                .andExpect(jsonPath("$.data.outputTypes", hasSize(2)))
                .andExpect(jsonPath("$.data.outputTypes[0]", is("PPT")))
                .andExpect(jsonPath("$.data.outputTypes[1]", is("LESSON_PLAN")))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
    }

    @Test
    void savesIncompleteRequirementWithRawTextOnly() throws Exception {
        Long projectId = createProject();

        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawRequirementText\":\"帮我设计一节数学课\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawRequirementText", is("帮我设计一节数学课")))
                .andExpect(jsonPath("$.data.topic", nullValue()));
    }

    @Test
    void savesRequirementWithTopicOnly() throws Exception {
        Long projectId = createProject();

        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"光合作用\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topic", is("光合作用")))
                .andExpect(jsonPath("$.data.rawRequirementText", nullValue()));
    }

    @Test
    void rejectsWhenTopicAndRawTextAreBothBlank() throws Exception {
        Long projectId = createProject();

        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"  ","rawRequirementText":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));
    }

    @Test
    void rejectsZeroProjectId() throws Exception {
        expectBadProjectId(0);
    }

    @Test
    void rejectsNegativeProjectId() throws Exception {
        expectBadProjectId(-1);
    }

    @Test
    void rejectsMissingProject() throws Exception {
        mockMvc.perform(post("/api/projects/999999/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"光合作用\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(404)));
    }

    @Test
    void latestReturnsNewestVersion() throws Exception {
        Long projectId = createProject();
        saveRequirement(projectId, "第一版");
        saveRequirement(projectId, "第二版");

        mockMvc.perform(get("/api/projects/{projectId}/requirements/latest", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topic", is("第二版")));
    }

    @Test
    void latestReturnsNullWhenProjectHasNoRequirement() throws Exception {
        Long projectId = createProject();

        mockMvc.perform(get("/api/projects/{projectId}/requirements/latest", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", nullValue()));
    }

    @Test
    void trimsBlankOptionalFieldsAndDeduplicatesOutputTypes() throws Exception {
        Long projectId = createProject();

        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "topic": "  光合作用  ",
                                  "subject": "   ",
                                  "outputTypes": ["PPT", " PPT ", " "]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topic", is("光合作用")))
                .andExpect(jsonPath("$.data.subject", nullValue()))
                .andExpect(jsonPath("$.data.outputTypes", hasSize(1)))
                .andExpect(jsonPath("$.data.outputTypes[0]", is("PPT")));
    }

    private void expectBadProjectId(long projectId) throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"光合作用\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));
    }

    private void saveRequirement(Long projectId, String topic) throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"" + topic + "\"}"))
                .andExpect(status().isOk());
    }

    private Long createProject() {
        Project project = new Project();
        project.setProjectName("M1 requirement test");
        project.setCourseName("数学");
        project.setChapterTopic("分数的意义");
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.CREATED);
        return projectRepository.save(project).getId();
    }

    private String completeRequirement(String topic) {
        return """
                {
                  "gradeLevel": "五年级",
                  "subject": "数学",
                  "topic": "%s",
                  "lessonDuration": "45分钟",
                  "teachingGoals": "理解分数表示整体与部分的关系",
                  "keyPoints": "分数的意义",
                  "difficultPoints": "单位一",
                  "outputTypes": ["PPT", "LESSON_PLAN"],
                  "rawRequirementText": "请生成课堂课件与教案"
                }
                """.formatted(topic);
    }
}
