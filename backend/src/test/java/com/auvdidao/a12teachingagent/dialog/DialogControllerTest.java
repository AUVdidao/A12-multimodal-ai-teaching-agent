package com.auvdidao.a12teachingagent.dialog;

import com.auvdidao.a12teachingagent.domain.common.DialogRole;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.dialog.DialogMessage;
import com.auvdidao.a12teachingagent.domain.dialog.repository.DialogMessageRepository;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class DialogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DialogMessageRepository dialogMessageRepository;

    @Test
    void saveTeacherMessageReturnsSavedMessage() throws Exception {
        Long projectId = createProject();

        mockMvc.perform(post("/api/projects/{projectId}/dialogues", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "project-%d-clarification",
                                  "sender": "TEACHER",
                                  "content": "我想上一节五年级数学课。",
                                  "roundNo": 1
                                }
                                """.formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.projectId", is(projectId.intValue())))
                .andExpect(jsonPath("$.data.sessionId", is("project-%d-clarification".formatted(projectId))))
                .andExpect(jsonPath("$.data.sender", is("TEACHER")))
                .andExpect(jsonPath("$.data.content", is("我想上一节五年级数学课。")))
                .andExpect(jsonPath("$.data.roundNo", is(1)));
    }

    @Test
    void saveAiMessageReturnsSavedMessage() throws Exception {
        Long projectId = createProject();

        mockMvc.perform(post("/api/projects/{projectId}/dialogues", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "project-%d-clarification",
                                  "sender": "AI",
                                  "content": "这节课的教学目标是什么？",
                                  "roundNo": 1
                                }
                                """.formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.sender", is("AI")))
                .andExpect(jsonPath("$.data.content", is("这节课的教学目标是什么？")))
                .andExpect(jsonPath("$.data.roundNo", is(1)));
    }

    @Test
    void listProjectDialoguesReturnsProjectHistory() throws Exception {
        Long projectId = createProject();
        saveMessage(projectId, "TEACHER", "我想做分数课。", 1);
        saveMessage(projectId, "AI", "请补充教学目标。", 1);

        mockMvc.perform(get("/api/projects/{projectId}/dialogues", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.length()", is(2)))
                .andExpect(jsonPath("$.data[0].content", is("我想做分数课。")))
                .andExpect(jsonPath("$.data[1].content", is("请补充教学目标。")));
    }

    @Test
    void listSessionDialoguesReturnsSessionHistory() throws Exception {
        Long projectId = createProject();
        saveMessage(projectId, "TEACHER", "学生是五年级。", 2);

        mockMvc.perform(get("/api/dialogues/{sessionId}", "project-%d-clarification".formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].projectId", is(projectId.intValue())))
                .andExpect(jsonPath("$.data[0].roundNo", is(2)));
    }

    @Test
    void listProjectDialoguesUsesCreatedAtThenIdOrder() throws Exception {
        Long projectId = createProject();
        LocalDateTime sameCreatedAt = LocalDateTime.of(2026, 7, 9, 9, 30);

        DialogMessage first = buildMessage(projectId, DialogRole.TEACHER, "第一条同时间消息", 1, sameCreatedAt);
        DialogMessage second = buildMessage(projectId, DialogRole.ASSISTANT, "第二条同时间消息", 1, sameCreatedAt);
        dialogMessageRepository.saveAll(List.of(first, second));

        mockMvc.perform(get("/api/projects/{projectId}/dialogues", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(2)))
                .andExpect(jsonPath("$.data[0].content", is("第一条同时间消息")))
                .andExpect(jsonPath("$.data[1].content", is("第二条同时间消息")));
    }

    private void saveMessage(Long projectId, String sender, String content, int roundNo) throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/dialogues", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "sessionId": "project-%d-clarification",
                          "sender": "%s",
                          "content": "%s",
                          "roundNo": %d
                        }
                        """.formatted(projectId, sender, content, roundNo)))
                .andExpect(status().isOk());
    }

    private DialogMessage buildMessage(
            Long projectId,
            DialogRole role,
            String content,
            int roundNo,
            LocalDateTime createdAt
    ) {
        DialogMessage message = new DialogMessage();
        message.setProjectId(projectId);
        message.setSessionId("project-%d-clarification".formatted(projectId));
        message.setRole(role);
        message.setContent(content);
        message.setRoundNo(roundNo);
        message.setCreatedAt(createdAt);
        return message;
    }

    private Long createProject() {
        Project project = new Project();
        project.setProjectName("TA-009 测试项目");
        project.setCourseName("数学");
        project.setChapterTopic("分数的意义");
        project.setTargetAudience("小学五年级");
        project.setLessonDurationMinutes(40);
        project.setProjectDescription("对话记录测试");
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.CREATED);

        return projectRepository.save(project).getId();
    }
}
