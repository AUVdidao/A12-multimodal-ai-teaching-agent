package com.auvdidao.a12teachingagent.pptskill.harness;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GenerationPlan;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntentEvidence;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
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
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PptHarnessGenerationServiceTest {
    @TempDir
    Path tempDir;

    private ProjectRepository projectRepository;
    private TeachingIntentRepository intentRepository;
    private GenerationPlanRepository planRepository;
    private RequirementSummaryRepository summaryRepository;
    private KnowledgeChunkRepository knowledgeChunkRepository;
    private UploadedMaterialRepository uploadedMaterialRepository;
    private ArtifactVersionRepository versionRepository;
    private GeneratedArtifactRepository artifactRepository;
    private PptHarnessClient harnessClient;
    private PptHarnessGenerationService service;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        intentRepository = mock(TeachingIntentRepository.class);
        planRepository = mock(GenerationPlanRepository.class);
        summaryRepository = mock(RequirementSummaryRepository.class);
        knowledgeChunkRepository = mock(KnowledgeChunkRepository.class);
        uploadedMaterialRepository = mock(UploadedMaterialRepository.class);
        versionRepository = mock(ArtifactVersionRepository.class);
        artifactRepository = mock(GeneratedArtifactRepository.class);
        harnessClient = mock(PptHarnessClient.class);

        Project project = new Project();
        project.setId(7L);
        project.setProjectName("Photosynthesis");
        project.setCourseName("Biology");
        project.setChapterTopic("Photosynthesis");
        project.setTargetAudience("Grade 8");
        project.setLessonDurationMinutes(45);
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(versionRepository.findByProjectIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(artifactRepository.findByProjectIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(versionRepository.save(any(ArtifactVersion.class))).thenAnswer(invocation -> {
            ArtifactVersion value = invocation.getArgument(0);
            value.setId(71L);
            return value;
        });
        when(summaryRepository.findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(eq(7L), eq(RequirementSummaryStatus.CONFIRMED)))
                .thenReturn(Optional.of(confirmedSummary()));
        when(intentRepository.findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(eq(7L), eq(TeachingIntentStatus.CONFIRMED)))
                .thenReturn(Optional.of(confirmedIntent()));
        when(planRepository.findFirstByProjectIdAndConfirmedTrueOrderByCreatedAtDescIdDesc(7L))
                .thenReturn(Optional.of(confirmedPlan()));
        when(knowledgeChunkRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(uploadedMaterialRepository.findById(anyLong())).thenReturn(Optional.empty());

        PptGeneratorProperties properties = new PptGeneratorProperties();
        properties.setStorageDir(tempDir.toString());
        PptTemplateSelectionService selections = mock(PptTemplateSelectionService.class);
        when(selections.get(7L)).thenReturn(new PptTemplateSelectionService.Selection("a12-teaching-generic", "1.0.0"));
        service = new PptHarnessGenerationService(
                projectRepository,
                intentRepository,
                planRepository,
                versionRepository,
                artifactRepository,
                mock(ProjectAccessService.class),
                selections,
                harnessClient,
                new PptSkillFileStore(properties),
                new ObjectMapper(),
                summaryRepository,
                knowledgeChunkRepository,
                uploadedMaterialRepository
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
                        && "1.0.0".equals(request.templateVersion())
                        && request.jobSnapshot() != null));
        verifyNoMoreInteractions(harnessClient);
    }

    @Test
    void startCapturesConfirmedTeachingContextAndAuthoritativeOutline() throws Exception {
        when(harnessClient.start(any())).thenReturn(job("task-queued", "QUEUED", 0, null));

        var captor = org.mockito.ArgumentCaptor.forClass(PptHarnessDtos.StartRequest.class);
        service.start(7L);
        verify(harnessClient).start(captor.capture());

        PptHarnessDtos.StartRequest request = captor.getValue();
        assertNotEquals(9, request.targetSlideCount());
        assertEquals(7L, request.jobSnapshot().project().projectId());
        assertEquals("Photosynthesis", request.jobSnapshot().requirementSummary().topic());
        assertEquals("Confirmed teacher outline", request.jobSnapshot().confirmedGenerationPlan().pptOutline().get(0).title());
        assertEquals(List.of("Teacher-confirmed points"), request.jobSnapshot().confirmedGenerationPlan().pptOutline().get(0).points());
        assertEquals("Biology source.pdf", request.jobSnapshot().materialEvidence().get(0).sourceName());
        assertEquals(12L, request.jobSnapshot().materialEvidence().get(0).materialId());
        assertEquals(13L, request.jobSnapshot().materialEvidence().get(0).chunkId());
    }

    @Test
    void unconfirmedPlanIsNotUsedAsAuthoritativeOutline() {
        when(planRepository.findFirstByProjectIdAndConfirmedTrueOrderByCreatedAtDescIdDesc(7L)).thenReturn(Optional.empty());

        PptSkillGenerationException exception = assertThrows(PptSkillGenerationException.class, () -> service.start(7L));

        assertEquals("PPT_PLAN_NOT_CONFIRMED", exception.getCode());
        verify(planRepository, never()).findFirstByProjectIdOrderByCreatedAtDescIdDesc(7L);
        verifyNoInteractions(harnessClient);
    }

    @Test
    void evidenceIsBoundedAndDoesNotIncludeForeignProjectChunks() {
        TeachingIntent intent = confirmedIntent();
        List<TeachingIntentEvidence> evidence = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            TeachingIntentEvidence item = new TeachingIntentEvidence();
            item.setMaterialId(12L);
            item.setKnowledgeChunkId(13L);
            item.setSourceFilename("Biology source.pdf");
            item.setContentExcerpt("x".repeat(1_000));
            evidence.add(item);
        }
        intent.setEvidenceItems(evidence);
        when(intentRepository.findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(eq(7L), eq(TeachingIntentStatus.CONFIRMED)))
                .thenReturn(Optional.of(intent));
        when(harnessClient.start(any())).thenReturn(job("task-queued", "QUEUED", 0, null));

        var captor = org.mockito.ArgumentCaptor.forClass(PptHarnessDtos.StartRequest.class);
        service.start(7L);
        verify(harnessClient).start(captor.capture());
        var bounded = captor.getValue().jobSnapshot().materialEvidence();
        assertEquals(20, bounded.size());
        assertTrue(bounded.stream().allMatch(item -> item.text().length() <= 4_000));
        assertTrue(bounded.stream().mapToInt(item -> item.text().length()).sum() <= 24_000);

        KnowledgeChunk foreign = new KnowledgeChunk();
        foreign.setId(13L);
        foreign.setProjectId(99L);
        when(knowledgeChunkRepository.findById(13L)).thenReturn(Optional.of(foreign));
        service.start(7L);
        var allRequests = org.mockito.ArgumentCaptor.forClass(PptHarnessDtos.StartRequest.class);
        verify(harnessClient, times(2)).start(allRequests.capture());
        assertTrue(allRequests.getAllValues().get(1).jobSnapshot().materialEvidence().isEmpty());
    }

    private static RequirementSummary confirmedSummary() {
        RequirementSummary summary = new RequirementSummary();
        summary.setId(41L);
        summary.setProjectId(7L);
        summary.setSubject("Biology");
        summary.setTopic("Photosynthesis");
        summary.setGradeLevel("Grade 8");
        summary.setLessonDuration("20 minutes");
        summary.setTeachingGoals("Understand the core concept; Explain the process");
        summary.setKeyPoints("Core concept");
        summary.setDifficultPoints("Apply the concept");
        summary.setStylePreference("Clear");
        summary.setInteractionType("Discussion");
        summary.setOutputTypes(List.of("PPT"));
        summary.setStatus(RequirementSummaryStatus.CONFIRMED);
        return summary;
    }

    private static TeachingIntent confirmedIntent() {
        TeachingIntent intent = new TeachingIntent();
        intent.setId(51L);
        intent.setProjectId(7L);
        intent.setStatus(TeachingIntentStatus.CONFIRMED);
        intent.setGenerationGoal("Teach photosynthesis");
        intent.setGenerationGoals(List.of("Understand", "Explain"));
        intent.setContentBasis("Confirmed classroom material");
        intent.setPrimaryBasis("Biology source");
        intent.setTargetAudience("Grade 8");
        intent.setTotalHours(1);
        intent.setTeachingApproach("Inquiry");
        intent.setInteractionMode("Discussion");
        intent.setOutputTypes(List.of("PPT"));
        intent.setStylePreference("Clear");
        TeachingIntentEvidence evidence = new TeachingIntentEvidence();
        evidence.setMaterialId(12L);
        evidence.setKnowledgeChunkId(13L);
        evidence.setSourceFilename("Biology source.pdf");
        evidence.setContentExcerpt("Grounded evidence");
        evidence.setHitReason("Confirmed evidence");
        intent.setEvidenceItems(List.of(evidence));
        return intent;
    }

    private static GenerationPlan confirmedPlan() {
        GenerationPlan plan = new GenerationPlan();
        plan.setId(61L);
        plan.setProjectId(7L);
        plan.setConfirmed(true);
        plan.setProvider("MOCK");
        plan.setPptOutline("[{\"order\":1,\"title\":\"Confirmed teacher outline\",\"description\":\"Teacher-confirmed points\"}]");
        plan.setInteractionPlan("[\"Ask a question\"]");
        return plan;
    }

    private static PptHarnessDtos.JobResponse job(String taskId, String status, long size, String hash) {
        return new PptHarnessDtos.JobResponse(
                taskId, "request-1", 7L, status, status, 50, "message", "/status", "/events",
                size == 0 ? null : new PptHarnessDtos.ArtifactRef("presentation.pptx", size, hash, null), null
        );
    }
}
