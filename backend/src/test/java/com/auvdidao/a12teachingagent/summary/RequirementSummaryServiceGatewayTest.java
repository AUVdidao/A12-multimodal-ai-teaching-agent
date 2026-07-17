package com.auvdidao.a12teachingagent.summary;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.DialogTurn;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryData;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryResponse;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.domain.common.DialogRole;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.dialog.DialogMessage;
import com.auvdidao.a12teachingagent.domain.dialog.repository.DialogMessageRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementInputRepository;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
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
class RequirementSummaryServiceGatewayTest {

    private static final long PROJECT_ID = 42L;

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private RequirementInputRepository requirementInputRepository;
    @Mock
    private RequirementSummaryRepository summaryRepository;
    @Mock
    private DialogMessageRepository dialogRepository;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private AIWorkflowGateway aiWorkflowGateway;

    private RequirementSummaryService service;

    @BeforeEach
    void setUp() {
        service = new RequirementSummaryService(
                projectRepository,
                requirementInputRepository,
                summaryRepository,
                dialogRepository,
                projectAccessService,
                aiWorkflowGateway
        );
    }

    @Test
    void callsWf02AndPersistsValidatedAiFieldsWithoutOverwritingStructuredFacts() {
        Project project = project();
        RequirementInput requirement = requirement();
        DialogMessage dialog = new DialogMessage();
        dialog.setRole(DialogRole.TEACHER);
        dialog.setContent("请保持循序渐进");

        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(requirementInputRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(PROJECT_ID))
                .thenReturn(Optional.of(requirement));
        when(summaryRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(PROJECT_ID))
                .thenReturn(Optional.empty());
        when(dialogRepository.findByProjectIdOrderByCreatedAtAscIdAsc(PROJECT_ID)).thenReturn(List.of(dialog));
        when(aiWorkflowGateway.summarizeRequirement(any())).thenReturn(validAiResponse());
        when(summaryRepository.save(any(RequirementSummary.class))).thenAnswer(invocation -> {
            RequirementSummary saved = invocation.getArgument(0);
            saved.setId(501L);
            return saved;
        });

        var response = service.generate(PROJECT_ID);

        ArgumentCaptor<RequirementSummaryRequest> gatewayRequest = ArgumentCaptor.forClass(RequirementSummaryRequest.class);
        verify(aiWorkflowGateway).summarizeRequirement(gatewayRequest.capture());
        assertThat(gatewayRequest.getValue().projectId()).isEqualTo(PROJECT_ID);
        assertThat(gatewayRequest.getValue().rawRequirement()).contains("请生成一份探究课", "课题：光合作用");
        assertThat(gatewayRequest.getValue().dialogTurns())
                .containsExactly(new DialogTurn("TEACHER", "请保持循序渐进"));
        assertThat(gatewayRequest.getValue().generationMode()).isEqualTo(GenerationMode.QUALITY);

        ArgumentCaptor<RequirementSummary> savedSummary = ArgumentCaptor.forClass(RequirementSummary.class);
        verify(summaryRepository).save(savedSummary.capture());
        assertThat(savedSummary.getValue().getSubject()).isEqualTo("生物");
        assertThat(savedSummary.getValue().getTopic()).isEqualTo("光合作用");
        assertThat(savedSummary.getValue().getTeachingGoals()).isEqualTo("解释光合作用；AI 目标一；AI 目标二");
        assertThat(savedSummary.getValue().getDifficultPoints()).isEqualTo("能量转化；AI 难点");
        assertThat(savedSummary.getValue().getOutputTypes()).containsExactly("DOCX");
        assertThat(savedSummary.getValue().getStylePreference()).isEqualTo("AI 清晰风格");
        assertThat(savedSummary.getValue().getInteractionType()).isEqualTo("AI 探究互动");
        assertThat(savedSummary.getValue().getStatus()).isEqualTo(RequirementSummaryStatus.DRAFT);
        assertThat(response.id()).isEqualTo(501L);
        verify(projectAccessService).requireAccess(project);
    }

