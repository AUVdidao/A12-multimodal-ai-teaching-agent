package com.auvdidao.a12teachingagent.summary;

import com.auvdidao.a12teachingagent.domain.common.DialogRole;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.InputType;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.dialog.DialogMessage;
import com.auvdidao.a12teachingagent.domain.dialog.repository.DialogMessageRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementInputRepository;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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
class RequirementSummaryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequirementInputRepository requirementInputRepository;

    @Autowired
    private RequirementSummaryRepository requirementSummaryRepository;

    @Autowired
    private DialogMessageRepository dialogMessageRepository;

    @Test
    void generatesSummaryFromLatestRequirement() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);
        saveRequirement(project.getId(), "旧课题", true);
        RequirementInput latest = saveRequirement(project.getId(), "光合作用", true);

        mockMvc.perform(post("/api/projects/{projectId}/requirement-summaries/generate", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceRequirementId", is(latest.getId().intValue())))
                .andExpect(jsonPath("$.data.topic", is("光合作用")))
                .andExpect(jsonPath("$.data.outputTypes", contains("PPT", "LESSON_PLAN")))
                .andExpect(jsonPath("$.data.status", is("DRAFT")));
    }

    @Test
    void includesNormalizedProjectGenerationMode() throws Exception {
        Project project = createProject(GenerationMode.HIGH_QUALITY);
        saveRequirement(project.getId(), "光合作用", true);

        generate(project.getId())
                .andExpect(jsonPath("$.data.generationMode", is("QUALITY")));
    }

    @Test
    void teacherDialogueAddsStylePreference() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);
        saveRequirement(project.getId(), "光合作用", true);
        saveTeacherDialogue(project.getId(), "希望采用科技风格，页面保持清晰。", 1);

        generate(project.getId())
                .andExpect(jsonPath("$.data.stylePreference", is("科技风格")));
    }

    @Test
    void dialogueDoesNotOverwriteStructuredRequirementFields() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);
        saveRequirement(project.getId(), "光合作用", true);
        saveTeacherDialogue(project.getId(), "把课题改成细胞呼吸，并采用简洁风格。", 1);

        generate(project.getId())
                .andExpect(jsonPath("$.data.topic", is("光合作用")))
                .andExpect(jsonPath("$.data.stylePreference", is("简洁风格")));
    }

    @Test
    void generationWithoutRequirementReturnsConflict() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);

        mockMvc.perform(post("/api/projects/{projectId}/requirement-summaries/generate", project.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(409)));
    }

    @Test
    void generationForMissingProjectReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/projects/999999/requirement-summaries/generate"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(404)));
    }

    @Test
    void invalidProjectIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/projects/0/requirement-summaries/generate"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));
    }

    @Test
    void latestReturnsGeneratedSummary() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);
        saveRequirement(project.getId(), "光合作用", true);
        long summaryId = summaryId(generate(project.getId()).andReturn());

        mockMvc.perform(get("/api/projects/{projectId}/requirement-summaries/latest", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is((int) summaryId)))
                .andExpect(jsonPath("$.data.topic", is("光合作用")));
    }

    @Test
    void updatesDraftSummary() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);
        saveRequirement(project.getId(), "光合作用", true);
        long summaryId = summaryId(generate(project.getId()).andReturn());

        update(project.getId(), summaryId, "光合作用探究", "清新风格")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topic", is("光合作用探究")))
                .andExpect(jsonPath("$.data.stylePreference", is("清新风格")));
    }

    @Test
    void confirmsSummaryAndUpdatesProjectStatus() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);
        saveRequirement(project.getId(), "光合作用", true);
        long summaryId = summaryId(generate(project.getId()).andReturn());

        confirm(project.getId(), summaryId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.data.confirmedAt", notNullValue()));

        org.assertj.core.api.Assertions.assertThat(projectRepository.findById(project.getId()).orElseThrow().getStatus())
                .isEqualTo(ProjectStatus.REQUIREMENT_CONFIRMED);
    }

    @Test
    void repeatedConfirmationIsIdempotent() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);
        saveRequirement(project.getId(), "光合作用", true);
        long summaryId = summaryId(generate(project.getId()).andReturn());
        String firstConfirmedAt = JsonPath.read(confirm(project.getId(), summaryId).andReturn()
                .getResponse().getContentAsString(), "$.data.confirmedAt");

        confirm(project.getId(), summaryId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.data.confirmedAt", is(firstConfirmedAt)));
    }

    @Test
    void rejectsSummaryFromAnotherProject() throws Exception {
        Project owner = createProject(GenerationMode.STANDARD);
        Project other = createProject(GenerationMode.STANDARD);
        saveRequirement(owner.getId(), "光合作用", true);
        long summaryId = summaryId(generate(owner.getId()).andReturn());

        update(other.getId(), summaryId, "错误项目", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(404)));
    }

    @Test
    void confirmedSummaryCannotBeEdited() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);
        saveRequirement(project.getId(), "光合作用", true);
        long summaryId = summaryId(generate(project.getId()).andReturn());
        confirm(project.getId(), summaryId).andExpect(status().isOk());

        update(project.getId(), summaryId, "再次修改", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(409)));
    }

    @Test
    void incompleteSummaryCannotBeConfirmed() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);
        saveRequirement(project.getId(), "光合作用", false);
        long summaryId = summaryId(generate(project.getId()).andReturn());

        confirm(project.getId(), summaryId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("incomplete")));
    }

    @Test
    void latestReturnsNullBeforeGeneration() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);

        mockMvc.perform(get("/api/projects/{projectId}/requirement-summaries/latest", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", nullValue()));
    }

    @Test
    void nullUpdateBodyReturnsBadRequest() throws Exception {
        Project project = createProject(GenerationMode.STANDARD);
        saveRequirement(project.getId(), "光合作用", true);
        long summaryId = summaryId(generate(project.getId()).andReturn());

        mockMvc.perform(put(
                        "/api/projects/{projectId}/requirement-summaries/{summaryId}",
                        project.getId(),
                        summaryId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));
    }

    private org.springframework.test.web.servlet.ResultActions generate(Long projectId) throws Exception {
        return mockMvc.perform(post("/api/projects/{projectId}/requirement-summaries/generate", projectId))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions update(
            Long projectId,
            long summaryId,
            String topic,
            String stylePreference
    ) throws Exception {
        return mockMvc.perform(put(
                        "/api/projects/{projectId}/requirement-summaries/{summaryId}",
                        projectId,
                        summaryId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "gradeLevel": "八年级",
                          "subject": "生物",
                          "topic": "%s",
                          "lessonDuration": "45分钟",
                          "teachingGoals": "解释光合作用的基本过程",
                          "keyPoints": "光合作用条件",
                          "difficultPoints": "物质与能量转化",
                          "outputTypes": ["PPT", "LESSON_PLAN"],
                          "stylePreference": %s
                        }
                        """.formatted(topic, stylePreference == null ? "null" : "\"" + stylePreference + "\"")));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(Long projectId, long summaryId) throws Exception {
        return mockMvc.perform(post(
                "/api/projects/{projectId}/requirement-summaries/{summaryId}/confirm",
                projectId,
                summaryId
        ));
    }

    private long summaryId(MvcResult result) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
        return value.longValue();
    }

    private Project createProject(GenerationMode generationMode) {
        Project project = new Project();
        project.setProjectName("M1 summary test");
        project.setCourseName("生物");
        project.setChapterTopic("光合作用");
        project.setGenerationMode(generationMode);
        project.setStatus(ProjectStatus.CREATED);
        return projectRepository.save(project);
    }

    private RequirementInput saveRequirement(Long projectId, String topic, boolean complete) {
        RequirementInput requirement = new RequirementInput();
        requirement.setProjectId(projectId);
        requirement.setTopic(topic);
        requirement.setInputType(InputType.TEXT);
        if (complete) {
            requirement.setGradeLevel("八年级");
            requirement.setSubject("生物");
            requirement.setLessonDuration("45分钟");
            requirement.setTeachingGoals("解释光合作用的基本过程");
            requirement.setKeyPoints("光合作用条件");
            requirement.setDifficultPoints("物质与能量转化");
            requirement.setOutputTypes(List.of("PPT", "LESSON_PLAN"));
        }
        return requirementInputRepository.save(requirement);
    }

    private void saveTeacherDialogue(Long projectId, String content, int roundNo) {
        DialogMessage message = new DialogMessage();
        message.setProjectId(projectId);
        message.setSessionId("project-" + projectId + "-clarification");
        message.setRole(DialogRole.TEACHER);
        message.setContent(content);
        message.setRoundNo(roundNo);
        dialogMessageRepository.save(message);
    }
}
