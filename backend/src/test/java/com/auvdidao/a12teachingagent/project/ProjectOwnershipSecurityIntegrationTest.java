package com.auvdidao.a12teachingagent.project;

import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserSessionRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "a12.security.enabled=true",
        "a12.security.demo-seed-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectOwnershipSecurityIntegrationTest {

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

    @BeforeEach
    void clean() {
        projectRepository.deleteAll();
        sessionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void teachersOnlySeeAndOpenTheirOwnProjects() throws Exception {
        createTeacher("teacher-owner", "Owner Teacher");
        createTeacher("teacher-other", "Other Teacher");
        String ownerToken = login("teacher-owner");
        String otherToken = login("teacher-other");

        String created = mockMvc.perform(post("/api/projects")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectName": "Owner project",
                                  "courseName": "AI Foundations",
                                  "chapterTitle": "Core concepts",
                                  "targetStudents": "First-year students",
                                  "lessonDuration": 90,
                                  "description": "Ownership test"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long projectId = objectMapper.readTree(created).path("data").path("id").asLong();

        mockMvc.perform(get("/api/projects").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is((int) projectId)));

        mockMvc.perform(get("/api/projects").header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/projects/" + projectId).header("Authorization", bearer(otherToken)))
                .andExpect(status().isForbidden());
    }

    private void createTeacher(String username, String displayName) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode("Teacher123!"));
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
                                "Teacher123!",
                                UserRole.TEACHER
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
