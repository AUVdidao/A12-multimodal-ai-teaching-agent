package com.auvdidao.a12teachingagent.versioning;

import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserSessionRepository;
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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class ArtifactVersionSecurityIntegrationTest {

    private static final String PASSWORD = "Versioning123!";

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

    private AppUser owner;
    private AppUser otherTeacher;
    private Project project;
    private Project otherProject;
    private ArtifactVersion generatedVersion;
    private ArtifactVersion previousFinalVersion;
    private ArtifactVersion emptyVersion;
    private ArtifactVersion otherProjectVersion;
    private String ownerToken;
    private String otherTeacherToken;
    private String leaderToken;
    private String studentToken;

    @BeforeEach
    void setUp() throws Exception {
        artifactRepository.deleteAll();
        versionRepository.deleteAll();
        projectRepository.deleteAll();
        sessionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();

        owner = createUser("version-owner", "Version Owner", UserRole.TEACHER);
        otherTeacher = createUser("version-other", "Other Teacher", UserRole.TEACHER);
        createUser("version-leader", "Version Leader", UserRole.LEADER);
        createUser("version-student", "Version Student", UserRole.STUDENT);

        project = createProject("Owner project", owner.getId());
        otherProject = createProject("Other project", otherTeacher.getId());

        previousFinalVersion = createVersion(project.getId(), 1, "Previous final", true);
        generatedVersion = createVersion(project.getId(), 2, "Target draft", false);
        emptyVersion = createVersion(project.getId(), 3, "No artifact", false);
        otherProjectVersion = createVersion(otherProject.getId(), 1, "Other project version", false);
        createArtifact(previousFinalVersion, "Previous artifact");
        createArtifact(generatedVersion, "Target artifact");

        ownerToken = login(owner, UserRole.TEACHER);
        otherTeacherToken = login(otherTeacher, UserRole.TEACHER);
        leaderToken = login("version-leader", UserRole.LEADER);
        studentToken = login("version-student", UserRole.STUDENT);
    }

    @Test
    void ownerCanListGeneratedVersionsAndFinalizeIdempotently() throws Exception {
        mockMvc.perform(get(path(project.getId())).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[1].id", is(generatedVersion.getId().intValue())))
                .andExpect(jsonPath("$.data[1].artifactCount", is(1)))
                .andExpect(jsonPath("$.data[1].contentJson").doesNotExist());

        mockMvc.perform(put(finalizePath(project.getId(), generatedVersion.getId()))
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(generatedVersion.getId().intValue())))
                .andExpect(jsonPath("$.data.finalVersion", is(true)))
                .andExpect(jsonPath("$.data.description", is("Target draft")));

        mockMvc.perform(put(finalizePath(project.getId(), generatedVersion.getId()))
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.finalVersion", is(true)))
                .andExpect(jsonPath("$.data.description", is("Target draft")));

        ArtifactVersion savedTarget = versionRepository.findById(generatedVersion.getId()).orElseThrow();
        ArtifactVersion savedPrevious = versionRepository.findById(previousFinalVersion.getId()).orElseThrow();
        GeneratedArtifact savedArtifact = artifactRepository.findByProjectIdOrderByCreatedAtAsc(project.getId()).stream()
                .filter(item -> generatedVersion.getId().equals(item.getVersionId()))
                .findFirst()
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(savedTarget.getDescription()).isEqualTo("Target draft");
        org.assertj.core.api.Assertions.assertThat(savedTarget.getFinalVersion()).isTrue();
        org.assertj.core.api.Assertions.assertThat(savedPrevious.getFinalVersion()).isFalse();
        org.assertj.core.api.Assertions.assertThat(savedArtifact.getTitle()).isEqualTo("Target artifact");
        org.assertj.core.api.Assertions.assertThat(savedArtifact.getContentJson()).isEqualTo("{\"content\":\"Target artifact\"}");
    }

    @Test
    void otherTeacherCannotReadOrFinalizeAnotherTeachersProject() throws Exception {
        assertForbidden(otherTeacherToken, get(path(project.getId())));
        assertForbidden(otherTeacherToken, put(finalizePath(project.getId(), generatedVersion.getId())));
    }

    @Test
    void projectAndVersionMustMatch() throws Exception {
        mockMvc.perform(put(finalizePath(project.getId(), otherProjectVersion.getId()))
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(path(otherProject.getId())).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void versionWithoutGeneratedArtifactsCannotBeFinalized() throws Exception {
        mockMvc.perform(put(finalizePath(project.getId(), emptyVersion.getId()))
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(409)));
    }

    @Test
    void leaderAndStudentAreRejected() throws Exception {
        assertForbidden(leaderToken, get(path(project.getId())));
        assertForbidden(leaderToken, put(finalizePath(project.getId(), generatedVersion.getId())));
        assertForbidden(studentToken, get(path(project.getId())));
        assertForbidden(studentToken, put(finalizePath(project.getId(), generatedVersion.getId())));
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
        value.setCourseName("Versioning course");
        value.setChapterTopic("Versioning");
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

    private void createArtifact(ArtifactVersion version, String title) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setProjectId(version.getProjectId());
        artifact.setGenerationPlanId(version.getGenerationPlanId());
        artifact.setVersionId(version.getId());
        artifact.setArtifactType(ArtifactType.PPT);
        artifact.setTitle(title);
        artifact.setSchemaVersion(1);
        artifact.setContentJson("{\"content\":\"" + title + "\"}");
        artifactRepository.save(artifact);
    }

    private String login(AppUser user, UserRole activeRole) throws Exception {
        return login(user.getUsername(), activeRole);
    }

    private String login(String username, UserRole activeRole) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(username, PASSWORD, activeRole))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.path("data").path("token").asText();
    }

    private void assertForbidden(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(403)));
    }

    private String path(Long projectId) {
        return "/api/v1/projects/" + projectId + "/artifact-versions";
    }

    private String finalizePath(Long projectId, Long versionId) {
        return path(projectId) + "/" + versionId + "/finalize";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoginPayload(String username, String password, UserRole activeRole) {
    }
}
