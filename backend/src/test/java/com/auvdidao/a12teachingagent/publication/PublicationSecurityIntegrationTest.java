package com.auvdidao.a12teachingagent.publication;

import com.auvdidao.a12teachingagent.domain.approval.ApprovalRequest;
import com.auvdidao.a12teachingagent.domain.approval.ApprovalStatus;
import com.auvdidao.a12teachingagent.domain.approval.repository.ApprovalRequestRepository;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.course.ClassGroup;
import com.auvdidao.a12teachingagent.domain.course.ClassMembership;
import com.auvdidao.a12teachingagent.domain.course.Course;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassGroupRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassMembershipRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.CourseRepository;
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
import com.auvdidao.a12teachingagent.domain.publication.repository.PublicationRepository;
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
class PublicationSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PublicationRepository publicationRepository;

    @Autowired
    private ApprovalRequestRepository approvalRequestRepository;

    @Autowired
    private GeneratedArtifactRepository generatedArtifactRepository;

    @Autowired
    private ArtifactVersionRepository artifactVersionRepository;

    @Autowired
    private ClassMembershipRepository classMembershipRepository;

    @Autowired
    private ClassGroupRepository classGroupRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private UserRoleAssignmentRepository roleRepository;

    @Autowired
    private AppUserRepository userRepository;

    private AppUser leader;
    private AppUser otherLeader;
    private AppUser teacher;
    private AppUser otherTeacher;
    private AppUser enrolledStudent;
    private AppUser unenrolledStudent;
    private Project project;
    private Course course;
    private ClassGroup classGroup;
    private ArtifactVersion approvedVersion;
    private ArtifactVersion newerDraftVersion;
    private ApprovalRequest approvedRequest;

    @BeforeEach
    void setUp() {
        publicationRepository.deleteAll();
        approvalRequestRepository.deleteAll();
        generatedArtifactRepository.deleteAll();
        artifactVersionRepository.deleteAll();
        classMembershipRepository.deleteAll();
        classGroupRepository.deleteAll();
        courseRepository.deleteAll();
        projectRepository.deleteAll();
        sessionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();

        leader = createUser("publication-leader", "Publication Leader", "Leader123!", UserRole.LEADER);
        otherLeader = createUser("other-publication-leader", "Other Leader", "Leader123!", UserRole.LEADER);
        teacher = createUser("publication-teacher", "Publication Teacher", "Teacher123!", UserRole.TEACHER);
        otherTeacher = createUser("other-publication-teacher", "Other Teacher", "Teacher123!", UserRole.TEACHER);
        enrolledStudent = createUser("enrolled-student", "Enrolled Student", "Student123!", UserRole.STUDENT);
        unenrolledStudent = createUser("unenrolled-student", "Unenrolled Student", "Student123!", UserRole.STUDENT);

        course = createCourse("AI-101", "AI Foundations");
        classGroup = createClass(course.getId(), "Class A");
        enroll(classGroup.getId(), enrolledStudent.getId());
        project = createProject("Approved AI lesson", "AI Foundations", teacher.getId());
        approvedVersion = createVersion(project.getId(), 1, "Approved fixed version", true);
        newerDraftVersion = createVersion(project.getId(), 2, "Newer private draft", false);
        createArtifact(approvedVersion, "Approved slides", "{\"marker\":\"approved-v1\"}", 1);
        createArtifact(newerDraftVersion, "Newer draft slides", "{\"marker\":\"newer-draft\"}", 2);
        approvedRequest = createApproval(project.getId(), approvedVersion.getId(), ApprovalStatus.APPROVED);
    }

    @Test
    void reviewerPublishesFixedVersionAndWithdrawalHidesItFromStudents() throws Exception {
        String leaderToken = login(leader, "Leader123!", UserRole.LEADER);
        String otherLeaderToken = login(otherLeader, "Leader123!", UserRole.LEADER);
        String teacherToken = login(teacher, "Teacher123!", UserRole.TEACHER);
        String otherTeacherToken = login(otherTeacher, "Teacher123!", UserRole.TEACHER);
        String enrolledToken = login(enrolledStudent, "Student123!", UserRole.STUDENT);
        String unenrolledToken = login(unenrolledStudent, "Student123!", UserRole.STUDENT);
        PublishPayload payload = new PublishPayload(
                approvedRequest.getId(),
                classGroup.getId(),
                "Responsible AI lesson",
                "Approved materials for Class A"
        );

        publish(payload, teacherToken).andExpect(status().isForbidden());
        publish(payload, otherLeaderToken).andExpect(status().isForbidden());

        String response = publish(payload, leaderToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvalRequestId", is(approvedRequest.getId().intValue())))
                .andExpect(jsonPath("$.data.artifactVersionId", is(approvedVersion.getId().intValue())))
                .andExpect(jsonPath("$.data.projectId", is(project.getId().intValue())))
                .andExpect(jsonPath("$.data.courseId", is(course.getId().intValue())))
                .andExpect(jsonPath("$.data.classId", is(classGroup.getId().intValue())))
                .andExpect(jsonPath("$.data.publishedBy", is(leader.getId().intValue())))
                .andExpect(jsonPath("$.data.status", is("PUBLISHED")))
                .andReturn().getResponse().getContentAsString();
        Long publicationId = objectMapper.readTree(response).path("data").path("id").asLong();

        publish(payload, leaderToken).andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/publications").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
        mockMvc.perform(get("/api/v1/publications").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].projectId", is(project.getId().intValue())));
        mockMvc.perform(get("/api/v1/publications").header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/publications/" + publicationId)
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(publicationId.intValue())))
                .andExpect(jsonPath("$.data.artifactVersionId", is(approvedVersion.getId().intValue())));
        mockMvc.perform(get("/api/v1/publications/" + publicationId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId", is(project.getId().intValue())));
        mockMvc.perform(get("/api/v1/publications/" + publicationId)
                        .header("Authorization", bearer(otherLeaderToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/publications/" + publicationId)
                        .header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/student/learning-tasks").header("Authorization", bearer(enrolledToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].publicationId", is(publicationId.intValue())))
                .andExpect(jsonPath("$.data[0].artifactVersionId", is(approvedVersion.getId().intValue())));
        mockMvc.perform(get("/api/v1/student/learning-tasks").header("Authorization", bearer(unenrolledToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/student/learning-tasks/" + publicationId)
                        .header("Authorization", bearer(unenrolledToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/student/learning-tasks/" + publicationId)
                        .header("Authorization", bearer(enrolledToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifactVersionId", is(approvedVersion.getId().intValue())))
                .andExpect(jsonPath("$.data.artifactVersion.id", is(approvedVersion.getId().intValue())))
                .andExpect(jsonPath("$.data.artifactVersion.versionNumber", is(1)))
                .andExpect(jsonPath("$.data.artifactVersion.description", is("Approved fixed version")))
                .andExpect(jsonPath("$.data.artifacts", hasSize(1)))
                .andExpect(jsonPath("$.data.artifacts[0].artifactType", is("PPT")))
                .andExpect(jsonPath("$.data.artifacts[0].title", is("Approved slides")))
                .andExpect(jsonPath("$.data.artifacts[0].contentJson", is("{\"marker\":\"approved-v1\"}")))
                .andExpect(jsonPath("$.data.artifacts[0].schemaVersion", is(1)));

        mockMvc.perform(post("/api/v1/publications/" + publicationId + "/withdraw")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/publications/" + publicationId + "/withdraw")
                        .header("Authorization", bearer(otherLeaderToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/publications/" + publicationId + "/withdraw")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("WITHDRAWN")))
                .andExpect(jsonPath("$.data.withdrawnAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/student/learning-tasks").header("Authorization", bearer(enrolledToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/student/learning-tasks/" + publicationId)
                        .header("Authorization", bearer(enrolledToken)))
                .andExpect(status().isNotFound());
        publish(payload, leaderToken).andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/publications?status=WITHDRAWN")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void publishRejectsUnapprovedAndMismatchedRelationChains() throws Exception {
        String leaderToken = login(leader, "Leader123!", UserRole.LEADER);
        ApprovalRequest submitted = createApproval(
                project.getId(),
                newerDraftVersion.getId(),
                ApprovalStatus.SUBMITTED
        );
        publish(new PublishPayload(submitted.getId(), classGroup.getId(), "Draft", null), leaderToken)
                .andExpect(status().isConflict());
        ApprovalRequest revisionRequired = createApproval(
                project.getId(),
                newerDraftVersion.getId(),
                ApprovalStatus.REVISION_REQUIRED
        );
        publish(new PublishPayload(revisionRequired.getId(), classGroup.getId(), "Revision", null), leaderToken)
                .andExpect(status().isConflict());
        ApprovalRequest cancelled = createApproval(
                project.getId(),
                newerDraftVersion.getId(),
                ApprovalStatus.CANCELLED
        );
        publish(new PublishPayload(cancelled.getId(), classGroup.getId(), "Cancelled", null), leaderToken)
                .andExpect(status().isConflict());

        Course otherCourse = createCourse("OTHER-101", "Different Course");
        ClassGroup otherClass = createClass(otherCourse.getId(), "Class B");
        publish(new PublishPayload(approvedRequest.getId(), otherClass.getId(), "Wrong course", null), leaderToken)
                .andExpect(status().isBadRequest());

        Project otherProject = createProject("Other project", "AI Foundations", teacher.getId());
        ArtifactVersion otherProjectVersion = createVersion(otherProject.getId(), 1, "Other project version", true);
        ApprovalRequest mismatched = createApproval(
                project.getId(),
                otherProjectVersion.getId(),
                ApprovalStatus.APPROVED
        );
        publish(new PublishPayload(mismatched.getId(), classGroup.getId(), "Wrong project", null), leaderToken)
                .andExpect(status().isBadRequest());
    }

    @Test
    void softDeletedProjectIsHiddenFromPublicationAndLearningTaskEndpoints() throws Exception {
        String leaderToken = login(leader, "Leader123!", UserRole.LEADER);
        String teacherToken = login(teacher, "Teacher123!", UserRole.TEACHER);
        String studentToken = login(enrolledStudent, "Student123!", UserRole.STUDENT);

        String response = publish(new PublishPayload(
                        approvedRequest.getId(),
                        classGroup.getId(),
                        "Published before deletion",
                        null
                ), leaderToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long publicationId = objectMapper.readTree(response).path("data").path("id").asLong();

        project.setDeletedAt(LocalDateTime.now());
        projectRepository.save(project);

        mockMvc.perform(get("/api/v1/publications").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/publications").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/publications/" + publicationId)
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/publications/" + publicationId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/student/learning-tasks")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/student/learning-tasks/" + publicationId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/publications/" + publicationId + "/withdraw")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isNotFound());

        ApprovalRequest secondApproval = createApproval(
                project.getId(),
                newerDraftVersion.getId(),
                ApprovalStatus.APPROVED
        );
        publish(new PublishPayload(secondApproval.getId(), classGroup.getId(), "After deletion", null), leaderToken)
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions publish(PublishPayload payload, String token)
            throws Exception {
        return mockMvc.perform(post("/api/v1/publications")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
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

    private Course createCourse(String code, String name) {
        Course value = new Course();
        value.setCourseCode(code);
        value.setCourseName(name);
        value.setDescription("Publication integration course");
        value.setCreatedBy(leader.getId());
        return courseRepository.save(value);
    }

    private ClassGroup createClass(Long courseId, String name) {
        ClassGroup value = new ClassGroup();
        value.setCourseId(courseId);
        value.setClassName(name);
        value.setCohort("2026");
        value.setStudentCount(1);
        return classGroupRepository.save(value);
    }

    private void enroll(Long classId, Long studentId) {
        ClassMembership membership = new ClassMembership();
        membership.setClassId(classId);
        membership.setStudentId(studentId);
        classMembershipRepository.save(membership);
    }

    private Project createProject(String name, String courseName, Long ownerUserId) {
        Project value = new Project();
        value.setProjectName(name);
        value.setCourseName(courseName);
        value.setChapterTopic("Responsible AI");
        value.setTargetAudience("Undergraduates");
        value.setLessonDurationMinutes(45);
        value.setProjectDescription("Publication integration project");
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

    private void createArtifact(ArtifactVersion version, String title, String contentJson, int schemaVersion) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setProjectId(version.getProjectId());
        artifact.setGenerationPlanId(version.getGenerationPlanId());
        artifact.setVersionId(version.getId());
        artifact.setArtifactType(ArtifactType.PPT);
        artifact.setTitle(title);
        artifact.setContentJson(contentJson);
        artifact.setSchemaVersion(schemaVersion);
        generatedArtifactRepository.save(artifact);
    }

    private ApprovalRequest createApproval(Long projectId, Long artifactVersionId, ApprovalStatus status) {
        ApprovalRequest value = new ApprovalRequest();
        value.setProjectId(projectId);
        value.setArtifactVersionId(artifactVersionId);
        value.setSubmittedBy(teacher.getId());
        value.setReviewerId(leader.getId());
        value.setStatus(status);
        value.setSubmittedAt(LocalDateTime.now().minusMinutes(5));
        if (status == ApprovalStatus.APPROVED) {
            value.setReviewedAt(LocalDateTime.now().minusMinutes(1));
        } else if (status == ApprovalStatus.SUBMITTED) {
            value.setActiveArtifactVersionId(artifactVersionId);
        }
        return approvalRequestRepository.save(value);
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
        JsonNode body = objectMapper.readTree(response);
        return body.path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoginPayload(String username, String password, UserRole activeRole) {
    }

    private record PublishPayload(Long approvalRequestId, Long classId, String title, String summary) {
    }
}
