package com.auvdidao.a12teachingagent.requirement;

import com.auvdidao.a12teachingagent.domain.common.InputType;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementInputRepository;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputRequest;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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

    public RequirementInputResponse save(Long projectId, RequirementInputRequest request) {
        assertProjectExists(projectId);

        RequirementInput requirementInput = new RequirementInput();
        requirementInput.setProjectId(projectId);
        requirementInput.setGradeLevel(normalize(request.gradeLevel()));
        requirementInput.setSubject(normalize(request.subject()));
        requirementInput.setTopic(normalize(request.topic()));
        requirementInput.setLessonDuration(normalize(request.lessonDuration()));
        requirementInput.setTeachingGoals(normalize(request.teachingGoals()));
        requirementInput.setKeyPoints(normalize(request.keyPoints()));
        requirementInput.setDifficultPoints(normalize(request.difficultPoints()));
        requirementInput.setOutputTypes(serializeOutputTypes(request.outputTypes()));
        requirementInput.setRawRequirementText(normalize(request.rawRequirementText()));
        requirementInput.setContent(normalize(request.rawRequirementText()));
        requirementInput.setInputType(InputType.TEXT);

        return toResponse(requirementInputRepository.save(requirementInput));
    }

    public RequirementInputResponse findLatest(Long projectId) {
        assertProjectExists(projectId);

        return requirementInputRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .map(this::toResponse)
                .orElse(null);
    }

    private void assertProjectExists(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
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
                .map(this::normalize)
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

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
