package com.auvdidao.a12teachingagent.requirement;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase
@Transactional
class RequirementInputControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void savesTeachingRequirementSuccessfully() throws Exception {
        Project project = createProject();
        Map<String, Object> request = Map.of(
                "gradeLevel", "五年级",
                "subject", "数学",
                "topic", "分数的意义",
                "lessonDuration", "45分钟",
                "teachingGoals", "理解分数表示整体与部分的关系",
                "keyPoints", "分数意义理解",
                "difficultPoints", "单位1的理解",
                "outputTypes", List.of("PPT", "DOCX"),
                "rawRequirementText", "帮我设计一节五年级数学课，主题是分数的意义。"
        );

        mockMvc.perform(post("/api/projects/{projectId}/requirements", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.projectId", is(project.getId().intValue())))
                .andExpect(jsonPath("$.data.topic", is("分数的意义")))
                .andExpect(jsonPath("$.data.rawRequirementText", is("帮我设计一节五年级数学课，主题是分数的意义。")))
                .andExpect(jsonPath("$.data.outputTypes", contains("PPT", "DOCX")))
                .andExpect(jsonPath("$.data.createdAt", notNullValue()))
                .andExpect(jsonPath("$.data.updatedAt", notNullValue()));
    }

    @Test
    void findsLatestTeachingRequirementSuccessfully() throws Exception {
        Project project = createProject();
        Map<String, Object> request = Map.of(
                "topic", "水循环",
                "rawRequirementText", "生成一节科学课",
                "outputTypes", List.of("PPT")
        );

        mockMvc.perform(post("/api/projects/{projectId}/requirements", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/{projectId}/requirements/latest", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.projectId", is(project.getId().intValue())))
                .andExpect(jsonPath("$.data.topic", is("水循环")))
                .andExpect(jsonPath("$.data.rawRequirementText", is("生成一节科学课")))
                .andExpect(jsonPath("$.data.outputTypes", contains("PPT")));
    }

    @Test
    void returnsNotFoundWhenProjectDoesNotExist() throws Exception {
        Map<String, Object> request = Map.of(
                "topic", "不存在项目的需求",
                "rawRequirementText", ""
        );

        mockMvc.perform(post("/api/projects/{projectId}/requirements", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(404)))
                .andExpect(jsonPath("$.message", is("项目不存在")));
    }

    @Test
    void rejectsWhenTopicAndRawRequirementTextAreBothBlank() throws Exception {
        Project project = createProject();
        Map<String, Object> request = Map.of(
                "topic", " ",
                "rawRequirementText", " "
        );

        mockMvc.perform(post("/api/projects/{projectId}/requirements", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("topic 和 rawRequirementText 至少填写一个")));
    }

    private Project createProject() {
        Project project = new Project();
        project.setProjectName("测试项目");
        project.setCourseName("数学");
        project.setChapterTopic("分数");
        project.setTargetAudience("五年级");
        project.setLessonDurationMinutes(45);
        project.setGenerationMode(GenerationMode.MOCK);
        project.setStatus(ProjectStatus.CREATED);
        return projectRepository.saveAndFlush(project);
    }
}
