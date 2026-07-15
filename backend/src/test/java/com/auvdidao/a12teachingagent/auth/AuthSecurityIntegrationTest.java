package com.auvdidao.a12teachingagent.auth;

import com.auvdidao.a12teachingagent.domain.common.UserRole;
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

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
class AuthSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserRoleAssignmentRepository roleRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanIdentityData() {
        sessionRepository.deleteAll();
        roleRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void publicRegistrationCreatesStudentOnlyAndPersistsHashes() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "Student.One",
                                  "displayName": "学生一号",
                                  "password": "Student123!",
                                  "roles": ["LEADER"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.username", is("student.one")))
                .andExpect(jsonPath("$.data.user.roles", contains("STUDENT")))
                .andExpect(jsonPath("$.data.user.activeRole", is("STUDENT")))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode payload = objectMapper.readTree(response).path("data");
        String rawToken = payload.path("token").asText();
        AppUser user = userRepository.findByUsernameIgnoreCase("student.one").orElseThrow();
        assertTrue(passwordEncoder.matches("Student123!", user.getPasswordHash()));
        assertNotEquals("Student123!", user.getPasswordHash());
        String tokenHash = sessionRepository.findAll().get(0).getTokenHash();
        assertFalse(tokenHash.contains(rawToken));
        assertNotEquals(rawToken, tokenHash);
    }

    @Test
    void protectedTeacherApiRejectsMissingTokenAndWrongPassword() throws Exception {
        createUser("teacher", "张老师", "Teacher123!", List.of(UserRole.TEACHER));

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(401)));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"teacher","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Username or password is incorrect")));
    }

    @Test
    void activeRoleSwitchChangesAuthorizationWithoutIssuingAnotherToken() throws Exception {
        createUser("multi", "协同负责人", "Multi123!", List.of(UserRole.TEACHER, UserRole.LEADER));
        String token = login("multi", "Multi123!", UserRole.TEACHER);

        mockMvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/switch-role")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"LEADER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeRole", is("LEADER")))
                .andExpect(jsonPath("$.data.roles", contains("TEACHER", "LEADER")));

        mockMvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(403)));

        mockMvc.perform(post("/api/v1/auth/switch-role")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TEACHER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRevokesCurrentToken() throws Exception {
        createUser("student", "学生用户", "Student123!", List.of(UserRole.STUDENT));
        String token = login("student", "Student123!", UserRole.STUDENT);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeRole", is("STUDENT")));

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    private String login(String username, String password, UserRole activeRole) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(username, password, activeRole))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private AppUser createUser(String username, String displayName, String password, List<UserRole> roles) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(true);
        user = userRepository.save(user);
        for (UserRole role : roles) {
            UserRoleAssignment assignment = new UserRoleAssignment();
            assignment.setUserId(user.getId());
            assignment.setRole(role);
            roleRepository.save(assignment);
        }
        return user;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoginPayload(String username, String password, UserRole activeRole) {
    }
}
