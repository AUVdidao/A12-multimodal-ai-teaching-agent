package com.auvdidao.a12teachingagent.revision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.EditRecordRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserSessionRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "a12.security.enabled=true",
        "a12.security.demo-seed-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ArtifactRevisionSecurityIntegrationTest {

    private static final String PASSWORD = "Revision123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserRoleAssignmentRepository roleRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ArtifactVersionRepository versionRepository;

    @Autowired
    private GeneratedArtifactRepository artifactRepository;

    @Autowired
    private EditRecordRepository editRecordRepository;

    private AppUser owner;
    private AppUser otherTeacher;
    private Project project;
    private ArtifactVersion sourceVersion;
    private ArtifactVersion finalVersion;
    private GeneratedArtifact sourcePpt;
    private GeneratedArtifact finalArtifact;
    private String ownerToken;
    private String otherTeacherToken;
    private String leaderToken;
    private String studentToken;

    @BeforeEach
    void setUp() throws Exception {
        editRecordRepository.deleteAll();
        artifactRepository.deleteAll();
        versionRepository.deleteAll();
        projectRepository.deleteAll();
        sessionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();

        owner = createUser("revision-owner", "Revision Owner", UserRole.TEACHER);
        otherTeacher = createUser("revision-other", "Other Teacher", UserRole.TEACHER);
        createUser("revision-leader", "Revision Leader", UserRole.LEADER);
        createUser("revision-student", "Revision Student", UserRole.STUDENT);

        project = createProject("Owner project", owner.getId());
        Project otherProject = createProject("Other project", otherTeacher.getId());
        sourceVersion = createVersion(project.getId(), 1, "Draft source", false);
        finalVersion = createVersion(project.getId(), 2, "Final source", true);
        ArtifactVersion otherVersion = createVersion(otherProject.getId(), 1, "Other source", false);

        sourcePpt = createArtifact(sourceVersion, ArtifactType.PPT, "Source PPT", pptJson("source"));
        createArtifact(sourceVersion, ArtifactType.DOCX, "Source DOCX", docxJson());
        createArtifact(sourceVersion, ArtifactType.INTERACTION, "Source interaction", interactionJson());
        finalArtifact = createArtifact(finalVersion, ArtifactType.PPT, "Final PPT", pptJson("final"));
        createArtifact(otherVersion, ArtifactType.PPT, "Other PPT", pptJson("other"));

        ownerToken = login(owner.getUsername(), UserRole.TEACHER);
        otherTeacherToken = login(otherTeacher.getUsername(), UserRole.TEACHER);
        leaderToken = login("revision-leader", UserRole.LEADER);
        studentToken = login("revision-student", UserRole.STUDENT);
    }

    @Test
    void ownerCreatesIncrementedRevisionAndPreservesSource() throws Exception {
        String originalSource = sourcePpt.getContentJson();
        String response = mockMvc.perform(post(revisionPath(project.getId(), sourcePpt.getId()))
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"Add a short recap for the lesson.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.versionNumber", is(3)))
                .andExpect(jsonPath("$.data.version.finalVersion", is(false)))
                .andExpect(jsonPath("$.data.artifacts", hasSize(3)))
                .andExpect(jsonPath("$.data.mockProvider", is(true)))
                .andExpect(jsonPath("$.data.changeSummary").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        for (JsonNode artifact : body.path("data").path("artifacts")) {
            assertThat(artifact.path("content").isObject()).isTrue();
            assertThat(artifact.path("content").toString()).isNotBlank();
        }
        assertThat(body.path("data").path("artifacts").get(0).path("content").path("slides")).hasSize(2);

        GeneratedArtifact savedSource = artifactRepository.findById(sourcePpt.getId()).orElseThrow();
        assertThat(savedSource.getContentJson()).isEqualTo(originalSource);
        assertThat(versionRepository.findById(sourceVersion.getId()).orElseThrow().getFinalVersion()).isFalse();
        assertThat(artifactRepository.findByProjectIdAndVersionIdOrderByCreatedAtAsc(project.getId(),
                body.path("data").path("version").path("id").asLong())).hasSize(3);

        mockMvc.perform(get(editRecordsPath(project.getId())).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].instruction", is("Add a short recap for the lesson.")));
    }

    @Test
    void finalSourceCannotBeRevised() throws Exception {
        mockMvc.perform(post(revisionPath(project.getId(), finalArtifact.getId()))
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"Change the final content.\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(409)))
                .andExpect(jsonPath("$.message", is("Final artifact version cannot be revised")));
    }

    @Test
    void projectArtifactMismatchReturnsNotFound() throws Exception {
        Project otherProject = projectRepository.findAll().stream()
                .filter(item -> !item.getId().equals(project.getId()))
                .findFirst()
                .orElseThrow();
        Long otherArtifactId = artifactRepository.findByProjectIdOrderByCreatedAtAsc(otherProject.getId()).get(0).getId();

        mockMvc.perform(post(revisionPath(project.getId(), otherArtifactId))
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"Should not cross projects.\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherTeacherAndNonTeacherRolesAreRejected() throws Exception {
        assertForbidden(otherTeacherToken, post(revisionPath(project.getId(), sourcePpt.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instruction\":\"No access.\"}"));
        assertForbidden(otherTeacherToken, get(editRecordsPath(project.getId())));
        assertForbidden(leaderToken, post(revisionPath(project.getId(), sourcePpt.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instruction\":\"No access.\"}"));
        assertForbidden(studentToken, get(editRecordsPath(project.getId())));
    }

    @Test
    void instructionMustBePresentAndBounded() throws Exception {
        mockMvc.perform(post(revisionPath(project.getId(), sourcePpt.getId()))
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"   \"}"))
                .andExpect(status().isBadRequest());

        String tooLong = "x".repeat(4001);
        mockMvc.perform(post(revisionPath(project.getId(), sourcePpt.getId()))
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RevisionPayload(tooLong))))
                .andExpect(status().isBadRequest());
    }

    private AppUser createUser(String username, String displayName, UserRole role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setEnabled(true);
        user = userRepository.save(user);

        UserRoleAssignment assignment = new UserRoleAssignment();
        assignment.setUserId(user.getId());
        assignment.setRole(role);
        roleRepository.save(assignment);
        return user;
    }

    private Project createProject(String name, Long ownerUserId) {
        Project value = new Project();
        value.setProjectName(name);
        value.setCourseName("Revision course");
        value.setChapterTopic("Revision topic");
        value.setOwnerUserId(ownerUserId);
        value.setGenerationMode(GenerationMode.STANDARD);
        value.setStatus(ProjectStatus.GENERATED);
        return projectRepository.save(value);
    }

    private ArtifactVersion createVersion(Long projectId, int number, String description, boolean finalVersion) {
        ArtifactVersion value = new ArtifactVersion();
        value.setProjectId(projectId);
        value.setGenerationPlanId(100L + number);
        value.setVersionNumber(number);
        value.setDescription(description);
        value.setFinalVersion(finalVersion);
        return versionRepository.save(value);
    }

    private GeneratedArtifact createArtifact(
            ArtifactVersion version,
            ArtifactType type,
            String title,
            String content
    ) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setProjectId(version.getProjectId());
        artifact.setGenerationPlanId(version.getGenerationPlanId());
        artifact.setVersionId(version.getId());
        artifact.setArtifactType(type);
        artifact.setTitle(title);
        artifact.setSchemaVersion(1);
        artifact.setContentJson(content);
        return artifactRepository.save(artifact);
    }

    private String pptJson(String title) {
        return "{\"deckTitle\":\"" + title + "\",\"theme\":\"Clear\",\"slides\":["
                + "{\"index\":1,\"kind\":\"COVER\",\"title\":\"" + title
                + "\",\"layout\":\"TITLE\",\"points\":[\"One point\"],\"speakerNotes\":\"Notes\"}]}";
    }

    private String docxJson() {
        return "{\"title\":\"Lesson plan\",\"courseInfo\":{},\"teachingGoals\":[],\"keyPoints\":[],"
                + "\"difficultPoints\":[],\"methods\":[],\"teachingProcess\":[],\"classroomActivities\":[],"
                + "\"homework\":[],\"resourceNotes\":[],\"sections\":[{\"order\":1,\"title\":\"Base\",\"paragraphs\":[\"Text\"]}]}";
    }

    private String interactionJson() {
        return "{\"title\":\"Interaction\",\"instructions\":\"Choose one.\",\"questions\":["
                + "{\"id\":\"q1\",\"question\":\"Question\",\"options\":[\"A\",\"B\"],"
                + "\"correctOption\":0,\"correctAnswer\":\"A\",\"explanation\":\"Because\"}]}";
    }

    private String login(String username, UserRole activeRole) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(username, PASSWORD, activeRole))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private void assertForbidden(String token, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(403)));
    }

    private String revisionPath(Long projectId, Long artifactId) {
        return "/api/v1/projects/" + projectId + "/artifacts/" + artifactId + "/revisions";
    }

    private String editRecordsPath(Long projectId) {
        return "/api/v1/projects/" + projectId + "/edit-records";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoginPayload(String username, String password, UserRole activeRole) {
    }

    private record RevisionPayload(String instruction) {
    }
}
