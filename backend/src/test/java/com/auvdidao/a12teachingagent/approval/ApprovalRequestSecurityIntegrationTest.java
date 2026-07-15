package com.auvdidao.a12teachingagent.approval;

import com.auvdidao.a12teachingagent.domain.approval.ApprovalStatus;
import com.auvdidao.a12teachingagent.domain.approval.repository.ApprovalRequestRepository;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
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

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
class ApprovalRequestSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ApprovalRequestRepository approvalRequestRepository;

    @Autowired
    private ArtifactVersionRepository artifactVersionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserRoleAssignmentRepository roleRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    private AppUser leader;
    private AppUser otherLeader;
    private AppUser teacher;
    private AppUser otherTeacher;
    private AppUser student;
    private Project project;
    private Project secondTeacherProject;
    private ArtifactVersion firstFinalVersion;
    private ArtifactVersion secondFinalVersion;
    private ArtifactVersion draftVersion;

    @BeforeEach
    void setUp() {
        approvalRequestRepository.deleteAll();
        artifactVersionRepository.deleteAll();
        projectRepository.deleteAll();
        sessionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();

        leader = createUser("approval-leader", "Approval Leader", "Leader123!", UserRole.LEADER);
        otherLeader = createUser("other-leader", "Other Leader", "Leader123!", UserRole.LEADER);
        teacher = createUser("approval-teacher", "Approval Teacher", "Teacher123!", UserRole.TEACHER);
        otherTeacher = createUser("other-teacher", "Other Teacher", "Teacher123!", UserRole.TEACHER);
        student = createUser("approval-student", "Approval Student", "Student123!", UserRole.STUDENT);

        project = createProject("Immutable lesson", teacher.getId());
        secondTeacherProject = createProject("Second lesson", teacher.getId());
        firstFinalVersion = createVersion(project.getId(), 1, "first immutable description", true);
        secondFinalVersion = createVersion(project.getId(), 2, "second immutable description", true);
        draftVersion = createVersion(project.getId(), 3, "draft description", false);
    }

    @Test
    void fixedVersionsCompleteApprovalLifecycleWithCrossUserIsolation() throws Exception {
        String leaderToken = login(leader, "Leader123!", UserRole.LEADER);
        String otherLeaderToken = login(otherLeader, "Leader123!", UserRole.LEADER);
        String teacherToken = login(teacher, "Teacher123!", UserRole.TEACHER);
        String otherTeacherToken = login(otherTeacher, "Teacher123!", UserRole.TEACHER);
        String studentToken = login(student, "Student123!", UserRole.STUDENT);

        Long firstApprovalId = submit(project.getId(), firstFinalVersion.getId(), leader.getId(), teacherToken)
                .path("data").path("id").asLong();

        mockMvc.perform(post("/api/v1/approval-requests")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitPayload(
                                project.getId(),
                                firstFinalVersion.getId(),
                                leader.getId()
                        ))))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/approval-requests").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].submittedBy", is(teacher.getId().intValue())));

        mockMvc.perform(get("/api/v1/approval-requests").header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/v1/approval-requests?status=SUBMITTED")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].reviewerId", is(leader.getId().intValue())));

        mockMvc.perform(get("/api/v1/approval-requests").header("Authorization", bearer(otherLeaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/v1/approval-requests/" + firstApprovalId)
                        .header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/approval-requests/" + firstApprovalId)
                        .header("Authorization", bearer(otherLeaderToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/approval-requests").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        review(firstApprovalId, otherLeaderToken, "REVISION_REQUIRED", "Not this leader")
                .andExpect(status().isForbidden());
        review(firstApprovalId, teacherToken, "APPROVED", null)
                .andExpect(status().isForbidden());
        review(firstApprovalId, leaderToken, "REVISION_REQUIRED", "   ")
                .andExpect(status().isBadRequest());
        review(firstApprovalId, leaderToken, "REVISION_REQUIRED", "  Add a worked example.  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("REVISION_REQUIRED")))
                .andExpect(jsonPath("$.data.reviewNote", is("Add a worked example.")))
                .andExpect(jsonPath("$.data.reviewedAt").isNotEmpty());

        assertVersionUnchanged(firstFinalVersion.getId(), 1, "first immutable description");

        Long secondApprovalId = submit(project.getId(), secondFinalVersion.getId(), leader.getId(), teacherToken)
                .path("data").path("id").asLong();
        review(secondApprovalId, leaderToken, "APPROVED", "Ready to publish")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("APPROVED")))
                .andExpect(jsonPath("$.data.reviewNote", is("Ready to publish")));

        mockMvc.perform(get("/api/v1/approval-requests?status=APPROVED")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is(secondApprovalId.intValue())));

        review(secondApprovalId, leaderToken, "REVISION_REQUIRED", "Too late")
                .andExpect(status().isConflict());
        assertVersionUnchanged(secondFinalVersion.getId(), 2, "second immutable description");
    }

    @Test
    void submissionRejectsWrongProjectDraftVersionInvalidReviewerAndStudent() throws Exception {
        String teacherToken = login(teacher, "Teacher123!", UserRole.TEACHER);
        String studentToken = login(student, "Student123!", UserRole.STUDENT);

        mockMvc.perform(post("/api/v1/approval-requests")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitPayload(
                                secondTeacherProject.getId(),
                                firstFinalVersion.getId(),
                                leader.getId()
                        ))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/approval-requests")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitPayload(
                                project.getId(),
                                draftVersion.getId(),
                                leader.getId()
                        ))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/approval-requests")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitPayload(
                                project.getId(),
                                firstFinalVersion.getId(),
                                otherTeacher.getId()
                        ))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/approval-requests")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitPayload(
                                project.getId(),
                                firstFinalVersion.getId(),
                                leader.getId()
                        ))))
                .andExpect(status().isForbidden());

        assertTrue(approvalRequestRepository.findAll().isEmpty());
    }

    @Test
    void submitterCanCancelPendingRequestWithoutChangingFixedVersion() throws Exception {
        String leaderToken = login(leader, "Leader123!", UserRole.LEADER);
        String teacherToken = login(teacher, "Teacher123!", UserRole.TEACHER);
        String otherTeacherToken = login(otherTeacher, "Teacher123!", UserRole.TEACHER);

        Long approvalId = submit(project.getId(), firstFinalVersion.getId(), leader.getId(), teacherToken)
                .path("data").path("id").asLong();

        mockMvc.perform(post("/api/v1/approval-requests/" + approvalId + "/cancel")
                        .header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/approval-requests/" + approvalId + "/cancel")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/approval-requests/" + approvalId + "/cancel")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CANCELLED")));

        mockMvc.perform(post("/api/v1/approval-requests/" + approvalId + "/cancel")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/approval-requests?status=CANCELLED")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is(approvalId.intValue())));

        assertVersionUnchanged(firstFinalVersion.getId(), 1, "first immutable description");
    }

    @Test
    void softDeletedProjectHidesAndLocksApprovalEndpoints() throws Exception {
        String leaderToken = login(leader, "Leader123!", UserRole.LEADER);
        String teacherToken = login(teacher, "Teacher123!", UserRole.TEACHER);
        Long approvalId = submit(project.getId(), firstFinalVersion.getId(), leader.getId(), teacherToken)
                .path("data").path("id").asLong();

        project.setDeletedAt(LocalDateTime.now());
        projectRepository.saveAndFlush(project);

        mockMvc.perform(get("/api/v1/approval-requests")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/approval-requests")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/approval-requests/" + approvalId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isNotFound());
        review(approvalId, leaderToken, "APPROVED", "Should remain hidden")
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/approval-requests/" + approvalId + "/cancel")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isNotFound());

        assertEquals(ApprovalStatus.SUBMITTED,
                approvalRequestRepository.findById(approvalId).orElseThrow().getStatus());
    }

    private JsonNode submit(Long projectId, Long versionId, Long reviewerId, String token) throws Exception {
        String response = mockMvc.perform(post("/api/v1/approval-requests")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitPayload(projectId, versionId, reviewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId", is(projectId.intValue())))
                .andExpect(jsonPath("$.data.artifactVersionId", is(versionId.intValue())))
                .andExpect(jsonPath("$.data.status", is("SUBMITTED")))
                .andExpect(jsonPath("$.data.submittedByName", is("Approval Teacher")))
                .andExpect(jsonPath("$.data.reviewerName", is("Approval Leader")))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private org.springframework.test.web.servlet.ResultActions review(
            Long approvalRequestId,
            String token,
            String statusValue,
            String note
    ) throws Exception {
        return mockMvc.perform(put("/api/v1/approval-requests/" + approvalRequestId + "/review")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReviewPayload(statusValue, note))));
    }

    private void assertVersionUnchanged(Long versionId, int versionNumber, String description) {
        ArtifactVersion persisted = artifactVersionRepository.findById(versionId).orElseThrow();
        assertEquals(versionNumber, persisted.getVersionNumber());
        assertEquals(description, persisted.getDescription());
        assertTrue(Boolean.TRUE.equals(persisted.getFinalVersion()));
    }

    private Project createProject(String name, Long ownerUserId) {
        Project value = new Project();
        value.setProjectName(name);
        value.setCourseName("AI Foundations");
        value.setChapterTopic("Responsible AI");
        value.setTargetAudience("Undergraduates");
        value.setLessonDurationMinutes(45);
        value.setProjectDescription("Approval integration test project");
        value.setOwnerUserId(ownerUserId);
        value.setGenerationMode(GenerationMode.STANDARD);
        value.setStatus(ProjectStatus.FINALIZED);
        return projectRepository.save(value);
    }

    private ArtifactVersion createVersion(Long projectId, int versionNumber, String description, boolean finalVersion) {
        ArtifactVersion value = new ArtifactVersion();
        value.setProjectId(projectId);
        value.setGenerationPlanId(100L + versionNumber);
        value.setVersionNumber(versionNumber);
        value.setDescription(description);
        value.setFinalVersion(finalVersion);
        return artifactVersionRepository.save(value);
    }

    private AppUser createUser(String username, String displayName, String password, UserRole role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(true);
        user = userRepository.save(user);

        UserRoleAssignment assignment = new UserRoleAssignment();
        assignment.setUserId(user.getId());
        assignment.setRole(role);
        roleRepository.save(assignment);
        return user;
    }

    private String login(AppUser user, String password, UserRole activeRole) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(
                                user.getUsername(),
                                password,
                                activeRole
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

    private record SubmitPayload(Long projectId, Long artifactVersionId, Long reviewerId) {
    }

    private record ReviewPayload(String status, String note) {
    }
}
