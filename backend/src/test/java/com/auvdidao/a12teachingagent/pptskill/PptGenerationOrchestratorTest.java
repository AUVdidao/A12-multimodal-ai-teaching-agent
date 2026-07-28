package com.auvdidao.a12teachingagent.pptskill;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PptGenerationOrchestratorTest {

    @TempDir
    Path tempDir;

    private ProjectRepository projectRepository;
    private GenerationPlanRepository planRepository;
    private ArtifactVersionRepository versionRepository;
    private GeneratedArtifactRepository artifactRepository;
    private ProjectAccessService accessService;
    private PptSkillRunnerClient runnerClient;
    private PptOutlineProviderRouter outlineProviderRouter;
    private PptGeneratorProperties properties;
    private PptSkillFileStore fileStore;
    private PptGenerationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        planRepository = mock(GenerationPlanRepository.class);
        versionRepository = mock(ArtifactVersionRepository.class);
        artifactRepository = mock(GeneratedArtifactRepository.class);
        accessService = mock(ProjectAccessService.class);
        runnerClient = mock(PptSkillRunnerClient.class);
        outlineProviderRouter = mock(PptOutlineProviderRouter.class);
        properties = new PptGeneratorProperties();
        properties.setProvider("PRESENTATION_SKILL");
        properties.setFixtureEnabled(true);
        properties.setStorageDir(tempDir.toString());
        fileStore = new PptSkillFileStore(properties);
        orchestrator = new PptGenerationOrchestrator(
                projectRepository, planRepository, versionRepository, artifactRepository,
                accessService, outlineProviderRouter,
                runnerClient, fileStore, properties, new ObjectMapper()
        );
        Project project = new Project();
        project.setId(12L);
        project.setProjectName("光合作用");
        when(projectRepository.findById(12L)).thenReturn(Optional.of(project));
        when(outlineProviderRouter.getOutline(any(Project.class))).thenReturn(new ObjectMapper().createObjectNode().put("title", "fixture"));
        when(planRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(12L)).thenReturn(Optional.empty());
        when(versionRepository.findByProjectIdOrderByCreatedAtAsc(12L)).thenReturn(List.of());
        when(versionRepository.save(any(ArtifactVersion.class))).thenAnswer(invocation -> {
            ArtifactVersion version = invocation.getArgument(0);
            version.setId(33L);
            return version;
        });
        when(artifactRepository.save(any())).thenAnswer(invocation -> {
            com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact artifact = invocation.getArgument(0);
            artifact.setId(44L);
            return artifact;
        });
    }

    @Test
    void successStoresArtifactAndSafeDownloadUrl() {
        byte[] pptx = "pptx".getBytes();
        when(runnerClient.generate(any(), any())).thenReturn(result(pptx, true, "AUTOMATED_GEOMETRY_ONLY"));

        PptGenerationDtos.GenerationResponse response = orchestrator.generate(12L);

        assertEquals(44L, response.artifactId());
        assertEquals("/api/v1/projects/12/exports/pptx", response.downloadUrl());
        assertEquals(64, response.sha256().length());
        verify(versionRepository).save(any(ArtifactVersion.class));
        verify(artifactRepository).save(any());
        assertEquals(pptx.length, fileStore.readManaged(tempDir.resolve("project-12").toFile().listFiles()[0].toString()).length);
    }

    @Test
    void qaFailureDoesNotSaveVersion() {
        when(runnerClient.generate(any(), any())).thenReturn(result("pptx".getBytes(), false, "AUTOMATED_GEOMETRY_ONLY"));
        PptSkillGenerationException exception = assertThrows(PptSkillGenerationException.class, () -> orchestrator.generate(12L));
        assertEquals("PPT_QA_FAILED", exception.getCode());
        verify(versionRepository, never()).save(any());
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void hashMismatchDoesNotSaveVersion() {
        PptSkillRunnerDtos.RunnerResult result = result("pptx".getBytes(), true, "AUTOMATED_GEOMETRY_ONLY");
        when(runnerClient.generate(any(), any())).thenReturn(new PptSkillRunnerDtos.RunnerResult(
                result.jobId(), result.status(), result.fileName(), result.sizeBytes(), "bad", result.qa(),
                result.buildDurationMs(), result.qaDurationMs(), result.totalDurationMs(), result.pptx(), result.outlineJson(), result.qaReportJson()
        ));
        PptSkillGenerationException exception = assertThrows(PptSkillGenerationException.class, () -> orchestrator.generate(12L));
        assertEquals("PPT_HASH_MISMATCH", exception.getCode());
        verify(versionRepository, never()).save(any());
    }

    @Test
    void legacyProviderNeverCallsRunner() {
        properties.setProvider("LEGACY");
        PptSkillGenerationException exception = assertThrows(PptSkillGenerationException.class, () -> orchestrator.generate(12L));
        assertEquals("LEGACY_RENDER_FAILED", exception.getCode());
        verifyNoInteractions(runnerClient);
    }

    @Test
    void unsupportedQaLevelIsRejected() {
        when(runnerClient.generate(any(), any())).thenReturn(result("pptx".getBytes(), true, "MANUAL_REVIEW"));
        PptSkillGenerationException exception = assertThrows(PptSkillGenerationException.class, () -> orchestrator.generate(12L));
        assertEquals("UNSUPPORTED_QA_LEVEL", exception.getCode());
        verify(versionRepository, never()).save(any());
    }

    private static PptSkillRunnerDtos.RunnerResult result(byte[] content, boolean passed, String qaLevel) {
        final String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        return new PptSkillRunnerDtos.RunnerResult(
                "job-1", "SUCCEEDED", "presentation.pptx", content.length, hash,
                new PptSkillRunnerDtos.RunnerQa(passed, qaLevel, Map.of()), 10, 20, 30,
                content, "{}", "{}"
        );
    }
}
