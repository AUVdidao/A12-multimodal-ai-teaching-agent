package com.auvdidao.a12teachingagent.teachingtask;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.course.ClassGroup;
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
import com.auvdidao.a12teachingagent.domain.teachingtask.repository.TeachingTaskRepository;
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
class TeachingTaskSecurityIntegrationTest {

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
    private CourseRepository courseRepository;

    @Autowired
    private ClassGroupRepository classGroupRepository;

    @Autowired
    private ClassMembershipRepository membershipRepository;

    @Autowired
    private TeachingTaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private AppUser leader;
    private AppUser teacher;
    private AppUser otherTeacher;
    private AppUser student;
    private Course course;
    private ClassGroup classGroup;
    private Project linkedProject;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        membershipRepository.deleteAll();
        classGroupRepository.deleteAll();
        courseRepository.deleteAll();
        sessionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();

        leader = createUser("leader-one", "Leader One", "Leader123!", UserRole.LEADER);
        teacher = createUser("teacher-one", "Teacher One", "Teacher123!", UserRole.TEACHER);
        otherTeacher = createUser("teacher-two", "Teacher Two", "Teacher123!", UserRole.TEACHER);
        student = createUser("student-one", "Student One", "Student123!", UserRole.STUDENT);
        linkedProject = createProject("Linked lesson", teacher.getId());

        course = new Course();
        course.setCourseCode("AI-101");
        course.setCourseName("AI Foundations");
        course.setDescription("Security integration test course");
        course.setCreatedBy(leader.getId());
        course = courseRepository.save(course);

