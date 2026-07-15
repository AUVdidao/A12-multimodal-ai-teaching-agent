package com.auvdidao.a12teachingagent.project;

import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
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
class ProjectLifecycleSecurityIntegrationTest {

    private static final String PASSWORD = "Lifecycle123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AppUserRepository userRepository;
    @Autowired private UserRoleAssignmentRepository roleRepository;

    private String ownerToken;
    private String otherTeacherToken;

    @BeforeEach
    void setUp() throws Exception {
        AppUser owner = createUser("lifecycle-owner", "Lifecycle Owner");
        AppUser other = createUser("lifecycle-other", "Lifecycle Other");
        ownerToken = login(owner);
        otherTeacherToken = login(other);
    }

    @Test
    void ownerTracksRecentVisitsAndRestoresSoftDeletedProject() throws Exception {
        long projectId = createProject(ownerToken);

        mockMvc.perform(get("/api/projects/" + projectId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/recent").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].project.id").value(projectId))
                .andExpect(jsonPath("$.data[0].visitCount").value(1))
                .andExpect(jsonPath("$.data[0].lastVisitedAt").isNotEmpty());

        mockMvc.perform(get("/api/projects/recent").header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(delete("/api/projects/" + projectId).header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/projects/" + projectId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/projects/recent").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/projects/recycle-bin").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(projectId))
                .andExpect(jsonPath("$.data[0].deletedAt").isNotEmpty());
        mockMvc.perform(get("/api/projects/" + projectId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/projects/" + projectId + "/restore").header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/" + projectId + "/restore").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(projectId))
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist());
        mockMvc.perform(get("/api/projects").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
        mockMvc.perform(post("/api/projects/" + projectId + "/restore").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isBadRequest());
    }

    private long createProject(String token) throws Exception {
        String response = mockMvc.perform(post("/api/projects")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectName": "Lifecycle project",
                                  "courseName": "Lifecycle course",
                                  "chapterTitle": "Lifecycle chapter",
                                  "targetStudents": "Undergraduate",
                                  "lessonDuration": 45
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private AppUser createUser(String username, String displayName) {
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
        return user;
    }

    private String login(AppUser user) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(user.getUsername(), PASSWORD, UserRole.TEACHER))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return data.path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoginPayload(String username, String password, UserRole activeRole) {
    }
}
