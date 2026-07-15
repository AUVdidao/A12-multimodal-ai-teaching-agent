package com.auvdidao.a12teachingagent.artifactexport;

import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.ExportType;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.TaskPriority;
import com.auvdidao.a12teachingagent.domain.common.TeachingTaskStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.exportrecord.repository.ExportRecordRepository;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.GenerationPlan;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.teachingtask.TeachingTask;
import com.auvdidao.a12teachingagent.domain.teachingtask.repository.TeachingTaskRepository;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.CourseInfo;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.DocSection;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.LessonPlanContent;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.PptContent;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.PptSlide;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "a12.security.enabled=true",
        "a12.security.demo-seed-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ArtifactExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TeachingTaskRepository taskRepository;

    @Autowired
    private GenerationPlanRepository planRepository;

    @Autowired
    private ArtifactVersionRepository versionRepository;

    @Autowired
    private GeneratedArtifactRepository artifactRepository;

    @Autowired
    private ExportRecordRepository exportRecordRepository;

    @Test
    void catalogRequiresTeacherAndListsOnlyProjectArtifacts() throws Exception {
        Fixture fixture = createExportableProject("人工智能基础");

        mockMvc.perform(get("/api/v1/projects/{projectId}/exports", fixture.project().getId()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/projects/{projectId}/exports", fixture.project().getId())
                        .with(user(UserRole.STUDENT, 20L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(403)));

        mockMvc.perform(get("/api/v1/projects/{projectId}/exports", fixture.project().getId())
                        .with(user(UserRole.TEACHER, 11L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", is("The project is not assigned to the current teacher")));

        mockMvc.perform(get("/api/v1/projects/{projectId}/exports", fixture.project().getId())
                        .with(user(UserRole.TEACHER, 10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId", is(fixture.project().getId().intValue())))
                .andExpect(jsonPath("$.data.formats", hasSize(2)))
                .andExpect(jsonPath("$.data.formats[0].format", is("PPTX")))
                .andExpect(jsonPath("$.data.formats[0].artifactId", is(fixture.ppt().getId().intValue())))
                .andExpect(jsonPath("$.data.formats[0].versionNumber", is(2)))
                .andExpect(jsonPath("$.data.formats[0].downloadUrl", is(
                        "/api/v1/projects/" + fixture.project().getId() + "/exports/pptx"
                )))
                .andExpect(jsonPath("$.data.formats[1].format", is("DOCX")))
                .andExpect(jsonPath("$.data.formats[1].artifactId", is(fixture.docx().getId().intValue())));
    }

    @Test
    void downloadsParseablePptxAndDocxAndRecordsSuccessfulExports() throws Exception {
        Fixture fixture = createExportableProject("人工智能基础");

        MvcResult pptxResult = mockMvc.perform(get("/api/v1/projects/{projectId}/exports/pptx", fixture.project().getId())
                        .with(user(UserRole.TEACHER, 10L)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(ArtifactExportService.PPTX_MEDIA_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".pptx")))
                .andReturn();

        byte[] pptxBytes = pptxResult.getResponse().getContentAsByteArray();
        assertThat(pptxBytes).startsWith((byte) 'P', (byte) 'K');
        try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(pptxBytes))) {
            assertThat(presentation.getSlides()).hasSize(2);
            String text = presentation.getSlides().stream()
                    .flatMap(slide -> slide.getShapes().stream())
                    .filter(XSLFTextShape.class::isInstance)
                    .map(XSLFTextShape.class::cast)
                    .map(XSLFTextShape::getText)
                    .collect(Collectors.joining("\n"));
            assertThat(text).contains("人工智能基础", "Machine learning and deep learning");
            String notes = presentation.getNotesSlide(presentation.getSlides().get(1)).getTextParagraphs().stream()
                    .flatMap(List::stream)
                    .map(XSLFTextParagraph::getText)
                    .collect(Collectors.joining("\n"));
            assertThat(notes).contains("Compare the concepts");
        }

        MvcResult docxResult = mockMvc.perform(get("/api/v1/projects/{projectId}/exports/DOCX", fixture.project().getId())
                        .with(user(UserRole.TEACHER, 10L)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(ArtifactExportService.DOCX_MEDIA_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".docx")))
                .andReturn();

        byte[] docxBytes = docxResult.getResponse().getContentAsByteArray();
        assertThat(docxBytes).startsWith((byte) 'P', (byte) 'K');
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            String paragraphs = document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText())
                    .collect(Collectors.joining("\n"));
            String tables = document.getTables().stream()
                    .flatMap(table -> table.getRows().stream())
                    .flatMap(row -> row.getTableCells().stream())
                    .map(cell -> cell.getText())
                    .collect(Collectors.joining("\n"));
            assertThat(paragraphs).contains("人工智能基础教案", "教学目标", "Explain core AI concepts");
            assertThat(tables).contains("课程名称", "AI foundations");
        }

        assertThat(exportRecordRepository.findByProjectIdOrderByCreatedAtAsc(fixture.project().getId()))
                .extracting(record -> record.getExportType())
                .containsExactly(ExportType.PPTX, ExportType.DOCX);
    }

    @Test
    void emptyUnsupportedAndCrossProjectRequestsHaveClearResponses() throws Exception {
        createExportableProject("Owned source project");
        Project emptyProject = createProject("Empty target project");
        assignProject(emptyProject, 10L);

        mockMvc.perform(get("/api/v1/projects/{projectId}/exports", emptyProject.getId())
                        .with(user(UserRole.TEACHER, 10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formats", hasSize(0)));

        mockMvc.perform(get("/api/v1/projects/{projectId}/exports/pptx", emptyProject.getId())
                        .with(user(UserRole.TEACHER, 10L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("PPTX artifact not found")));

        mockMvc.perform(get("/api/v1/projects/{projectId}/exports/pdf", emptyProject.getId())
                        .with(user(UserRole.TEACHER, 10L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Supported formats: PPTX, DOCX")));

        mockMvc.perform(get("/api/v1/projects/{projectId}/exports", 999999L)
                        .with(user(UserRole.TEACHER, 10L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Project not found: 999999")));

        mockMvc.perform(get("/api/v1/projects/{projectId}/exports", 0L)
                        .with(user(UserRole.TEACHER, 10L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("projectId must be greater than 0")));
    }

    @Test
    void malformedPersistedArtifactContentReturnsValidationErrorWithoutExportRecord() throws Exception {
        Project project = createProject("Malformed content");
        assignProject(project, 10L);
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setProjectId(project.getId());
        artifact.setArtifactType(ArtifactType.PPT);
        artifact.setTitle("Broken deck");
        artifact.setSchemaVersion(1);
        artifact.setContentJson("{not-json}");
        artifactRepository.save(artifact);

        mockMvc.perform(get("/api/v1/projects/{projectId}/exports/pptx", project.getId())
                        .with(user(UserRole.TEACHER, 10L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("content JSON does not match schema version 1")));

        assertThat(exportRecordRepository.findByProjectIdOrderByCreatedAtAsc(project.getId())).isEmpty();
    }

    private Fixture createExportableProject(String name) throws JsonProcessingException {
        Project project = createProject(name);
        assignProject(project, 10L);

        GenerationPlan plan = new GenerationPlan();
        plan.setProjectId(project.getId());
        plan.setProvider("MOCK");
        plan.setPptOutline("[]");
        plan.setDocOutline("[]");
        plan.setInteractionPlan("[]");
        plan.setConfirmed(true);
        plan = planRepository.save(plan);

        ArtifactVersion version = new ArtifactVersion();
        version.setProjectId(project.getId());
        version.setGenerationPlanId(plan.getId());
        version.setVersionNumber(2);
        version.setDescription("Export test version");
        version.setFinalVersion(false);
        version = versionRepository.save(version);

        PptContent pptContent = new PptContent(
                name,
                "Clear classroom theme",
                List.of(
                        new PptSlide(1, "COVER", name, "TITLE", List.of("AI foundations"), "Introduce the course"),
                        new PptSlide(2, "CONTENT", "Core concepts", "BULLETS",
                                List.of("Machine learning and deep learning", "Classroom case"), "Compare the concepts")
                )
        );
        GeneratedArtifact ppt = artifact(project, plan, version, ArtifactType.PPT, name + "课件", pptContent);

        LessonPlanContent docxContent = new LessonPlanContent(
                name + "教案",
                new CourseInfo(name, "AI foundations", "Core concepts", "First-year students", 90, "STANDARD"),
                List.of("Explain core AI concepts"),
                List.of("Machine learning workflow"),
                List.of("Distinguish related concepts"),
                List.of("Case-based explanation"),
                List.of(),
                List.of("Concept check"),
                List.of("Analyze one AI application"),
                List.of("Confirmed teaching evidence"),
                List.of(
                        new DocSection(1, "教学目标", List.of("Explain core AI concepts")),
                        new DocSection(2, "教学过程", List.of("Use a classroom case and guided practice"))
                )
        );
        GeneratedArtifact docx = artifact(project, plan, version, ArtifactType.DOCX, name + "教案", docxContent);
        return new Fixture(project, ppt, docx);
    }

    private GeneratedArtifact artifact(
            Project project,
            GenerationPlan plan,
            ArtifactVersion version,
            ArtifactType type,
            String title,
            Object content
    ) throws JsonProcessingException {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setProjectId(project.getId());
        artifact.setGenerationPlanId(plan.getId());
        artifact.setVersionId(version.getId());
        artifact.setArtifactType(type);
        artifact.setTitle(title);
        artifact.setSchemaVersion(1);
        artifact.setContentJson(objectMapper.writeValueAsString(content));
        return artifactRepository.save(artifact);
    }

    private Project createProject(String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setCourseName("AI foundations");
        project.setChapterTopic("Core concepts");
        project.setTargetAudience("First-year students");
        project.setLessonDurationMinutes(90);
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.GENERATED);
        return projectRepository.save(project);
    }

    private void assignProject(Project project, Long teacherId) {
        TeachingTask task = new TeachingTask();
        task.setTaskName("Export " + project.getProjectName());
        task.setCourseId(1L);
        task.setChapterTitle(project.getChapterTopic());
        task.setAssigneeId(teacherId);
        task.setRequirements("Create classroom-ready teaching artifacts");
        task.setPriority(TaskPriority.MEDIUM);
        task.setDueAt(LocalDateTime.now().plusDays(7));
        task.setCreatedBy(99L);
        task.setLinkedProjectId(project.getId());
        task.setTaskStatus(TeachingTaskStatus.IN_PROGRESS);
        taskRepository.save(task);
    }

    private static RequestPostProcessor user(UserRole role, Long userId) {
        AuthenticatedUser principal = new AuthenticatedUser(
                100L + userId,
                userId,
                role.name().toLowerCase(),
                role.name(),
                role
        );
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
        return authentication(token);
    }

    private record Fixture(Project project, GeneratedArtifact ppt, GeneratedArtifact docx) {
    }
}
