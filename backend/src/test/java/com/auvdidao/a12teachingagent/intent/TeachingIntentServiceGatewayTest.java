package com.auvdidao.a12teachingagent.intent;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentResponse;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.knowledge.KnowledgeSearchService;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeHitResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeSearchResponse;
import com.auvdidao.a12teachingagent.intent.dto.TeachingIntentDtos.TeachingIntentUpdateRequest;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeachingIntentServiceGatewayTest {

    private static final long PROJECT_ID = 71L;

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private RequirementSummaryRepository summaryRepository;
    @Mock
    private UploadedMaterialRepository materialRepository;
    @Mock
    private MaterialPurposeRepository purposeRepository;
    @Mock
    private KnowledgeChunkRepository chunkRepository;
    @Mock
    private TeachingIntentRepository intentRepository;
    @Mock
    private KnowledgeSearchService searchService;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private AIWorkflowGateway aiWorkflowGateway;

    private TeachingIntentService service;

    @BeforeEach
    void setUp() {
        service = new TeachingIntentService(
                projectRepository,
                summaryRepository,
                materialRepository,
                purposeRepository,
                chunkRepository,
                intentRepository,
                searchService,
                projectAccessService,
                aiWorkflowGateway
        );
    }

    @Test
    void callsWf04WithConfirmedSummaryAndRealHitsThenPersistsGroundedIntent() {
        Project project = project();
        RequirementSummary summary = confirmedSummary();
        UploadedMaterial material = parsedMaterial();
        KnowledgeHitResponse hit = knowledgeHit();
        stubPipeline(project, summary, material, hit);
        when(aiWorkflowGateway.buildTeachingIntent(any())).thenReturn(validAiResponse());
        when(intentRepository.save(any(TeachingIntent.class))).thenAnswer(invocation -> {
            TeachingIntent saved = invocation.getArgument(0);
            saved.setId(801L);
            return saved;
        });

        var response = service.generate(PROJECT_ID);

        ArgumentCaptor<TeachingIntentRequest> gatewayRequest = ArgumentCaptor.forClass(TeachingIntentRequest.class);
        verify(aiWorkflowGateway).buildTeachingIntent(gatewayRequest.capture());
        assertThat(gatewayRequest.getValue().projectId()).isEqualTo(PROJECT_ID);
        assertThat(gatewayRequest.getValue().requirementSummary().courseName()).isEqualTo("生物");
        assertThat(gatewayRequest.getValue().requirementSummary().chapterTopic()).isEqualTo("光合作用");
        assertThat(gatewayRequest.getValue().requirementSummary().lessonDurationMinutes()).isEqualTo(45);
        assertThat(gatewayRequest.getValue().knowledgeSnippets()).hasSize(1);
        assertThat(gatewayRequest.getValue().knowledgeSnippets().get(0).sourceName()).isEqualTo("生物教材.pdf");
        assertThat(gatewayRequest.getValue().knowledgeSnippets().get(0).content()).contains("叶绿体");

        ArgumentCaptor<TeachingIntent> savedIntent = ArgumentCaptor.forClass(TeachingIntent.class);
        verify(intentRepository).save(savedIntent.capture());
        TeachingIntent saved = savedIntent.getValue();
        assertThat(saved.getGenerationGoals()).containsExactly("理解光合作用", "AI 目标一", "AI 目标二");
        assertThat(saved.getContentBasis()).contains("教师已确认需求为主", "检索依据一", "生物教材.pdf");
        assertThat(saved.getInteractionMode()).isEqualTo("AI 提问；AI 练习");
        assertThat(saved.getOutputTypes()).containsExactly("PPT", "DOCX");
        assertThat(saved.getNotes()).isEqualTo("请确认教学意图");
        assertThat(saved.getStatus()).isEqualTo(TeachingIntentStatus.DRAFT);
        assertThat(saved.getEvidenceItems()).singleElement().satisfies(evidence -> {
            assertThat(evidence.getMaterialId()).isEqualTo(301L);
            assertThat(evidence.getKnowledgeChunkId()).isEqualTo(401L);
            assertThat(evidence.getSourceFilename()).isEqualTo("生物教材.pdf");
            assertThat(evidence.getHitReason()).isEqualTo("命中主题与关键词");
        });
        assertThat(response.id()).isEqualTo(801L);
        verify(projectAccessService).requireAccess(project);
    }

    @Test
    void repeatedGenerationForSameConfirmedSummaryIsIdempotentAndSkipsWf04() {
        Project project = project();
        RequirementSummary summary = confirmedSummary();
        TeachingIntent existing = existingIntent(summary.getId());
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(summaryRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(PROJECT_ID))
                .thenReturn(Optional.of(summary));
        when(intentRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(PROJECT_ID))
                .thenReturn(Optional.of(existing));

        var response = service.generate(PROJECT_ID);

        assertThat(response.id()).isEqualTo(existing.getId());
        verifyNoInteractions(materialRepository, purposeRepository, chunkRepository, searchService, aiWorkflowGateway);
        verify(intentRepository, never()).save(any());
    }

    @Test
    void rejectsIncompleteWf04OutputWithoutPersisting() {
        stubPipeline(project(), confirmedSummary(), parsedMaterial(), knowledgeHit());
        when(aiWorkflowGateway.buildTeachingIntent(any())).thenReturn(new TeachingIntentResponse(
                "wf-04",
                "intent-71",
                List.of(),
                List.of("检索依据"),
                List.of("互动"),
                List.of("PPT"),
                "请确认"
        ));

        assertThatThrownBy(() -> service.generate(PROJECT_ID))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("WF-04");
        verify(intentRepository, never()).save(any());
    }

    @Test
    void projectAccessFailureStopsGenerationBeforeSummaryOrKnowledgeLookup() {
        Project project = project();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        doThrow(new ForbiddenException("This project belongs to another teacher"))
                .when(projectAccessService).requireAccess(project);

        assertThatThrownBy(() -> service.generate(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(
                summaryRepository,
                materialRepository,
                purposeRepository,
                chunkRepository,
                intentRepository,
                searchService,
                aiWorkflowGateway
        );
    }

    @Test
    void updateKeepsStructuredPrimaryBasisWhenContentBasisIsLong() {
        Project project = project();
        TeachingIntent existing = existingIntent(201L);
        existing.setPrimaryBasis("OFFICIAL_OUTLINE");
        String longContentBasis = "Confirmed requirement and grounded evidence. ".repeat(20);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(intentRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(intentRepository.save(any(TeachingIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.update(PROJECT_ID, existing.getId(), new TeachingIntentUpdateRequest(
                "Updated generation goal",
                longContentBasis,
                "Case-based teaching",
                "Guided discussion",
                List.of("PPT", "DOCX"),
                "Concise"
        ));

        assertThat(response.contentBasis()).isEqualTo(longContentBasis.trim());
        assertThat(existing.getPrimaryBasis()).isEqualTo("OFFICIAL_OUTLINE");
        verify(intentRepository).save(existing);
    }

    private void stubPipeline(
            Project project,
            RequirementSummary summary,
            UploadedMaterial material,
            KnowledgeHitResponse hit
    ) {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(summaryRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(PROJECT_ID))
                .thenReturn(Optional.of(summary));
        when(intentRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(PROJECT_ID))
                .thenReturn(Optional.empty());
        when(materialRepository.findByProjectIdOrderByCreatedAtAsc(PROJECT_ID)).thenReturn(List.of(material));
        when(chunkRepository.countByProjectId(PROJECT_ID)).thenReturn(1L);
        when(searchService.search(PROJECT_ID, "光合作用", 5)).thenReturn(new KnowledgeSearchResponse(
                "光合作用",
                List.of(hit),
                false,
                "test"
        ));
        when(purposeRepository.findByMaterialIdOrderByIdAsc(material.getId()))
                .thenReturn(List.of(materialPurpose(material.getId())));
    }

    private static Project project() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setCourseName("生物");
        project.setChapterTopic("光合作用");
        project.setTargetAudience("八年级");
        project.setLessonDurationMinutes(45);
        project.setGenerationMode(GenerationMode.STANDARD);
        return project;
    }

    private static RequirementSummary confirmedSummary() {
        RequirementSummary summary = new RequirementSummary();
        summary.setId(201L);
        summary.setProjectId(PROJECT_ID);
        summary.setSubject("生物");
        summary.setTopic("光合作用");
        summary.setGradeLevel("八年级");
        summary.setLessonDuration("45分钟");
        summary.setTeachingGoals("理解光合作用");
        summary.setKeyPoints("反应条件");
        summary.setDifficultPoints("物质与能量转化");
        summary.setOutputTypes(List.of("PPT"));
        summary.setStylePreference("清晰自然");
        summary.setInteractionType("探究讨论");
        summary.setStatus(RequirementSummaryStatus.CONFIRMED);
        return summary;
    }

    private static UploadedMaterial parsedMaterial() {
        UploadedMaterial material = new UploadedMaterial();
        material.setId(301L);
        material.setProjectId(PROJECT_ID);
        material.setOriginalFileName("生物教材.pdf");
        material.setParseStatus(MaterialParseStatus.SUCCEEDED);
        return material;
    }

    private static MaterialPurpose materialPurpose(Long materialId) {
        MaterialPurpose purpose = new MaterialPurpose();
        purpose.setProjectId(PROJECT_ID);
        purpose.setMaterialId(materialId);
        purpose.setPurposeType(PurposeType.TEXTBOOK_BASIS);
        return purpose;
    }

    private static KnowledgeHitResponse knowledgeHit() {
        return new KnowledgeHitResponse(
                401L,
                301L,
                "生物教材.pdf",
                "光合作用核心概念",
                "叶绿体吸收光能并完成能量转化。",
                0.96,
                "命中主题与关键词",
                List.of(PurposeType.TEXTBOOK_BASIS),
                List.of("光合作用", "叶绿体")
        );
    }

    private static TeachingIntentResponse validAiResponse() {
        return new TeachingIntentResponse(
                "wf-04",
                "intent-71",
                List.of("AI 目标一", "AI 目标二"),
                List.of("检索依据一", "检索依据二"),
                List.of("AI 提问", "AI 练习"),
                List.of("DOCX"),
                "请确认教学意图"
        );
    }

    private static TeachingIntent existingIntent(Long summaryId) {
        TeachingIntent intent = new TeachingIntent();
        intent.setId(901L);
        intent.setProjectId(PROJECT_ID);
        intent.setRequirementSummaryId(summaryId);
        intent.setGenerationGoal("既有目标");
        intent.setContentBasis("既有依据");
        intent.setTeachingApproach("既有策略");
        intent.setInteractionMode("既有互动");
        intent.setOutputTypes(List.of("PPT"));
        intent.setStatus(TeachingIntentStatus.DRAFT);
        return intent;
    }
}