    @Test
    void repeatedGenerationForSameRequirementIsIdempotentAndSkipsGateway() {
        Project project = project();
        RequirementInput requirement = requirement();
        RequirementSummary existing = existingSummary(requirement.getId());
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(requirementInputRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(PROJECT_ID))
                .thenReturn(Optional.of(requirement));
        when(summaryRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(PROJECT_ID))
                .thenReturn(Optional.of(existing));

        var response = service.generate(PROJECT_ID);

        assertThat(response.id()).isEqualTo(existing.getId());
        verifyNoInteractions(aiWorkflowGateway, dialogRepository);
        verify(summaryRepository, never()).save(any());
    }

    @Test
    void rejectsIncompleteWf02OutputWithoutPersisting() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project()));
        when(requirementInputRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(PROJECT_ID))
                .thenReturn(Optional.of(requirement()));
        when(summaryRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(PROJECT_ID))
                .thenReturn(Optional.empty());
        when(dialogRepository.findByProjectIdOrderByCreatedAtAscIdAsc(PROJECT_ID)).thenReturn(List.of());
        when(aiWorkflowGateway.summarizeRequirement(any())).thenReturn(new RequirementSummaryResponse(
                "wf-02",
                new RequirementSummaryData("生物", "光合作用", "八年级", 45, List.of(), List.of(), List.of(), null, null),
                List.of(),
                null
        ));

        assertThatThrownBy(() -> service.generate(PROJECT_ID))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("WF-02");
        verify(summaryRepository, never()).save(any());
    }

    @Test
    void projectAccessFailureStopsGenerationBeforeProjectDataIsRead() {
        Project project = project();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        doThrow(new ForbiddenException("This project belongs to another teacher"))
                .when(projectAccessService).requireAccess(project);

        assertThatThrownBy(() -> service.generate(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(requirementInputRepository, summaryRepository, dialogRepository, aiWorkflowGateway);
    }

    private static Project project() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setCourseName("生物");
        project.setChapterTopic("光合作用");
        project.setTargetAudience("八年级");
        project.setLessonDurationMinutes(45);
        project.setGenerationMode(GenerationMode.QUALITY);
        return project;
    }

    private static RequirementInput requirement() {
        RequirementInput requirement = new RequirementInput();
        requirement.setId(101L);
        requirement.setProjectId(PROJECT_ID);
        requirement.setRawRequirementText("请生成一份探究课");
        requirement.setContent("请生成一份探究课");
        requirement.setGradeLevel("八年级");
        requirement.setSubject("生物");
        requirement.setTopic("光合作用");
        requirement.setBaselineLevel("已了解叶绿体");
        requirement.setLessonDuration("45分钟");
        requirement.setTeachingGoals("解释光合作用");
        requirement.setKeyPoints("反应条件");
        requirement.setDifficultPoints("能量转化");
        return requirement;
    }

    private static RequirementSummaryResponse validAiResponse() {
        return new RequirementSummaryResponse(
                "wf-02",
                new RequirementSummaryData(
                        "AI 生物课程",
                        "AI 光合作用课题",
                        "AI 八年级",
                        50,
                        List.of("AI 目标一", "AI 目标二"),
                        List.of("AI 难点"),
                        List.of("DOCX"),
                        "AI 清晰风格",
                        "AI 探究互动"
                ),
                List.of("假设"),
                "请确认"
        );
    }

    private static RequirementSummary existingSummary(Long sourceRequirementId) {
        RequirementSummary summary = new RequirementSummary();
        summary.setId(601L);
        summary.setProjectId(PROJECT_ID);
        summary.setSourceRequirementId(sourceRequirementId);
        summary.setSubject("生物");
        summary.setTopic("光合作用");
        summary.setOutputTypes(List.of("PPT"));
        summary.setGenerationMode(GenerationMode.QUALITY);
        summary.setStatus(RequirementSummaryStatus.DRAFT);
        return summary;
    }
}
