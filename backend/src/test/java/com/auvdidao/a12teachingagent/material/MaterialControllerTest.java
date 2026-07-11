package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Rollback
class MaterialControllerTest {

    private static final byte[] SAMPLE = "safe prototype material".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequirementSummaryRepository summaryRepository;

    @Autowired
    private UploadedMaterialRepository materialRepository;

    @Autowired
    private StorageProperties storageProperties;

    @BeforeEach
    void prepareStorage() throws IOException {
        deleteStorage();
    }

    @AfterEach
    void cleanStorage() throws IOException {
        deleteStorage();
    }

    @Test
    void uploadsPdf() throws Exception {
        Project project = createProject(true);

        upload(project.getId(), file("教材依据.pdf", "application/pdf", SAMPLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename", is("教材依据.pdf")))
                .andExpect(jsonPath("$.data.fileType", is("PDF")))
                .andExpect(jsonPath("$.data.uploadStatus", is("UPLOADED")))
                .andExpect(jsonPath("$.data.parseStatus", is("NOT_STARTED")))
                .andExpect(jsonPath("$.data.filePath").doesNotExist());
    }

    @Test
    void uploadsDocx() throws Exception {
        Project project = createProject(true);

        upload(project.getId(), file(
                "教学设计.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                SAMPLE
        )).andExpect(status().isOk()).andExpect(jsonPath("$.data.fileType", is("DOCX")));
    }

    @Test
    void uploadsPptx() throws Exception {
        Project project = createProject(true);

        upload(project.getId(), file(
                "课堂案例.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                SAMPLE
        )).andExpect(status().isOk()).andExpect(jsonPath("$.data.fileType", is("PPTX")));
    }

    @Test
    void uploadsPngWithChineseSpacesAndParentheses() throws Exception {
        Project project = createProject(true);

        upload(project.getId(), file("光合作用 示意图 (课堂版).png", "image/png", SAMPLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename", is("光合作用 示意图 (课堂版).png")))
                .andExpect(jsonPath("$.data.fileType", is("PNG")));
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        Project project = createProject(true);

        upload(project.getId(), file("empty.pdf", "application/pdf", new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        Project project = createProject(true);

        upload(project.getId(), file("payload.html", "text/html", SAMPLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Unsupported file extension")));
    }

    @Test
    void rejectsDoubleExtensionExecutable() throws Exception {
        Project project = createProject(true);

        upload(project.getId(), file("lesson.pdf.exe", "application/pdf", SAMPLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Unsupported file extension")));
    }

    @Test
    void rejectsMimeMismatch() throws Exception {
        Project project = createProject(true);

        upload(project.getId(), file("lesson.pdf", "image/png", SAMPLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("MIME")));
    }

    @Test
    void rejectsOversizedFile() throws Exception {
        Project project = createProject(true);
        byte[] oversized = new byte[20 * 1024 * 1024 + 1];

        upload(project.getId(), file("large.pdf", "application/pdf", oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code", is(413)));
    }

    @Test
    void rejectsZeroProjectId() throws Exception {
        upload(0L, file("lesson.pdf", "application/pdf", SAMPLE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNegativeProjectId() throws Exception {
        upload(-1L, file("lesson.pdf", "application/pdf", SAMPLE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingProject() throws Exception {
        upload(999999L, file("lesson.pdf", "application/pdf", SAMPLE))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresConfirmedSummaryBeforeUpload() throws Exception {
        Project project = createProject(false);

        upload(project.getId(), file("lesson.pdf", "application/pdf", SAMPLE))
                .andExpect(status().isConflict());
    }

    @Test
    void listsAndReadsMaterialWithoutExposingStoragePath() throws Exception {
        Project project = createProject(true);
        long materialId = materialId(upload(project.getId(), file("lesson.pdf", "application/pdf", SAMPLE)).andReturn());

        mockMvc.perform(get("/api/projects/{projectId}/materials", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is((int) materialId)))
                .andExpect(jsonPath("$.data[0].downloadPath", containsString("/download")));

        mockMvc.perform(get("/api/projects/{projectId}/materials/{materialId}", project.getId(), materialId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename", is("lesson.pdf")))
                .andExpect(jsonPath("$.data.filePath").doesNotExist());
    }

    @Test
    void downloadsExistingMaterialThroughControlledEndpoint() throws Exception {
        Project project = createProject(true);
        long materialId = materialId(upload(project.getId(), file("教材 (一).pdf", "application/pdf", SAMPLE)).andReturn());

        mockMvc.perform(get("/api/projects/{projectId}/materials/{materialId}/download", project.getId(), materialId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(SAMPLE))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", not(containsString("\r"))));
    }

    @Test
    void returnsNotFoundForUnknownDownload() throws Exception {
        Project project = createProject(true);

        mockMvc.perform(get("/api/projects/{projectId}/materials/999999/download", project.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsNotFoundWhenStoredFileIsMissing() throws Exception {
        Project project = createProject(true);
        long materialId = materialId(upload(project.getId(), file("lesson.pdf", "application/pdf", SAMPLE)).andReturn());
        UploadedMaterial material = materialRepository.findById(materialId).orElseThrow();
        Files.deleteIfExists(storageRoot().resolve(material.getFilePath()));

        mockMvc.perform(get("/api/projects/{projectId}/materials/{materialId}/download", project.getId(), materialId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("missing")));
    }

    @Test
    void stripsTraversalSegmentsFromOriginalFilename() throws Exception {
        Project project = createProject(true);

        upload(project.getId(), file("../../课堂材料.pdf", "application/pdf", SAMPLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename", is("课堂材料.pdf")));

        UploadedMaterial stored = materialRepository.findByProjectIdOrderByCreatedAtAsc(project.getId()).get(0);
        org.assertj.core.api.Assertions.assertThat(storageRoot().resolve(stored.getFilePath()).normalize())
                .startsWith(storageRoot());
    }

    @Test
    void isolatesMaterialsBetweenProjects() throws Exception {
        Project owner = createProject(true);
        Project other = createProject(true);
        long materialId = materialId(upload(owner.getId(), file("owner.pdf", "application/pdf", SAMPLE)).andReturn());

        mockMvc.perform(get("/api/projects/{projectId}/materials/{materialId}", other.getId(), materialId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects/{projectId}/materials", other.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void bindsDeduplicatesAndRestoresStableUsages() throws Exception {
        Project project = createProject(true);
        long materialId = materialId(upload(project.getId(), file("lesson.pdf", "application/pdf", SAMPLE)).andReturn());

        updateUsages(project.getId(), materialId, """
                {
                  "usageTypes": ["TEXTBOOK_BASIS", "CASE_MATERIAL", "TEXTBOOK_BASIS"],
                  "note": "  重点参考章节结构  "
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usageTypes", contains("TEXTBOOK_BASIS", "CASE_MATERIAL")))
                .andExpect(jsonPath("$.data.note", is("重点参考章节结构")));

        mockMvc.perform(get(
                        "/api/projects/{projectId}/materials/{materialId}/usages",
                        project.getId(),
                        materialId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usageTypes", contains("TEXTBOOK_BASIS", "CASE_MATERIAL")));
    }

    @Test
    void usageUpdateOverwritesInsteadOfAppending() throws Exception {
        Project project = createProject(true);
        long materialId = materialId(upload(project.getId(), file("lesson.pdf", "application/pdf", SAMPLE)).andReturn());
        updateUsages(project.getId(), materialId, "{\"usageTypes\":[\"TEXTBOOK_BASIS\"]}")
                .andExpect(status().isOk());

        updateUsages(project.getId(), materialId, "{\"usageTypes\":[\"EXERCISE_SOURCE\"],\"note\":\"练习\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usageTypes", contains("EXERCISE_SOURCE")));

        mockMvc.perform(get(
                        "/api/projects/{projectId}/materials/{materialId}/usages",
                        project.getId(),
                        materialId
                ))
                .andExpect(jsonPath("$.data.usageTypes", contains("EXERCISE_SOURCE")));
    }

    @Test
    void rejectsEmptyUnsupportedAndCrossProjectUsages() throws Exception {
        Project owner = createProject(true);
        Project other = createProject(true);
        long materialId = materialId(upload(owner.getId(), file("lesson.pdf", "application/pdf", SAMPLE)).andReturn());

        updateUsages(owner.getId(), materialId, "{\"usageTypes\":[]}")
                .andExpect(status().isBadRequest());
        updateUsages(owner.getId(), materialId, "{\"usageTypes\":[\"VIDEO_CONTENT\"]}")
                .andExpect(status().isBadRequest());
        updateUsages(other.getId(), materialId, "{\"usageTypes\":[\"TEXTBOOK_BASIS\"]}")
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions upload(Long projectId, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/projects/{projectId}/materials", projectId)
                .file(file)
                .param("description", "M2 test material"));
    }

    private org.springframework.test.web.servlet.ResultActions updateUsages(
            Long projectId,
            long materialId,
            String body
    ) throws Exception {
        return mockMvc.perform(put(
                        "/api/projects/{projectId}/materials/{materialId}/usages",
                        projectId,
                        materialId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    private long materialId(MvcResult result) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
        return value.longValue();
    }

    private Project createProject(boolean confirmedSummary) {
        Project project = new Project();
        project.setProjectName("M2 material test");
        project.setCourseName("生物");
        project.setChapterTopic("光合作用");
        project.setTargetAudience("八年级");
        project.setLessonDurationMinutes(45);
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(confirmedSummary ? ProjectStatus.REQUIREMENT_CONFIRMED : ProjectStatus.CREATED);
        project = projectRepository.save(project);

        if (confirmedSummary) {
            RequirementSummary summary = new RequirementSummary();
            summary.setProjectId(project.getId());
            summary.setGradeLevel("八年级");
            summary.setSubject("生物");
            summary.setTopic("光合作用");
            summary.setLessonDuration("45分钟");
            summary.setTeachingGoals("解释光合作用的基本过程");
            summary.setKeyPoints("光合作用条件");
            summary.setDifficultPoints("物质与能量转化");
            summary.setOutputTypes(List.of("PPT", "LESSON_PLAN"));
            summary.setStylePreference("清晰自然");
            summary.setGenerationMode(GenerationMode.STANDARD);
            summary.setStatus(RequirementSummaryStatus.CONFIRMED);
            summary.setConfirmedAt(LocalDateTime.now());
            summaryRepository.save(summary);
        }
        return project;
    }

    private Path storageRoot() {
        return Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
    }

    private void deleteStorage() throws IOException {
        Path root = storageRoot();
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }
}
