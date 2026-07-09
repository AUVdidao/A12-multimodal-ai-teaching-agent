package com.auvdidao.a12teachingagent.requirement;

import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.InputType;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementInputRepository;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputDtos.RequirementInputRequest;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputDtos.RequirementInputResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class RequirementInputService {

    private final ProjectRepository projectRepository;
    private final RequirementInputRepository requirementInputRepository;

    public RequirementInputService(
            ProjectRepository projectRepository,
            RequirementInputRepository requirementInputRepository
    ) {
        this.projectRepository = projectRepository;
        this.requirementInputRepository = requirementInputRepository;
    }

    @Transactional
    public RequirementInputResponse save(Long projectId, RequirementInputRequest request) {
        assertProjectExists(projectId);

        RequirementInput requirementInput = new RequirementInput();
        requirementInput.setProjectId(projectId);
        requirementInput.setGradeLevel(trimToEmpty(request.gradeLevel()));
        requirementInput.setSubject(trimToEmpty(request.subject()));
        requirementInput.setTopic(trimToEmpty(request.topic()));
        requirementInput.setLessonDuration(trimToEmpty(request.lessonDuration()));
        requirementInput.setTeachingGoals(trimToEmpty(request.teachingGoals()));
        requirementInput.setKeyPoints(trimToEmpty(request.keyPoints()));
        requirementInput.setDifficultPoints(trimToEmpty(request.difficultPoints()));
        requirementInput.setOutputTypes(serializeOutputTypes(request.outputTypes()));
        requirementInput.setRawRequirementText(trimToEmpty(request.rawRequirementText()));
        requirementInput.setContent(trimToEmpty(request.rawRequirementText()));
        requirementInput.setInputType(InputType.TEXT);

        return toResponse(requirementInputRepository.save(requirementInput));
    }

    @Transactional(readOnly = true)
    public RequirementInputResponse latest(Long projectId) {
        assertProjectExists(projectId);

        return requirementInputRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .map(this::toResponse)
                .orElse(null);
    }

    private void assertProjectExists(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
    }

    private RequirementInputResponse toResponse(RequirementInput requirementInput) {
        return new RequirementInputResponse(
                requirementInput.getId(),
                requirementInput.getProjectId(),
                requirementInput.getGradeLevel(),
                requirementInput.getSubject(),
                requirementInput.getTopic(),
                requirementInput.getLessonDuration(),
                requirementInput.getTeachingGoals(),
                requirementInput.getKeyPoints(),
                requirementInput.getDifficultPoints(),
                parseOutputTypes(requirementInput.getOutputTypes()),
                requirementInput.getRawRequirementText(),
                requirementInput.getCreatedAt(),
                requirementInput.getUpdatedAt()
        );
    }

    private String serializeOutputTypes(List<String> outputTypes) {
        if (outputTypes == null || outputTypes.isEmpty()) {
            return "";
        }

        return outputTypes.stream()
                .map(this::trimToEmpty)
                .filter(value -> !value.isEmpty())
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private List<String> parseOutputTypes(String outputTypes) {
        if (outputTypes == null || outputTypes.isBlank()) {
            return List.of();
        }

        return Arrays.stream(outputTypes.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
