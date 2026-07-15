package com.auvdidao.a12teachingagent.intent;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntentEvidence;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "a12.security.enabled=true",
        "a12.security.demo-seed-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeachingIntentRevisionSecurityIntegrationTest {

    private static final String PASSWORD = "IntentRevision123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TeachingIntentRepository intentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserRoleAssignmentRepository roleRepository;

    private AppUser owner;
    private AppUser otherTeacher;
    private AppUser leader;
    private AppUser student;
    private Project ownerProject;
    private Project secondOwnerProject;
    private Project deletedProject;
    private String ownerToken;
    private String otherTeacherToken;
    private String leaderToken;
    private String studentToken;

    @BeforeEach
    void setUp() throws Exception {
        owner = createUser("owner", "Intent Owner", UserRole.TEACHER);
        otherTeacher = createUser("other-teacher", "Other Teacher", UserRole.TEACHER);
        leader = createUser("leader", "Intent Leader", UserRole.LEADER);
        student = createUser("student", "Intent Student", UserRole.STUDENT);
        ownerProject = createProject(owner.getId(), false);
        secondOwnerProject = createProject(owner.getId(), false);
        deletedProject = createProject(owner.getId(), true);
        ownerToken = login(owner, UserRole.TEACHER);
        otherTeacherToken = login(otherTeacher, UserRole.TEACHER);
        leaderToken = login(leader, UserRole.LEADER);
        studentToken = login(student, UserRole.STUDENT);
    }

    @Test
    void confirmedIntentCreatesNewDraftAndLeavesOriginalImmutable() throws Exception {
        TeachingIntent source = createIntent(ownerProject, TeachingIntentStatus.CONFIRMED);

        String response = mockMvc.perform(post(revisionPath(ownerProject.getId(), source.getId()))
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId", is(ownerProject.getId().intValue())))
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.data.confirmedAt", nullValue()))
                .andExpect(jsonPath("$.data.evidenceItems", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        Long draftId = data.path("id").asLong();
        TeachingIntent original = intentRepository.findById(source.getId()).orElseThrow();
        TeachingIntent draft = intentRepository.findById(draftId).orElseThrow();

        assertNotEquals(source.getId(), draft.getId());
        assertNotEquals(source.getCreatedAt(), draft.getCreatedAt());
        assertThat(original.getStatus()).isEqualTo(TeachingIntentStatus.CONFIRMED);
        assertThat(original.getConfirmedAt()).isNotNull();
        assertThat(original.getGenerationGoal()).isEqualTo("Understand the topic and apply it");
        assertThat(original.getEvidenceItems()).hasSize(1);
        assertThat(draft.getStatus()).isEqualTo(TeachingIntentStatus.DRAFT);
        assertThat(draft.getConfirmedAt()).isNull();
        assertThat(draft.getGenerationGoals()).containsExactlyElementsOf(original.getGenerationGoals());
        assertThat(draft.getEvidenceItems()).hasSize(1);
        assertThat(draft.getEvidenceItems().get(0)).isNotSameAs(original.getEvidenceItems().get(0));
        assertThat(draft.getEvidenceItems().get(0).getContentExcerpt())
                .isEqualTo(original.getEvidenceItems().get(0).getContentExcerpt());

        mockMvc.perform(put("/api/projects/{projectId}/teaching-intents/{intentId}", ownerProject.getId(), source.getId())
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "generationGoal": "Changed",
                                  "contentBasis": "Changed",
                                  "teachingApproach": "Changed",
                                  "interactionMode": "Changed",
                                  "outputTypes": ["PPT"]
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void draftRevisionIsIdempotent() throws Exception {
        TeachingIntent draft = createIntent(ownerProject, TeachingIntentStatus.DRAFT);

        mockMvc.perform(post(revisionPath(ownerProject.getId(), draft.getId()))
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(draft.getId().intValue())))
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.data.confirmedAt", nullValue()));

        assertThat(intentRepository.findAll().stream()
                .filter(intent -> ownerProject.getId().equals(intent.getProjectId())))
                .hasSize(1);
    }

    @Test
    void crossProjectIntentReturnsNotFound() throws Exception {
        TeachingIntent foreignIntent = createIntent(secondOwnerProject, TeachingIntentStatus.CONFIRMED);

        mockMvc.perform(post(revisionPath(ownerProject.getId(), foreignIntent.getId()))
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletedProjectCannotCreateRevision() throws Exception {
        TeachingIntent source = createIntent(deletedProject, TeachingIntentStatus.CONFIRMED);

        mockMvc.perform(post(revisionPath(deletedProject.getId(), source.getId()))
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyProjectOwnerTeacherCanCreateRevision() throws Exception {
        TeachingIntent source = createIntent(ownerProject, TeachingIntentStatus.CONFIRMED);

        mockMvc.perform(post(revisionPath(ownerProject.getId(), source.getId()))
                        .header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(revisionPath(ownerProject.getId(), source.getId()))
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(revisionPath(ownerProject.getId(), source.getId()))
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
    }

    private String revisionPath(Long projectId, Long intentId) {
        return "/api/projects/" + projectId + "/teaching-intents/" + intentId + "/revisions";
    }

    private TeachingIntent createIntent(Project project, TeachingIntentStatus status) {
        TeachingIntentEvidence evidence = new TeachingIntentEvidence();
        evidence.setMaterialId(101L);
        evidence.setKnowledgeChunkId(202L);
        evidence.setSourceFilename("intent-source.md");
        evidence.setUsageTypes(PurposeType.TEXTBOOK_BASIS.name());
        evidence.setHitReason("Matched confirmed teaching topic");
        evidence.setContentExcerpt("Evidence excerpt");

        TeachingIntent intent = new TeachingIntent();
        intent.setProjectId(project.getId());
        intent.setRequirementSummaryId(303L);
        intent.setGenerationGoal("Understand the topic and apply it");
        intent.setGenerationGoals(List.of("Understand the topic and apply it"));
        intent.setContentBasis("Confirmed course requirements and evidence");
        intent.setPrimaryBasis("Confirmed requirements");
        intent.setSupplementalBasis(List.of("intent-source.md"));
        intent.setTeachingApproach("Concept explanation and practice");
        intent.setInteractionMode("Guided discussion");
        intent.setTargetAudience("Undergraduate students");
        intent.setTotalHours(2);
        intent.setTeachingFormat("Guided discussion");
        intent.setOutputTypes(List.of("PPT", "DOCX"));
        intent.setStylePreference("Clear and practical");
        intent.setNotes("Original intent notes");
        intent.setEvidenceItems(List.of(evidence));
        intent.setStatus(status);
        intent.setConfirmedAt(status == TeachingIntentStatus.CONFIRMED
                ? LocalDateTime.now().minusMinutes(5)
                : null);
        return intentRepository.saveAndFlush(intent);
    }

    private Project createProject(Long ownerId, boolean deleted) {
        Project project = new Project();
        project.setProjectName("Intent project " + UUID.randomUUID());
        project.setCourseName("AI Foundations");
        project.setChapterTopic("Teaching intent revision");
        project.setTargetAudience("Undergraduates");
        project.setLessonDurationMinutes(90);
        project.setProjectDescription("Teaching intent revision integration project");
        project.setOwnerUserId(ownerId);
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.INTENT_CONFIRMED);
        if (deleted) {
            project.setDeletedAt(LocalDateTime.now().minusMinutes(1));
        }
        return projectRepository.saveAndFlush(project);
    }

    private AppUser createUser(String roleName, String displayName, UserRole role) {
        AppUser user = new AppUser();
        user.setUsername("intent-rev-" + roleName + "-" + UUID.randomUUID().toString().substring(0, 8));
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setEnabled(true);
        user = userRepository.saveAndFlush(user);

        UserRoleAssignment assignment = new UserRoleAssignment();
        assignment.setUserId(user.getId());
        assignment.setRole(role);
        roleRepository.saveAndFlush(assignment);
        return user;
    }

    private String login(AppUser user, UserRole activeRole) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(
                                user.getUsername(), PASSWORD, activeRole
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoginPayload(String username, String password, UserRole activeRole) {
    }
}
