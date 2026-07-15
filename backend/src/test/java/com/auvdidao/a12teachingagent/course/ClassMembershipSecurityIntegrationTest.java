package com.auvdidao.a12teachingagent.course;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class ClassMembershipSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AppUserRepository userRepository;
    @Autowired private UserRoleAssignmentRepository roleRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private ClassGroupRepository classGroupRepository;
    @Autowired private ClassMembershipRepository membershipRepository;

    private AppUser leader;
    private AppUser teacher;
    private AppUser student;
    private ClassGroup classGroup;

    @BeforeEach
    void setUp() {
        membershipRepository.deleteAll();
        classGroupRepository.deleteAll();
        courseRepository.deleteAll();
        sessionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();

        leader = createUser("membership-leader", "Membership Leader", "Leader123!", UserRole.LEADER);
        teacher = createUser("membership-teacher", "Membership Teacher", "Teacher123!", UserRole.TEACHER);
        student = createUser("membership-student", "Membership Student", "Student123!", UserRole.STUDENT);

        Course course = new Course();
        course.setCourseCode("MEM-101");
        course.setCourseName("Membership Course");
        course.setCreatedBy(leader.getId());
        course = courseRepository.save(course);

        classGroup = new ClassGroup();
        classGroup.setCourseId(course.getId());
        classGroup.setClassName("Membership Class");
        classGroup.setStudentCount(30);
        classGroup = classGroupRepository.save(classGroup);
    }

    @Test
    void leaderManagesMembersWhileOtherRolesAreRejected() throws Exception {
        String leaderToken = login(leader, "Leader123!", UserRole.LEADER);
        String teacherToken = login(teacher, "Teacher123!", UserRole.TEACHER);
        String studentToken = login(student, "Student123!", UserRole.STUDENT);

        mockMvc.perform(get("/api/v1/collaboration/reference-data").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.students", hasSize(1)))
                .andExpect(jsonPath("$.data.students[0].id", is(student.getId().intValue())));

        mockMvc.perform(post("/api/v1/classes/" + classGroup.getId() + "/members")
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MemberPayload(student.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentId", is(student.getId().intValue())))
                .andExpect(jsonPath("$.data.displayName", is("Membership Student")));

        mockMvc.perform(get("/api/v1/classes/" + classGroup.getId() + "/members")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        mockMvc.perform(post("/api/v1/classes/" + classGroup.getId() + "/members")
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MemberPayload(student.getId()))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/classes/" + classGroup.getId() + "/members")
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MemberPayload(teacher.getId()))))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/classes/" + classGroup.getId() + "/members")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/classes/" + classGroup.getId() + "/members/" + student.getId())
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/classes/" + classGroup.getId() + "/members/" + student.getId())
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/classes/" + classGroup.getId() + "/members")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
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

    private String login(AppUser user, String password, UserRole role) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(user.getUsername(), password, role))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return data.path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record MemberPayload(Long studentId) {}
    private record LoginPayload(String username, String password, UserRole activeRole) {}
}
