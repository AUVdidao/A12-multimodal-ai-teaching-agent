package com.auvdidao.a12teachingagent.qa;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.course.ClassGroup;
import com.auvdidao.a12teachingagent.domain.course.ClassMembership;
import com.auvdidao.a12teachingagent.domain.course.Course;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassGroupRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassMembershipRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.CourseRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserSessionRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.publication.Publication;
import com.auvdidao.a12teachingagent.domain.publication.PublicationStatus;
import com.auvdidao.a12teachingagent.domain.publication.repository.PublicationRepository;
import com.auvdidao.a12teachingagent.domain.qa.repository.QuestionAnswerRepository;
import com.auvdidao.a12teachingagent.domain.qa.repository.QuestionRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "a12.security.enabled=true",
        "a12.security.demo-seed-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuestionSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private QuestionAnswerRepository answerRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private PublicationRepository publicationRepository;

    @Autowired
    private ClassMembershipRepository membershipRepository;

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
    private AppUser student;
    private AppUser otherStudent;
    private Project project;
    private Publication publication;
    private Publication withdrawnPublication;
    private Publication otherClassPublication;

    @BeforeEach
    void setUp() {
        answerRepository.deleteAll();
        questionRepository.deleteAll();
        publicationRepository.deleteAll();
        membershipRepository.deleteAll();
        classGroupRepository.deleteAll();
        courseRepository.deleteAll();
        projectRepository.deleteAll();
        sessionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();

        leader = createUser("qa-leader", "QA Leader", "Leader123!", UserRole.LEADER);
        otherLeader = createUser("qa-other-leader", "Other QA Leader", "Leader123!", UserRole.LEADER);
        teacher = createUser("qa-teacher", "QA Teacher", "Teacher123!", UserRole.TEACHER);
        otherTeacher = createUser("qa-other-teacher", "Other QA Teacher", "Teacher123!", UserRole.TEACHER);
        student = createUser("qa-student", "QA Student", "Student123!", UserRole.STUDENT);
        otherStudent = createUser("qa-other-student", "Other QA Student", "Student123!", UserRole.STUDENT);

        Course course = createCourse("QA-101", "Question and Answer");
        ClassGroup enrolledClass = createClass(course.getId(), "QA Class A");
        ClassGroup otherClass = createClass(course.getId(), "QA Class B");
        enroll(enrolledClass.getId(), student.getId());
        project = createProject("QA Project", teacher.getId());
        createProject("Other QA Project", otherTeacher.getId());
        publication = createPublication(project.getId(), enrolledClass.getId(), leader.getId(), PublicationStatus.PUBLISHED);
        withdrawnPublication = createPublication(project.getId(), enrolledClass.getId(), leader.getId(), PublicationStatus.WITHDRAWN);
        otherClassPublication = createPublication(project.getId(), otherClass.getId(), leader.getId(), PublicationStatus.PUBLISHED);
    }

    @Test
    void studentTeacherAndAssignedLeaderSeeOnlyTheirQuestionScope() throws Exception {
        String studentToken = login(student, "Student123!", UserRole.STUDENT);
        String otherStudentToken = login(otherStudent, "Student123!", UserRole.STUDENT);
        String teacherToken = login(teacher, "Teacher123!", UserRole.TEACHER);
        String otherTeacherToken = login(otherTeacher, "Teacher123!", UserRole.TEACHER);
        String leaderToken = login(leader, "Leader123!", UserRole.LEADER);
        String otherLeaderToken = login(otherLeader, "Leader123!", UserRole.LEADER);

        String response = createQuestion(publication.getId(), "Why is this important?", "Please explain the key idea.", studentToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicationId", is(publication.getId().intValue())))
                .andExpect(jsonPath("$.data.projectId", is(project.getId().intValue())))
                .andExpect(jsonPath("$.data.studentId", is(student.getId().intValue())))
                .andExpect(jsonPath("$.data.status", is("OPEN")))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        Long questionId = objectMapper.readTree(response).path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/questions?publicationId=" + publication.getId() + "&status=OPEN")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
        mockMvc.perform(get("/api/v1/questions/" + questionId).header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(questionId.intValue())));
        mockMvc.perform(get("/api/v1/questions").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        mockMvc.perform(get("/api/v1/questions").header("Authorization", bearer(otherStudentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/questions/" + questionId).header("Authorization", bearer(otherStudentToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/questions/" + questionId).header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/questions/" + questionId).header("Authorization", bearer(otherLeaderToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/questions/" + questionId + "/answers")
                        .header("Authorization", bearer(otherTeacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Unauthorized answer\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/questions/" + questionId + "/answers")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"It connects the lesson concepts to the assessment.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("ANSWERED")))
                .andExpect(jsonPath("$.data.answeredAt").isNotEmpty())
                .andExpect(jsonPath("$.data.answers", hasSize(1)))
                .andExpect(jsonPath("$.data.answers[0].teacherId", is(teacher.getId().intValue())));

        mockMvc.perform(put("/api/v1/questions/" + questionId + "/status")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("OPEN")));
        mockMvc.perform(put("/api/v1/questions/" + questionId + "/status")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Unsupported question status: UNKNOWN")));

        mockMvc.perform(post("/api/v1/questions/" + questionId + "/answers")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"A follow-up answer.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answers", hasSize(2)));
        mockMvc.perform(put("/api/v1/questions/" + questionId + "/status")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CLOSED")))
                .andExpect(jsonPath("$.data.closedAt").isNotEmpty());
        mockMvc.perform(post("/api/v1/questions/" + questionId + "/answers")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"This must be rejected.\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/v1/questions/" + questionId + "/status")
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void questionCreationRejectsNonStudentsUnpublishedAndCrossClassPublications() throws Exception {
        String studentToken = login(student, "Student123!", UserRole.STUDENT);
        String otherStudentToken = login(otherStudent, "Student123!", UserRole.STUDENT);
        String teacherToken = login(teacher, "Teacher123!", UserRole.TEACHER);

        createQuestion(publication.getId(), "Teacher question", "Teachers must not create student questions.", teacherToken)
                .andExpect(status().isForbidden());
        createQuestion(publication.getId(), "Cross student", "This student is not in the class.", otherStudentToken)
                .andExpect(status().isForbidden());
        createQuestion(withdrawnPublication.getId(), "Withdrawn", "This learning task is no longer active.", studentToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Questions can only be asked on a published learning task")));
        createQuestion(otherClassPublication.getId(), "Other class", "This student is not enrolled in this class.", studentToken)
                .andExpect(status().isForbidden());
        createQuestion(publication.getId(), "Valid title", "   ", studentToken)
                .andExpect(status().isBadRequest());
    }

    @Test
    void softDeletedProjectHidesAndLocksQuestionEndpoints() throws Exception {
        String studentToken = login(student, "Student123!", UserRole.STUDENT);
        String teacherToken = login(teacher, "Teacher123!", UserRole.TEACHER);
        String leaderToken = login(leader, "Leader123!", UserRole.LEADER);

        String response = createQuestion(
                        publication.getId(),
                        "Question before deletion",
                        "This question must become inaccessible.",
                        studentToken
                )
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long questionId = objectMapper.readTree(response).path("data").path("id").asLong();

        project.setDeletedAt(LocalDateTime.now());
        projectRepository.save(project);

        mockMvc.perform(get("/api/v1/questions").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/questions").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/questions").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/questions/" + questionId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/questions/" + questionId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/questions/" + questionId)
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isNotFound());
        createQuestion(publication.getId(), "After deletion", "Must be rejected.", studentToken)
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/questions/" + questionId + "/answers")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Must be rejected.\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/questions/" + questionId + "/status")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/questions/" + questionId + "/status")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions createQuestion(
            Long publicationId,
            String title,
            String content,
            String token
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/questions")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateQuestionPayload(publicationId, title, content))));
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
        Course course = new Course();
        course.setCourseCode(code);
        course.setCourseName(name);
        course.setDescription("Question integration test course");
        course.setCreatedBy(leader.getId());
        return courseRepository.save(course);
    }

    private ClassGroup createClass(Long courseId, String name) {
        ClassGroup classGroup = new ClassGroup();
        classGroup.setCourseId(courseId);
        classGroup.setClassName(name);
        classGroup.setCohort("2026");
        classGroup.setStudentCount(30);
        return classGroupRepository.save(classGroup);
    }

    private void enroll(Long classId, Long studentId) {
        ClassMembership membership = new ClassMembership();
        membership.setClassId(classId);
        membership.setStudentId(studentId);
        membershipRepository.save(membership);
    }

    private Project createProject(String name, Long ownerId) {
        Project project = new Project();
        project.setProjectName(name);
        project.setCourseName("Question and Answer");
        project.setChapterTopic("Question lifecycle");
        project.setTargetAudience("Students");
        project.setLessonDurationMinutes(45);
        project.setProjectDescription("Question integration project");
        project.setOwnerUserId(ownerId);
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.FINALIZED);
        return projectRepository.save(project);
    }

    private Publication createPublication(Long projectId, Long classId, Long leaderId, PublicationStatus status) {
        Publication publication = new Publication();
        publication.setApprovalRequestId(100L + publicationRepository.count());
        publication.setArtifactVersionId(200L + publicationRepository.count());
        publication.setProjectId(projectId);
        publication.setCourseId(classGroupRepository.findById(classId).orElseThrow().getCourseId());
        publication.setClassId(classId);
        publication.setTitle("QA publication " + publicationRepository.count());
        publication.setSummary("Question test publication");
        publication.setPublishedBy(leaderId);
        publication.setStatus(status);
        publication.setPublishedAt(LocalDateTime.now());
        if (status == PublicationStatus.WITHDRAWN) {
            publication.setWithdrawnAt(LocalDateTime.now());
        }
        return publicationRepository.save(publication);
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

    private record CreateQuestionPayload(Long publicationId, String title, String content) {
    }
}
