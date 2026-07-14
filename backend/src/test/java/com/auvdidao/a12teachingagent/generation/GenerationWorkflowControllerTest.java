package com.auvdidao.a12teachingagent.generation;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.GenerationPlan;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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
class GenerationWorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TeachingIntentRepository intentRepository;

    @Autowired
    private GenerationPlanRepository planRepository;

    @Autowired
    private GeneratedArtifactRepository artifactRepository;

    @Autowired
    private ArtifactVersionRepository versionRepository;

    @Test
    void requiresConfirmedIntentAndUsesTheLatestConfirmedIntent() throws Exception {
        Project blockedProject = createProject("Blocked generation");
        createIntent(blockedProject, TeachingIntentStatus.DRAFT, "Draft goal");

        mockMvc.perform(post("/api/projects/{projectId}/generation-plans", blockedProject.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("confirmed teaching intent")));

        Project project = createProject("Latest confirmed intent");
        TeachingIntent confirmed = createIntent(project, TeachingIntentStatus.CONFIRMED, "Confirmed goal");
        createIntent(project, TeachingIntentStatus.DRAFT, "Newer draft goal");

        createPlan(project.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider", is("MOCK")));

        GenerationPlan saved = planRepository
                .findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId())
                .orElseThrow();
        assertThat(saved.getTeachingIntentId()).isEqualTo(confirmed.getId());
    }

    @Test
    void createsEditsConfirmsAndRestoresGenerationPlan() throws Exception {
        Project project = createProject("Plan lifecycle");
        createIntent(project, TeachingIntentStatus.CONFIRMED, "Understand the topic and apply it");

        mockMvc.perform(get("/api/projects/{projectId}/generation/workspace", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider", is("MOCK")))
                .andExpect(jsonPath("$.data.latestPlan", nullValue()))
                .andExpect(jsonPath("$.data.teachingIntent.contentBasis", is("Confirmed requirements and local teaching evidence")))
                .andExpect(jsonPath("$.data.teachingIntent.teachingApproach", is("Case-based explanation and guided practice")))
                .andExpect(jsonPath("$.data.capabilities.canCreatePlan", is(true)))
                .andExpect(jsonPath("$.data.capabilities.canEditPlan", is(false)));

        MvcResult created = createPlan(project.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider", is("MOCK")))
                .andExpect(jsonPath("$.data.pptOutline", not(empty())))
                .andExpect(jsonPath("$.data.docOutline", not(empty())))
                .andExpect(jsonPath("$.data.interactionPlan", not(empty())))
                .andExpect(jsonPath("$.data.confirmed", is(false)))
                .andReturn();
        long planId = responseDataId(created);

        mockMvc.perform(put("/api/projects/{projectId}/generation-plans/{planId}", project.getId(), planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pptOutline": [
                                    {"order": 1, "title": "Edited PPT outline", "description": "Teacher reviewed deck structure"}
                                  ],
                                  "docOutline": [
                                    {"order": 1, "title": "Edited lesson plan", "description": "Teacher reviewed lesson structure"}
                                  ],
                                  "interactionPlan": ["Opening diagnostic question", "Three-question classroom quiz"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pptOutline[0].title", is("Edited PPT outline")))
                .andExpect(jsonPath("$.data.docOutline[0].description", is("Teacher reviewed lesson structure")))
                .andExpect(jsonPath("$.data.interactionPlan", contains("Opening diagnostic question", "Three-question classroom quiz")));

        mockMvc.perform(get("/api/projects/{projectId}/generation-plans/latest", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is((int) planId)))
                .andExpect(jsonPath("$.data.pptOutline[0].title", is("Edited PPT outline")));

        mockMvc.perform(post("/api/projects/{projectId}/generation-plans/{planId}/confirm", project.getId(), planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed", is(true)));
        mockMvc.perform(post("/api/projects/{projectId}/generation-plans/{planId}/confirm", project.getId(), planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed", is(true)));

        mockMvc.perform(put("/api/projects/{projectId}/generation-plans/{planId}", project.getId(), planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pptOutline": [{"order": 1, "title": "Locked", "description": "Locked"}],
                                  "docOutline": [{"order": 1, "title": "Locked", "description": "Locked"}],
                                  "interactionPlan": ["Locked"]
                                }
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/projects/{projectId}/generation/workspace", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestPlan.id", is((int) planId)))
                .andExpect(jsonPath("$.data.latestPlan.confirmed", is(true)))
                .andExpect(jsonPath("$.data.capabilities.canEditPlan", is(false)))
                .andExpect(jsonPath("$.data.capabilities.canConfirmPlan", is(false)))
                .andExpect(jsonPath("$.data.capabilities.canGenerate", is(true)))
                .andExpect(jsonPath("$.data.capabilities.canGenerateArtifacts", is(true)));
    }

    @Test
    void refusesArtifactGenerationUntilPlanIsConfirmed() throws Exception {
        Project project = createProject("Unconfirmed plan");
        createIntent(project, TeachingIntentStatus.CONFIRMED, "Confirmed teaching goal");
        long planId = responseDataId(createPlan(project.getId()).andReturn());

        generateArtifacts(project.getId(), planId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("must be confirmed")));

        assertThat(artifactRepository.findByProjectIdOrderByCreatedAtAsc(project.getId())).isEmpty();
        assertThat(versionRepository.findByProjectIdOrderByCreatedAtAsc(project.getId())).isEmpty();
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getStatus())
                .isNotEqualTo(ProjectStatus.GENERATED);
    }

    @Test
    void generatesPreviewableV1ArtifactsIdempotentlyAndMarksProjectGenerated() throws Exception {
        Project project = createProject("Artificial intelligence foundations");
        createIntent(project, TeachingIntentStatus.CONFIRMED, "Explain core AI concepts and apply them in cases");
        long planId = responseDataId(createPlan(project.getId()).andReturn());
        confirmPlan(project.getId(), planId);

        MvcResult first = generateArtifacts(project.getId(), planId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].type", is("PPT")))
                .andExpect(jsonPath("$.data[0].schemaVersion", is(1)))
                .andExpect(jsonPath("$.data[0].generationPlanId", is((int) planId)))
                .andExpect(jsonPath("$.data[0].versionNumber", is(1)))
                .andExpect(jsonPath("$.data[0].content.slides", hasSize(7)))
                .andExpect(jsonPath("$.data[0].content.slides[*].index", hasSize(7)))
                .andExpect(jsonPath("$.data[0].content.slides[*].title", hasSize(7)))
                .andExpect(jsonPath("$.data[0].content.slides[*].layout", hasSize(7)))
                .andExpect(jsonPath("$.data[0].content.slides[*].points", hasSize(7)))
                .andExpect(jsonPath("$.data[0].content.slides[*].speakerNotes", hasSize(7)))
                .andExpect(jsonPath("$.data[0].content.slides[*].kind", contains(
                        "COVER", "AGENDA", "OBJECTIVES", "CONTENT", "CASE", "INTERACTION", "SUMMARY"
                )))
                .andExpect(jsonPath("$.data[1].type", is("DOCX")))
                .andExpect(jsonPath("$.data[1].content.courseInfo", notNullValue()))
                .andExpect(jsonPath("$.data[1].content.teachingGoals", not(empty())))
                .andExpect(jsonPath("$.data[1].content.keyPoints", not(empty())))
                .andExpect(jsonPath("$.data[1].content.difficultPoints", not(empty())))
                .andExpect(jsonPath("$.data[1].content.methods", not(empty())))
                .andExpect(jsonPath("$.data[1].content.teachingProcess", not(empty())))
                .andExpect(jsonPath("$.data[1].content.classroomActivities", not(empty())))
                .andExpect(jsonPath("$.data[1].content.homework", not(empty())))
                .andExpect(jsonPath("$.data[1].content.resourceNotes", not(empty())))
                .andExpect(jsonPath("$.data[1].content.sections", hasSize(9)))
                .andExpect(jsonPath("$.data[2].type", is("INTERACTION")))
                .andExpect(jsonPath("$.data[2].content.questions", hasSize(3)))
                .andExpect(jsonPath("$.data[2].content.questions[*].question", hasSize(3)))
                .andExpect(jsonPath("$.data[2].content.questions[*].options", hasSize(3)))
                .andExpect(jsonPath("$.data[2].content.questions[*].correctOption", hasSize(3)))
                .andExpect(jsonPath("$.data[2].content.questions[*].correctAnswer", contains("B", "A", "C")))
                .andExpect(jsonPath("$.data[2].content.questions[*].explanation", hasSize(3)))
                .andReturn();

        JsonNode firstData = responseData(first);
        long versionId = firstData.get(0).path("versionId").asLong();
        List<Long> firstArtifactIds = artifactIds(firstData);
        for (JsonNode artifact : firstData) {
            assertThat(artifact.path("generationPlanId").asLong()).isEqualTo(planId);
            assertThat(artifact.path("versionId").asLong()).isEqualTo(versionId);
            assertThat(artifact.path("schemaVersion").asInt()).isEqualTo(1);
        }

        MvcResult repeated = generateArtifacts(project.getId(), planId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andReturn();
        assertThat(artifactIds(responseData(repeated))).containsExactlyElementsOf(firstArtifactIds);
        assertThat(artifactRepository.findByProjectIdOrderByCreatedAtAsc(project.getId())).hasSize(3);
        assertThat(versionRepository.findByProjectIdOrderByCreatedAtAsc(project.getId())).hasSize(1);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getStatus())
                .isEqualTo(ProjectStatus.GENERATED);

        List<GeneratedArtifact> persisted = artifactRepository.findByProjectIdOrderByCreatedAtAsc(project.getId());
        for (GeneratedArtifact artifact : persisted) {
            assertThat(artifact.getGenerationPlanId()).isEqualTo(planId);
            assertThat(artifact.getVersionId()).isEqualTo(versionId);
            assertThat(artifact.getSchemaVersion()).isEqualTo(1);
            assertThat(artifact.getFilePath()).isNull();
            assertThat(objectMapper.readTree(artifact.getContentJson()).isObject()).isTrue();
        }

        mockMvc.perform(get("/api/projects/{projectId}/artifacts", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)));
        mockMvc.perform(get("/api/projects/{projectId}/artifacts/{artifactId}", project.getId(), firstArtifactIds.get(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.slides", hasSize(7)));
        mockMvc.perform(get("/api/projects/{projectId}/generation/workspace", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectStatus", is("GENERATED")))
                .andExpect(jsonPath("$.data.artifacts", hasSize(3)))
                .andExpect(jsonPath("$.data.capabilities.canGenerate", is(false)))
                .andExpect(jsonPath("$.data.capabilities.canGenerateArtifacts", is(false)))
                .andExpect(jsonPath("$.data.capabilities.canPreview", is(true)));
    }

    @Test
    void isolatesPlansAndArtifactsByProject() throws Exception {
        Project firstProject = createProject("First project");
        Project secondProject = createProject("Second project");
        createIntent(firstProject, TeachingIntentStatus.CONFIRMED, "First goal");
        createIntent(secondProject, TeachingIntentStatus.CONFIRMED, "Second goal");
        long firstPlanId = responseDataId(createPlan(firstProject.getId()).andReturn());
        confirmPlan(firstProject.getId(), firstPlanId);
        long firstArtifactId = artifactIds(responseData(generateArtifacts(firstProject.getId(), firstPlanId).andReturn())).get(0);

        mockMvc.perform(get("/api/projects/{projectId}/artifacts", secondProject.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", empty()));
        mockMvc.perform(get("/api/projects/{projectId}/artifacts/{artifactId}", secondProject.getId(), firstArtifactId))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/{projectId}/generation-plans/{planId}/confirm", secondProject.getId(), firstPlanId))
                .andExpect(status().isNotFound());
        generateArtifacts(secondProject.getId(), firstPlanId)
                .andExpect(status().isNotFound());

        assertThat(artifactRepository.findByProjectIdOrderByCreatedAtAsc(firstProject.getId())).hasSize(3);
        assertThat(artifactRepository.findByProjectIdOrderByCreatedAtAsc(secondProject.getId())).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions createPlan(Long projectId) throws Exception {
        return mockMvc.perform(post("/api/projects/{projectId}/generation-plans", projectId));
    }

    private void confirmPlan(Long projectId, Long planId) throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/generation-plans/{planId}/confirm", projectId, planId))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions generateArtifacts(Long projectId, Long planId) throws Exception {
        return mockMvc.perform(post("/api/projects/{projectId}/artifacts/generate", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"planId\":" + planId + "}"));
    }

    private Project createProject(String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setCourseName("AI foundations");
        project.setChapterTopic(name);
        project.setTargetAudience("First-year university students");
        project.setLessonDurationMinutes(90);
        project.setProjectDescription("A deterministic M3 content-generation test project");
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.INTENT_CONFIRMED);
        return projectRepository.save(project);
    }

    private TeachingIntent createIntent(Project project, TeachingIntentStatus status, String goal) {
        TeachingIntent intent = new TeachingIntent();
        intent.setProjectId(project.getId());
        intent.setGenerationGoal(goal);
        intent.setGenerationGoals(List.of(goal, "Apply the topic in a case", "Complete a classroom check"));
        intent.setContentBasis("Confirmed requirements and local teaching evidence");
        intent.setTeachingApproach("Case-based explanation and guided practice");
        intent.setInteractionMode("Question, discussion, and feedback");
        intent.setTargetAudience(project.getTargetAudience());
        intent.setOutputTypes(List.of("PPT", "DOCX", "INTERACTION"));
        intent.setStylePreference("Clear and classroom-ready");
        intent.setStatus(status);
        if (status == TeachingIntentStatus.CONFIRMED) {
            intent.setConfirmedAt(LocalDateTime.now());
        }
        return intentRepository.save(intent);
    }

    private long responseDataId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data").path("id").asLong();
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static List<Long> artifactIds(JsonNode data) {
        List<Long> ids = new java.util.ArrayList<>();
        data.forEach(artifact -> ids.add(artifact.path("id").asLong()));
        return List.copyOf(ids);
    }
}