        classGroup = new ClassGroup();
        classGroup.setCourseId(course.getId());
        classGroup.setClassName("Class A");
        classGroup.setCohort("2026");
        classGroup.setStudentCount(30);
        classGroup = classGroupRepository.save(classGroup);
    }

    @Test
    void leaderAndAssignedTeacherCompleteTaskLifecycleWithDataIsolation() throws Exception {
        String leaderToken = login(leader.getUsername(), "Leader123!", UserRole.LEADER);
        String teacherToken = login(teacher.getUsername(), "Teacher123!", UserRole.TEACHER);
        String otherTeacherToken = login(otherTeacher.getUsername(), "Teacher123!", UserRole.TEACHER);
        String studentToken = login(student.getUsername(), "Student123!", UserRole.STUDENT);

        mockMvc.perform(get("/api/v1/collaboration/reference-data")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teachers", hasSize(2)))
                .andExpect(jsonPath("$.data.leaders", hasSize(1)))
                .andExpect(jsonPath("$.data.courses", hasSize(1)))
                .andExpect(jsonPath("$.data.classes", hasSize(1)));

        mockMvc.perform(get("/api/v1/collaboration/reference-data")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/courses").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "courseCode": "UNAUTHORIZED-101",
                                  "courseName": "Unauthorized course",
                                  "description": "A teacher cannot create organization data"
                                }
                                """))
                .andExpect(status().isForbidden());

        String createResponse = mockMvc.perform(post("/api/v1/teaching-tasks")
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTaskPayload(
                                "Prepare AI foundations lesson",
                                course.getId(),
                                classGroup.getId(),
                                "Core concepts",
                                teacher.getId(),
                                "Create a complete lesson package and submit the linked project.",
                                "HIGH",
                                LocalDateTime.now().plusDays(3),
                                null
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus", is("ASSIGNED")))
                .andExpect(jsonPath("$.data.assigneeName", is("Teacher One")))
                .andExpect(jsonPath("$.data.courseName", is("AI Foundations")))
                .andReturn().getResponse().getContentAsString();

        Long taskId = objectMapper.readTree(createResponse).path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/teaching-tasks").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is(taskId.intValue())));

        mockMvc.perform(get("/api/v1/teaching-tasks").header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/v1/teaching-tasks/" + taskId)
                        .header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/teaching-tasks").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/teaching-tasks")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTaskPayload(
                                "Teacher cannot self-assign",
                                course.getId(),
                                classGroup.getId(),
                                "Unauthorized task",
                                teacher.getId(),
                                "This request is valid but the active role is not allowed to create it.",
                                "MEDIUM",
                                LocalDateTime.now().plusDays(2),
                                null
                        ))))
                .andExpect(status().isForbidden());

        updateStatus(taskId, teacherToken, "IN_PROGRESS", null, "IN_PROGRESS");
        submit(taskId, teacherToken, "First submission", "SUBMITTED");
        updateStatus(taskId, leaderToken, "REVISION_REQUIRED", "Add a classroom interaction", "REVISION_REQUIRED");
        updateStatus(taskId, teacherToken, "IN_PROGRESS", null, "IN_PROGRESS");
        submit(taskId, teacherToken, "Revised submission", "SUBMITTED");
        updateStatus(taskId, leaderToken, "COMPLETED", "Accepted", "COMPLETED");

        mockMvc.perform(get("/api/v1/teaching-tasks/" + taskId)
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus", is("COMPLETED")))
                .andExpect(jsonPath("$.data.overdue", is(false)))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty());
    }

    @Test
    void softDeletedLinkedProjectHidesTaskAndBlocksReadAndStatusTransitions() throws Exception {
        String leaderToken = login(leader.getUsername(), "Leader123!", UserRole.LEADER);
        String teacherToken = login(teacher.getUsername(), "Teacher123!", UserRole.TEACHER);
        String otherTeacherToken = login(otherTeacher.getUsername(), "Teacher123!", UserRole.TEACHER);
        Long taskId = createTask(leaderToken, linkedProject.getId());

        linkedProject.setDeletedAt(LocalDateTime.now());
        projectRepository.saveAndFlush(linkedProject);

        mockMvc.perform(get("/api/v1/teaching-tasks")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/teaching-tasks")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/teaching-tasks/" + taskId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/teaching-tasks/" + taskId)
                        .header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isNotFound());

        updateStatus(taskId, teacherToken, "IN_PROGRESS", null, null)
                .andExpect(status().isNotFound());
        updateStatus(taskId, leaderToken, "CANCELLED", "Hidden project", null)
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/teaching-tasks/" + taskId + "/submit")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmissionPayload("Must be hidden"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/teaching-tasks/" + taskId)
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTaskPayload(
                                "Clear deleted project",
                                course.getId(),
                                classGroup.getId(),
                                "Cleanup",
                                teacher.getId(),
                                "Null linkedProjectId explicitly clears the association.",
                                "MEDIUM",
                                LocalDateTime.now().plusDays(2),
                                null
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkedProjectId").doesNotExist());
    }

    @Test
    void createAndUpdateRejectDeletedLinkedProject() throws Exception {
        String leaderToken = login(leader.getUsername(), "Leader123!", UserRole.LEADER);
        Project deletedProject = createProject("Deleted linked lesson", teacher.getId());
        deletedProject.setDeletedAt(LocalDateTime.now());
        projectRepository.saveAndFlush(deletedProject);

        mockMvc.perform(post("/api/v1/teaching-tasks")
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTaskPayload(
                                "Reject deleted project",
                                course.getId(),
                                classGroup.getId(),
                                "Invalid association",
                                teacher.getId(),
                                "The linked project is deleted.",
                                "LOW",
                                LocalDateTime.now().plusDays(2),
                                deletedProject.getId()
                        ))))
                .andExpect(status().isNotFound());

        Long taskId = createTask(leaderToken, null);
        mockMvc.perform(put("/api/v1/teaching-tasks/" + taskId)
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTaskPayload(
                                "Reject deleted project update",
                                course.getId(),
                                classGroup.getId(),
                                "Invalid update",
                                teacher.getId(),
                                "The linked project is deleted.",
                                "LOW",
                                LocalDateTime.now().plusDays(2),
                                deletedProject.getId()
                        ))))
                .andExpect(status().isNotFound());
    }

    private Long createTask(String token, Long linkedProjectId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/teaching-tasks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTaskPayload(
                                "Linked task",
                                course.getId(),
                                classGroup.getId(),
                                "Linked chapter",
                                teacher.getId(),
                                "Task with a project association.",
                                "MEDIUM",
                                LocalDateTime.now().plusDays(2),
                                linkedProjectId
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions updateStatus(
            Long taskId,
            String token,
            String statusValue,
            String note,
            String expected
    ) throws Exception {
        org.springframework.test.web.servlet.ResultActions result = mockMvc.perform(put("/api/v1/teaching-tasks/" + taskId + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StatusPayload(statusValue, note))));
        if (expected != null) {
            result.andExpect(status().isOk()).andExpect(jsonPath("$.data.taskStatus", is(expected)));
        }
        return result;
    }

    private void submit(Long taskId, String token, String note, String expected) throws Exception {
        mockMvc.perform(post("/api/v1/teaching-tasks/" + taskId + "/submit")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmissionPayload(note))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus", is(expected)));
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

    private String login(String username, String password, UserRole activeRole) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(username, password, activeRole))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode payload = objectMapper.readTree(response).path("data");
        return payload.path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoginPayload(String username, String password, UserRole activeRole) {
    }

    private record CreateTaskPayload(
            String taskName,
            Long courseId,
            Long classId,
            String chapterTitle,
            Long assigneeId,
            String requirements,
            String priority,
            LocalDateTime dueAt,
            Long linkedProjectId
    ) {
    }

    private record StatusPayload(String status, String note) {
    }

    private record SubmissionPayload(String note) {
    }

    private Project createProject(String name, Long ownerId) {
        Project value = new Project();
        value.setProjectName(name);
        value.setCourseName("AI Foundations");
        value.setChapterTopic("Teaching task lifecycle");
        value.setTargetAudience("Undergraduates");
        value.setLessonDurationMinutes(45);
        value.setProjectDescription("Teaching task integration project");
        value.setOwnerUserId(ownerId);
        value.setGenerationMode(GenerationMode.STANDARD);
        value.setStatus(ProjectStatus.FINALIZED);
        return projectRepository.save(value);
    }
}
