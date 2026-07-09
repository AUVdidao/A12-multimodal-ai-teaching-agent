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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class RequirementInputControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void saveRequirementReturnsSavedInput() throws Exception {
        Long projectId = createProject("数学", "分数的意义");

        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gradeLevel": "五年级",
                                  "subject": "数学",
                                  "topic": "分数的意义",
                                  "lessonDuration": "45分钟",
                                  "teachingGoals": "理解分数表示整体与部分的关系",
                                  "keyPoints": "分数意义理解",
                                  "difficultPoints": "单位1的理解",
                                  "outputTypes": ["PPT", "DOCX"],
                                  "rawRequirementText": "帮我设计一节五年级数学课。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.projectId", is(projectId.intValue())))
                .andExpect(jsonPath("$.data.topic", is("分数的意义")))
                .andExpect(jsonPath("$.data.rawRequirementText", is("帮我设计一节五年级数学课。")))
                .andExpect(jsonPath("$.data.outputTypes", contains("PPT", "DOCX")))
                .andExpect(jsonPath("$.data.createdAt", notNullValue()))
                .andExpect(jsonPath("$.data.updatedAt", notNullValue()));
    }

    @Test
    void latestReturnsOnlyCurrentProjectNewestInput() throws Exception {
        Long firstProjectId = createProject("数学", "分数的意义");
        Long secondProjectId = createProject("科学", "水循环");

        saveRequirement(firstProjectId, "第一条", "第一个项目旧需求");
        saveRequirement(secondProjectId, "第二项目", "第二个项目需求");
        saveRequirement(firstProjectId, "最新课题", "第一个项目最新需求");

        mockMvc.perform(get("/api/projects/{projectId}/requirements/latest", firstProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.projectId", is(firstProjectId.intValue())))
                .andExpect(jsonPath("$.data.topic", is("最新课题")))
                .andExpect(jsonPath("$.data.rawRequirementText", is("第一个项目最新需求")));
    }

    @Test
    void saveAllowsTopicOnly() throws Exception {
        Long projectId = createProject("语文", "说明文阅读");

        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "topic": "说明文阅读",
                                  "rawRequirementText": ""
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topic", is("说明文阅读")));
    }

    @Test
    void saveAllowsRawRequirementTextOnly() throws Exception {
        Long projectId = createProject("英语", "There be 句型");

        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "topic": "",
                                  "rawRequirementText": "帮我设计一节英语语法课。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawRequirementText", is("帮我设计一节英语语法课。")));
    }

    @Test
    void saveRejectsBlankTopicAndRawRequirementText() throws Exception {
        Long projectId = createProject("数学", "小数乘法");

        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "topic": " ",
                                  "rawRequirementText": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));
    }

    @Test
    void saveReturnsNotFoundWhenProjectDoesNotExist() throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/requirements", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "topic": "不存在项目",
                                  "rawRequirementText": ""
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(404)));
    }

    private void saveRequirement(Long projectId, String topic, String rawRequirementText) throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/requirements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "topic": "%s",
                                  "rawRequirementText": "%s"
                                }
                                """.formatted(topic, rawRequirementText)))
                .andExpect(status().isOk());
    }

    private Long createProject(String courseName, String chapterTopic) {
        Project project = new Project();
        project.setProjectName(courseName + " - " + chapterTopic);
        project.setCourseName(courseName);
        project.setChapterTopic(chapterTopic);
        project.setTargetAudience("小学五年级");
        project.setLessonDurationMinutes(40);
        project.setProjectDescription("TA-007 requirement input test");
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.CREATED);

        return projectRepository.save(project).getId();
    }
}
