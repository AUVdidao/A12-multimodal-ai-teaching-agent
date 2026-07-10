package com.auvdidao.a12teachingagent.project;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Rollback
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createProjectReturnsCreatedProject() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "courseName": "数学",
                                  "chapterTitle": "分数的意义",
                                  "targetStudents": "小学五年级",
                                  "lessonDuration": 40,
                                  "description": "用于课堂导入与互动练习"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.courseName", is("数学")))
                .andExpect(jsonPath("$.data.chapterTitle", is("分数的意义")))
                .andExpect(jsonPath("$.data.modelMode", is("STANDARD")))
                .andExpect(jsonPath("$.data.status", is("CREATED")));
    }

    @Test
    void listProjectsReturnsExistingProject() throws Exception {
        createProject("语文", "说明文阅读");

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data[0].courseName", is("语文")))
                .andExpect(jsonPath("$.data[0].chapterTitle", is("说明文阅读")));
    }

    @Test
    void listModelModesReturnsThreeSupportedModes() throws Exception {
        mockMvc.perform(get("/api/model-modes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.length()", is(3)))
                .andExpect(jsonPath("$.data[0].code", is("STANDARD")))
                .andExpect(jsonPath("$.data[1].code", is("QUALITY")))
                .andExpect(jsonPath("$.data[2].code", is("ECONOMY")));
    }

    @Test
    void saveProjectModelModeUpdatesProjectMode() throws Exception {
        String locationJson = createProject("英语", "There be 句型");
        String projectId = locationJson.replaceAll(".*\\\"id\\\":(\\d+).*", "$1");

        mockMvc.perform(put("/api/projects/{projectId}/model-mode", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "QUALITY"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.projectId", is(Integer.parseInt(projectId))))
                .andExpect(jsonPath("$.data.mode", is("QUALITY")));

        mockMvc.perform(get("/api/projects/{projectId}/model-mode", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode", is("QUALITY")));
    }

    private String createProject(String courseName, String chapterTitle) throws Exception {
        return mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "courseName": "%s",
                                  "chapterTitle": "%s",
                                  "targetStudents": "初中一年级",
                                  "lessonDuration": 45,
                                  "description": "TA-006 controller test"
                                }
                                """.formatted(courseName, chapterTitle)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
