package com.auvdidao.a12teachingagent.pptskill.harness;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.pptskill.PptSkillFileStore;
import com.auvdidao.a12teachingagent.pptskill.PptSkillGenerationException;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PptHarnessGenerationServiceTest {
    @TempDir
    Path tempDir;

    private ProjectRepository projectRepository;
    private ArtifactVersionRepository versionRepository;
    private GeneratedArtifactRepository artifactRepository;
    private PptHarnessClient harnessClient;
    private PptHarnessGenerationService service;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        versionRepository = mock(ArtifactVersionRepository.class);
        artifactRepository = mock(GeneratedArtifactRepository.class);
        harnessClient = mock(PptHarnessClient.class);

        Project project = new Project();
        project.setId(7L);
        project.setProjectName("Photosynthesis");
        project.setCourseName("Biology");
        project.setChapterTopic("Photosynthesis");
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(versionRepository.findByProjectIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(artifactRepository.findByProjectIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(versionRepository.save(any(ArtifactVersion.class))).thenAnswer(invocation -> {
            ArtifactVersion value = invocation.getArgument(0);
            value.setId(71L);
            return value;
        });

        PptGeneratorProperties properties = new PptGeneratorProperties();
        properties.setStorageDir(tempDir.toString());
        PptTemplateSelectionService selections = mock(PptTemplateSelectionService.class);
        when(selections.get(7L)).thenReturn(new PptTemplateSelectionService.Selection("a12-teaching-generic", "1.0.0"));
        service = new PptHarnessGenerationService(
                projectRepository,
                mock(TeachingIntentRepository.class),
                mock(GenerationPlanRepository.class),
                versionRepository,
                artifactRepository,
                mock(ProjectAccessService.class),
                selections,
                harnessClient,
                new PptSkillFileStore(properties),
                new ObjectMapper()
        );
    }

    @Test
    void succeededJobWithQaAndMatchingHashCreatesArtifactVersion() throws Exception {
        byte[] content = "pptx-content".getBytes();
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        when(harnessClient.get("task-1")).thenReturn(job("task-1", "SUCCEEDED", content.length, hash));
        when(harnessClient.qaReport("task-1")).thenReturn(new PptHarnessDtos.QaReport("task-1", "AUTOMATED_GEOMETRY_ONLY", true, new ObjectMapper().createObjectNode()));
        when(harnessClient.download("task-1")).thenReturn(content);

        service.statusAndFinalize(7L, "task-1");

        verify(versionRepository).save(any(ArtifactVersion.class));
        verify(artifactRepository).save(any());
        verify(harnessClient).download("task-1");
    }

    @Test
    void qaFailureNeverDownloadsOrCreatesArtifactVersion() {
        when(harnessClient.get("task-2")).thenReturn(job("task-2", "SUCCEEDED", 4, "hash"));
        when(harnessClient.qaReport("task-2")).thenReturn(new PptHarnessDtos.QaReport("task-2", "AUTOMATED_GEOMETRY_ONLY", false, new ObjectMapper().createObjectNode()));

        PptSkillGenerationException exception = assertThrows(PptSkillGenerationException.class, () -> service.statusAndFinalize(7L, "task-2"));

        assertEquals("PPT_QA_FAILED", exception.getCode());
        verify(harnessClient, never()).download(any());
        verify(versionRepository, never()).save(any());
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void hashMismatchNeverCreatesArtifactVersion() {
        when(harnessClient.get("task-3")).thenReturn(job("task-3", "SUCCEEDED", 4, "incorrect"));
        when(harnessClient.qaReport("task-3")).thenReturn(new PptHarnessDtos.QaReport("task-3", "AUTOMATED_GEOMETRY_ONLY", true, new ObjectMapper().createObjectNode()));
        when(harnessClient.download("task-3")).thenReturn("pptx".getBytes());

        PptSkillGenerationException exception = assertThrows(PptSkillGenerationException.class, () -> service.statusAndFinalize(7L, "task-3"));

        assertEquals("PPT_HASH_MISMATCH", exception.getCode());
        verify(versionRepository, never()).save(any());
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void startUsesSelectedTemplateAndDoesNotCallAnyAiProvider() {
        when(harnessClient.start(any())).thenReturn(job("task-queued", "QUEUED", 0, null));

        service.start(7L);

        verify(harnessClient).start(argThat(request ->
                request.projectId() == 7L
                        && "a12-teaching-generic".equals(request.templateId())
                        && "1.0.0".equals(request.templateVersion())));
        verifyNoMoreInteractions(harnessClient);
    }

    private static PptHarnessDtos.JobResponse job(String taskId, String status, long size, String hash) {
        return new PptHarnessDtos.JobResponse(
                taskId, "request-1", 7L, status, status, 50, "message", "/status", "/events",
                size == 0 ? null : new PptHarnessDtos.ArtifactRef("presentation.pptx", size, hash, null), null
        );
    }
}
