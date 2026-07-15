package com.auvdidao.a12teachingagent.security;

import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.InputType;
import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserSessionRepository;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementInputRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

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
class ProjectScopedAccessSecurityIntegrationTest {

    private static final String PASSWORD = "Teacher123!";

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
    private RequirementInputRepository requirementRepository;

    @Autowired
    private UploadedMaterialRepository materialRepository;

    @Autowired
    private GeneratedArtifactRepository artifactRepository;

    @AfterEach
    @BeforeEach
    void clean() {
        artifactRepository.deleteAll();
        materialRepository.deleteAll();
        requirementRepository.deleteAll();
        projectRepository.deleteAll();
        sessionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void teacherCanReadOwnM1M3DataAndOtherTeacherIsForbidden() throws Exception {
        createTeacher("teacher-a", "Teacher A");
        createTeacher("teacher-b", "Teacher B");
        String ownerToken = login("teacher-a");
        String otherToken = login("teacher-b");
        long projectId = createProject(ownerToken);
        createRequirement(projectId);
        createMaterial(projectId);
        createArtifact(projectId);

        mockMvc.perform(get("/api/projects/{projectId}/requirements/latest", projectId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawRequirementText", is("Owner-only requirement")));

        mockMvc.perform(get("/api/projects/{projectId}/materials", projectId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].originalFilename", is("owner-material.txt")));

        mockMvc.perform(get("/api/projects/{projectId}/artifacts", projectId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title", is("Owner artifact")));

        assertForbidden(otherToken, "/api/projects/" + projectId + "/requirements/latest");
        assertForbidden(otherToken, "/api/projects/" + projectId + "/materials");
        assertForbidden(otherToken, "/api/projects/" + projectId + "/artifacts");
    }

    @Test
    void workspaceProjectAggregationOnlyIncludesOwnedProjects() throws Exception {
        createTeacher("teacher-a", "Teacher A");
        createTeacher("teacher-b", "Teacher B");
        String ownerToken = login("teacher-a");
        String otherToken = login("teacher-b");
        long projectId = createProject(ownerToken);

        mockMvc.perform(get("/api/workspace/projects")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].id", is((int) projectId)));

        mockMvc.perform(get("/api/workspace/projects")
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    private void assertForbidden(String token, String path) throws Exception {
        mockMvc.perform(get(path).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(403)));
    }

    private void createTeacher(String username, String displayName) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setEnabled(true);
        user = userRepository.save(user);

        UserRoleAssignment assignment = new UserRoleAssignment();
        assignment.setUserId(user.getId());
        assignment.setRole(UserRole.TEACHER);
        roleRepository.save(assignment);
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(
                                username,
                                PASSWORD,
                                UserRole.TEACHER
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private long createProject(String token) throws Exception {
        String response = mockMvc.perform(post("/api/projects")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectName": "Teacher A project",
                                  "courseName": "Secure teaching",
                                  "chapterTitle": "Ownership",
                                  "targetStudents": "Teachers",
                                  "lessonDuration": 45,
                                  "description": "Project access integration test"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private void createRequirement(long projectId) {
        RequirementInput requirement = new RequirementInput();
        requirement.setProjectId(projectId);
        requirement.setInputType(InputType.TEXT);
        requirement.setRawRequirementText("Owner-only requirement");
        requirement.setContent("Owner-only requirement");
        requirement.setOutputTypes(List.of("PPT"));
        requirementRepository.save(requirement);
    }

    private void createMaterial(long projectId) {
        UploadedMaterial material = new UploadedMaterial();
        material.setProjectId(projectId);
        material.setFileName("stored-owner-material.txt");
        material.setOriginalFileName("owner-material.txt");
        material.setFileExtension("txt");
        material.setFileType(MaterialFileType.TXT);
        material.setContentType(MediaType.TEXT_PLAIN_VALUE);
        material.setFilePath("test/owner-material.txt");
        material.setFileSize(24L);
        material.setUploadStatus(UploadStatus.UPLOADED);
        material.setParseStatus(MaterialParseStatus.NOT_STARTED);
        materialRepository.save(material);
    }

    private void createArtifact(long projectId) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setProjectId(projectId);
        artifact.setGenerationPlanId(1L);
        artifact.setArtifactType(ArtifactType.PPT);
        artifact.setTitle("Owner artifact");
        artifact.setSchemaVersion(1);
        artifact.setContentJson("{\"slides\":[]}");
        artifactRepository.save(artifact);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoginPayload(String username, String password, UserRole activeRole) {
    }
}
