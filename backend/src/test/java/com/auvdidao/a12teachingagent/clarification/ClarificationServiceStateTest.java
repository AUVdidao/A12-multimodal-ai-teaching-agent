package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationQuestion;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationResponse;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.requirement.RequirementInputService;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputDtos.RequirementInputResponse;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ClarificationServiceStateTest {

    @Test
    void keepsTheSinglePendingQuestionWhenItsTargetIsStillMissing() {
        AIWorkflowGateway gateway = mock(AIWorkflowGateway.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        ClarificationQuestionRepository questions = mock(ClarificationQuestionRepository.class);
        RequirementInputService requirements = mock(RequirementInputService.class);
        ClarificationQuestionEntity pending = question(1L, "q-existing", "gradeLevel");
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project(1L)));
        when(questions.findFirstByProjectIdAndStatusOrderByCreatedAtDescIdDesc(
                1L, ClarificationQuestionStatus.PENDING)).thenReturn(Optional.of(pending));

        ClarificationService service = service(gateway, projects, access, questions, requirements);
        ClarificationResult result = service.questions(1L, request(null, null, null, null, null, null, null, null, null, List.of()));

        assertThat(result.complete()).isFalse();
        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).questionId()).isEqualTo("q-existing");
        assertThat(result.questions().get(0).targetField()).isEqualTo("gradeLevel");
        verifyNoInteractions(gateway);
    }

    @Test
    void marksFilledPendingQuestionObsoleteAndCreatesTheNextActualQuestion() {
        AIWorkflowGateway gateway = mock(AIWorkflowGateway.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        ClarificationQuestionRepository questions = mock(ClarificationQuestionRepository.class);
        RequirementInputService requirements = mock(RequirementInputService.class);
        ClarificationQuestionEntity pending = question(1L, "q-old", "outputTypes");
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project(1L)));
        when(questions.findFirstByProjectIdAndStatusOrderByCreatedAtDescIdDesc(
                1L, ClarificationQuestionStatus.PENDING)).thenReturn(Optional.of(pending));
        when(gateway.clarifyRequirement(any(ClarificationRequest.class))).thenReturn(
                new ClarificationResponse(
                        "WF-01",
                        List.of("gradeLevel"),
                        List.of(new ClarificationQuestion("gradeLevel", "请补充授课年级")),
                        Map.of(),
                        "ASK"));

        ClarificationService service = service(gateway, projects, access, questions, requirements);
        ClarificationResult result = service.questions(1L, request(null, null, null, null, null, null, null, null, null,
                List.of("PPT")));

        assertThat(pending.getStatus()).isEqualTo(ClarificationQuestionStatus.OBSOLETE);
        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).targetField()).isEqualTo("gradeLevel");
        assertThat(result.questions().get(0).targetField()).isNotEqualTo("outputTypes");
        verify(questions, times(2)).save(any(ClarificationQuestionEntity.class));
    }

    @Test
    void marksPendingQuestionObsoleteWhenAllFieldsAreComplete() {
        AIWorkflowGateway gateway = mock(AIWorkflowGateway.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        ClarificationQuestionRepository questions = mock(ClarificationQuestionRepository.class);
        RequirementInputService requirements = mock(RequirementInputService.class);
        ClarificationQuestionEntity pending = question(1L, "q-old", "outputTypes");
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project(1L)));
        when(questions.findFirstByProjectIdAndStatusOrderByCreatedAtDescIdDesc(
                1L, ClarificationQuestionStatus.PENDING)).thenReturn(Optional.of(pending));

        ClarificationService service = service(gateway, projects, access, questions, requirements);
        ClarificationResult result = service.questions(1L, request(
                "Grade 8", "Biology", "Photosynthesis", "45 minutes", "Explain photosynthesis",
                "Basic biology", "Carbon fixation", "Clear", "Group discussion",
                List.of("PPT")));

        assertThat(result.complete()).isTrue();
        assertThat(result.questions()).isEmpty();
        assertThat(pending.getStatus()).isEqualTo(ClarificationQuestionStatus.OBSOLETE);
        verify(questions).save(pending);
        verifyNoInteractions(gateway);
    }

    @Test
    void answersAreAppliedOnceAndSecondAnswerDoesNotUpdateRequirementsAgain() {
        AIWorkflowGateway gateway = mock(AIWorkflowGateway.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        ClarificationQuestionRepository questions = mock(ClarificationQuestionRepository.class);
        RequirementInputService requirements = mock(RequirementInputService.class);
        ClarificationQuestionEntity pending = question(1L, "q-output", "outputTypes");
        when(projects.findById(1L)).thenReturn(Optional.of(project(1L)));
        when(questions.findByQuestionIdForUpdate("q-output")).thenReturn(Optional.of(pending));
        when(requirements.applyClarificationAnswer(1L, "outputTypes", "课件、教案、学案、课堂练习"))
                .thenReturn(mock(RequirementInputResponse.class));

        ClarificationService service = service(gateway, projects, access, questions, requirements);
        service.answer(1L, "q-output", "课件、教案、学案、课堂练习");

        assertThat(pending.getStatus()).isEqualTo(ClarificationQuestionStatus.ANSWERED);
        verify(requirements).applyClarificationAnswer(1L, "outputTypes", "课件、教案、学案、课堂练习");
        assertThatThrownBy(() -> service.answer(1L, "q-output", "再次回答"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("already been answered");
        verify(requirements, times(1)).applyClarificationAnswer(anyLong(), eq("outputTypes"), anyString());
    }

    @Test
    void answerFromAnotherProjectIsRejectedWithoutUpdatingRequirements() {
        AIWorkflowGateway gateway = mock(AIWorkflowGateway.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        ClarificationQuestionRepository questions = mock(ClarificationQuestionRepository.class);
        RequirementInputService requirements = mock(RequirementInputService.class);
        ClarificationQuestionEntity pending = question(1L, "q-project-1", "outputTypes");
        when(projects.findById(2L)).thenReturn(Optional.of(project(2L)));
        when(questions.findByQuestionIdForUpdate("q-project-1")).thenReturn(Optional.of(pending));

        ClarificationService service = service(gateway, projects, access, questions, requirements);
        assertThatThrownBy(() -> service.answer(2L, "q-project-1", "PPT"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("does not belong to this project");
        assertThat(pending.getStatus()).isEqualTo(ClarificationQuestionStatus.PENDING);
        verifyNoInteractions(requirements);
    }

    @Test
    void questionIdHasAUniqueDatabaseConstraint() {
        Table table = ClarificationQuestionEntity.class.getAnnotation(Table.class);

        assertThat(table).isNotNull();
        assertThat(List.of(table.uniqueConstraints()))
                .anyMatch(constraint -> constraint.name().equals("uk_clarification_question_question_id")
                        && List.of(constraint.columnNames()).contains("question_id"));
    }

    private static ClarificationService service(
            AIWorkflowGateway gateway,
            ProjectRepository projects,
            ProjectAccessService access,
            ClarificationQuestionRepository questions,
            RequirementInputService requirements
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ClarificationQuestionRepository> questionProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RequirementInputService> requirementProvider = mock(ObjectProvider.class);
        when(questionProvider.getIfAvailable()).thenReturn(questions);
        when(requirementProvider.getIfAvailable()).thenReturn(requirements);
        return new ClarificationService(gateway, projects, access, questionProvider, requirementProvider);
    }

    private static Project project(Long id) {
        Project project = new Project();
        project.setId(id);
        project.setGenerationMode(GenerationMode.STANDARD);
        return project;
    }

    private static ClarificationQuestionEntity question(Long projectId, String questionId, String targetField) {
        ClarificationQuestionEntity question = new ClarificationQuestionEntity();
        question.setProjectId(projectId);
        question.setQuestionId(questionId);
        question.setTargetField(targetField);
        question.setQuestion("请补充" + targetField);
        question.setStatus(ClarificationQuestionStatus.PENDING);
        return question;
    }

    private static ClarificationCheckRequest request(
            String gradeLevel,
            String subject,
            String topic,
            String lessonDuration,
            String teachingGoals,
            String baselineLevel,
            String difficultPoints,
            String stylePreference,
            String interactionType,
            List<String> outputTypes
    ) {
        return new ClarificationCheckRequest(
                gradeLevel,
                subject,
                topic,
                lessonDuration,
                teachingGoals,
                baselineLevel,
                null,
                difficultPoints,
                stylePreference,
                interactionType,
                outputTypes,
                "Photosynthesis lesson requirements"
        );
    }
}
