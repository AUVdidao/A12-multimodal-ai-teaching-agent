package com.auvdidao.a12teachingagent.requirement;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.clarification.ClarificationField;
import com.auvdidao.a12teachingagent.domain.common.InputType;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementInputRepository;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputDtos.RequirementInputRequest;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputDtos.RequirementInputResponse;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

@Service
public class RequirementInputService {

    private final ProjectRepository projectRepository;
    private final RequirementInputRepository requirementInputRepository;
    private final ProjectAccessService projectAccessService;

    public RequirementInputService(
            ProjectRepository projectRepository,
            RequirementInputRepository requirementInputRepository,
            ProjectAccessService projectAccessService
    ) {
        this.projectRepository = projectRepository;
        this.requirementInputRepository = requirementInputRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional
    public RequirementInputResponse save(Long projectId, RequirementInputRequest request) {
        requireProject(projectId);
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }

        RequirementInput requirement = new RequirementInput();
        requirement.setProjectId(projectId);
        requirement.setGradeLevel(trimToNull(request.gradeLevel()));
        requirement.setSubject(trimToNull(request.subject()));
        requirement.setTopic(trimToNull(request.topic()));
        requirement.setBaselineLevel(trimToNull(request.baselineLevel()));
        requirement.setLessonDuration(trimToNull(request.lessonDuration()));
        requirement.setTeachingGoals(trimToNull(request.teachingGoals()));
        requirement.setKeyPoints(trimToNull(request.keyPoints()));
        requirement.setDifficultPoints(trimToNull(request.difficultPoints()));
        requirement.setStylePreference(trimToNull(request.stylePreference()));
        requirement.setInteractionType(trimToNull(request.interactionType()));
        requirement.setOutputTypes(normalizeOutputTypes(request.outputTypes()));
        requirement.setRawRequirementText(trimToNull(request.rawRequirementText()));
        requirement.setContent(requirement.getRawRequirementText());
        requirement.setInputType(InputType.TEXT);

        return toResponse(requirementInputRepository.save(requirement));
    }

    @Transactional(readOnly = true)
    public RequirementInputResponse latest(Long projectId) {
        requireProject(projectId);
        return requirementInputRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public RequirementInputResponse applyClarificationAnswer(
            Long projectId,
            String targetField,
            String answer
    ) {
        requireProject(projectId);
        ClarificationField field = ClarificationField.fromCode(trimToNull(targetField))
                .orElseThrow(() -> new BadRequestException(
                        "Unsupported clarification targetField: " + targetField
                ));
        String normalizedAnswer = trimToNull(answer);
        if (normalizedAnswer == null) {
            throw new BadRequestException("Clarification answer is required");
        }

        RequirementInput latest = requirementInputRepository
                .findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Requirement input not found for project: " + projectId
                ));
        RequirementInput updated = copyOf(latest);
        switch (field) {
            case GRADE_LEVEL -> updated.setGradeLevel(normalizedAnswer);
            case TOPIC -> updated.setTopic(normalizedAnswer);
            case LESSON_DURATION -> updated.setLessonDuration(normalizedAnswer);
            case TEACHING_GOALS -> updated.setTeachingGoals(normalizedAnswer);
            case BASELINE_LEVEL -> updated.setBaselineLevel(normalizedAnswer);
            case DIFFICULT_POINTS -> updated.setDifficultPoints(normalizedAnswer);
            case STYLE_PREFERENCE -> updated.setStylePreference(normalizedAnswer);
            case INTERACTION_TYPE -> updated.setInteractionType(normalizedAnswer);
            case OUTPUT_TYPES -> updated.setOutputTypes(parseOutputTypes(normalizedAnswer));
        }
        return toResponse(requirementInputRepository.save(updated));
    }

    private void requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BadRequestException("projectId must be greater than 0");
        }
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
        projectAccessService.requireAccess(projectId);
    }

    private RequirementInputResponse toResponse(RequirementInput requirement) {
        return new RequirementInputResponse(
                requirement.getId(),
                requirement.getProjectId(),
                requirement.getGradeLevel(),
                requirement.getSubject(),
                requirement.getTopic(),
                requirement.getBaselineLevel(),
                requirement.getLessonDuration(),
                requirement.getTeachingGoals(),
                requirement.getKeyPoints(),
                requirement.getDifficultPoints(),
                requirement.getStylePreference(),
                requirement.getInteractionType(),
                requirement.getOutputTypes(),
                requirement.getRawRequirementText(),
                requirement.getCreatedAt(),
                requirement.getUpdatedAt()
        );
    }

    private static RequirementInput copyOf(RequirementInput source) {
        RequirementInput target = new RequirementInput();
        target.setProjectId(source.getProjectId());
        target.setGradeLevel(source.getGradeLevel());
        target.setSubject(source.getSubject());
        target.setTopic(source.getTopic());
        target.setBaselineLevel(source.getBaselineLevel());
        target.setLessonDuration(source.getLessonDuration());
        target.setTeachingGoals(source.getTeachingGoals());
        target.setKeyPoints(source.getKeyPoints());
        target.setDifficultPoints(source.getDifficultPoints());
        target.setStylePreference(source.getStylePreference());
        target.setInteractionType(source.getInteractionType());
        target.setOutputTypes(source.getOutputTypes());
        target.setRawRequirementText(source.getRawRequirementText());
        target.setContent(source.getContent());
        target.setInputType(source.getInputType());
        return target;
    }

    private static List<String> parseOutputTypes(String answer) {
        return normalizeOutputTypes(List.of(answer.split("[,，、;；/\\n]+")));
    }

    private static List<String> normalizeOutputTypes(List<String> outputTypes) {
        if (outputTypes == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String outputType : outputTypes) {
            String value = trimToNull(outputType);
            if (value != null) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
