package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserSessionRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileReviewRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProcessingRunRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateRenderedSlideSetRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateStructuralSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"a12.security.enabled=true", "a12.security.demo-seed-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateProjectIsolationSecurityTest {

    private static final String PASSWORD = "Teacher123!";
    private static final String PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AppUserRepository userRepository;
    @Autowired private UserRoleAssignmentRepository roleRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TemplateProfileReviewRepository reviewRepository;
    @Autowired private TemplateProfileVersionRepository profileRepository;
    @Autowired private TemplateStructuralSnapshotRepository snapshotRepository;
    @Autowired private TemplateRenderedSlideSetRepository renderedRepository;
    @Autowired private TemplateProcessingRunRepository runRepository;
    @Autowired private TemplateSourceVersionRepository sourceRepository;
    @Autowired private TemplateRepository templateRepository;

    @AfterEach
    void clean() {
        reviewRepository.deleteAll(); profileRepository.deleteAll(); snapshotRepository.deleteAll(); renderedRepository.deleteAll();
        runRepository.deleteAll(); sourceRepository.deleteAll(); templateRepository.deleteAll(); projectRepository.deleteAll();
        sessionRepository.deleteAll(); roleRepository.deleteAll(); userRepository.deleteAll();
    }

    @Test
    void onlyOwningTeacherCanUploadOrReadProjectTemplates() throws Exception {
        createTeacher("template-owner");
        createTeacher("template-other");
        String ownerToken = login("template-owner");
        String otherToken = login("template-other");
        long projectId = createProject(ownerToken);

        mockMvc.perform(multipart("/api/projects/{projectId}/templates", projectId)
                        .file(new MockMultipartFile("file", "owner.pptx", PPTX_MIME, pptxBytes()))
                        .header("Authorization", bearer(ownerToken))
                        .param("name", "Owner template"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/{projectId}/templates", projectId).header("Authorization", bearer(otherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/projects/{projectId}/templates", projectId)
                        .file(new MockMultipartFile("file", "other.pptx", PPTX_MIME, pptxBytes()))
                        .header("Authorization", bearer(otherToken))
                        .param("name", "Other template"))
                .andExpect(status().isForbidden());
    }

    private void createTeacher(String username) {
        AppUser user = new AppUser(); user.setUsername(username); user.setDisplayName(username); user.setPasswordHash(passwordEncoder.encode(PASSWORD)); user.setEnabled(true);
        user = userRepository.save(user);
        UserRoleAssignment role = new UserRoleAssignment(); role.setUserId(user.getId()); role.setRole(UserRole.TEACHER); roleRepository.save(role);
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login(username, PASSWORD, UserRole.TEACHER))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private long createProject(String token) throws Exception {
        String response = mockMvc.perform(post("/api/projects").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectName\":\"Template project\",\"courseName\":\"Biology\",\"chapterTitle\":\"Cells\",\"targetStudents\":\"Teachers\",\"lessonDuration\":45}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private byte[] pptxBytes() throws Exception {
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) { show.createSlide(); show.write(output); return output.toByteArray(); }
    }

    private String bearer(String token) { return "Bearer " + token; }
    private record Login(String username, String password, UserRole activeRole) { }
}


