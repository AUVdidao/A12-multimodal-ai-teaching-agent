package com.auvdidao.a12teachingagent.summary;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.DialogRole;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.dialog.DialogMessage;
import com.auvdidao.a12teachingagent.domain.dialog.repository.DialogMessageRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementInputRepository;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.summary.dto.RequirementSummaryDtos.RequirementSummaryResponse;
import com.auvdidao.a12teachingagent.summary.dto.RequirementSummaryDtos.RequirementSummaryUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class RequirementSummaryService {

    private static final List<String> STYLE_KEYWORDS = List.of(
            "简洁", "活泼", "学术", "科技", "清新", "卡通", "正式", "互动"
    );

    private final ProjectRepository projectRepository;
    private final RequirementInputRepository requirementInputRepository;
    private final RequirementSummaryRepository requirementSummaryRepository;
    private final DialogMessageRepository dialogMessageRepository;

    public RequirementSummaryService(
            ProjectRepository projectRepository,
            RequirementInputRepository requirementInputRepository,
            RequirementSummaryRepository requirementSummaryRepository,
            DialogMessageRepository dialogMessageRepository
    ) {
        this.projectRepository = projectRepository;
        this.requirementInputRepository = requirementInputRepository;
        this.requirementSummaryRepository = requirementSummaryRepository;
        this.dialogMessageRepository = dialogMessageRepository;
    }

    @Transactional
    public RequirementSummaryResponse generate(Long projectId) {
        Project project = requireProject(projectId);
        RequirementInput requirement = requirementInputRepository
                .findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .orElseThrow(() -> new ConflictException("A teaching requirement is required before summary generation"));

        RequirementSummary existing = requirementSummaryRepository
                .findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .filter(summary -> requirement.getId().equals(summary.getSourceRequirementId()))
                .orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }

        RequirementSummary summary = new RequirementSummary();
        summary.setProjectId(projectId);
        summary.setSourceRequirementId(requirement.getId());
        summary.setGradeLevel(requirement.getGradeLevel());
        summary.setSubject(requirement.getSubject());
        summary.setTopic(requirement.getTopic());
        summary.setBaselineLevel(requirement.getBaselineLevel());
        summary.setLessonDuration(requirement.getLessonDuration());
        summary.setTeachingGoals(requirement.getTeachingGoals());
        summary.setKeyPoints(requirement.getKeyPoints());
        summary.setDifficultPoints(requirement.getDifficultPoints());
        summary.setOutputTypes(requirement.getOutputTypes());
        summary.setStylePreference(firstNonBlank(requirement.getStylePreference(), resolveStylePreference(projectId)));
        summary.setInteractionType(requirement.getInteractionType());
        summary.setGenerationMode(project.getGenerationMode() == null ? GenerationMode.STANDARD : project.getGenerationMode());
        summary.setStatus(RequirementSummaryStatus.DRAFT);

        return toResponse(requirementSummaryRepository.save(summary));
    }

    @Transactional(readOnly = true)
    public RequirementSummaryResponse latest(Long projectId) {
        requireProject(projectId);
        return requirementSummaryRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public RequirementSummaryResponse update(
            Long projectId,
            Long summaryId,
            RequirementSummaryUpdateRequest request
    ) {
        requireProject(projectId);
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        RequirementSummary summary = requireSummary(projectId, summaryId);
        if (summary.getStatus() == RequirementSummaryStatus.CONFIRMED) {
            throw new ConflictException("A confirmed requirement summary cannot be modified");
        }

        summary.setGradeLevel(trimToNull(request.gradeLevel()));
        summary.setSubject(trimToNull(request.subject()));
        summary.setTopic(trimToNull(request.topic()));
        if (request.baselineLevel() != null) {
            summary.setBaselineLevel(trimToNull(request.baselineLevel()));
        }
        summary.setLessonDuration(trimToNull(request.lessonDuration()));
        summary.setTeachingGoals(trimToNull(request.teachingGoals()));
        summary.setKeyPoints(trimToNull(request.keyPoints()));
        summary.setDifficultPoints(trimToNull(request.difficultPoints()));
        summary.setOutputTypes(normalizeOutputTypes(request.outputTypes()));
        summary.setStylePreference(trimToNull(request.stylePreference()));
        if (request.interactionType() != null) {
            summary.setInteractionType(trimToNull(request.interactionType()));
        }

        return toResponse(requirementSummaryRepository.save(summary));
    }

    @Transactional
    public RequirementSummaryResponse confirm(Long projectId, Long summaryId) {
        Project project = requireProject(projectId);
        RequirementSummary summary = requireSummary(projectId, summaryId);
        if (summary.getStatus() == RequirementSummaryStatus.CONFIRMED) {
            return toResponse(summary);
        }

        validateComplete(summary);
        summary.setStatus(RequirementSummaryStatus.CONFIRMED);
        summary.setConfirmedAt(LocalDateTime.now());
        project.setStatus(ProjectStatus.REQUIREMENT_CONFIRMED);
        projectRepository.save(project);

        return toResponse(requirementSummaryRepository.save(summary));
    }

    private Project requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BadRequestException("projectId must be greater than 0");
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    private RequirementSummary requireSummary(Long projectId, Long summaryId) {
        RequirementSummary summary = requirementSummaryRepository.findById(summaryId)
                .orElseThrow(() -> new ResourceNotFoundException("Requirement summary not found: " + summaryId));
        if (!projectId.equals(summary.getProjectId())) {
            throw new ResourceNotFoundException("Requirement summary does not belong to project: " + projectId);
        }
        return summary;
    }

    private String resolveStylePreference(Long projectId) {
        List<DialogMessage> messages = dialogMessageRepository.findByProjectIdOrderByCreatedAtAscIdAsc(projectId);
        for (int index = messages.size() - 1; index >= 0; index--) {
            DialogMessage message = messages.get(index);
            if (message.getRole() != DialogRole.TEACHER || !hasText(message.getContent())) {
                continue;
            }
            for (String keyword : STYLE_KEYWORDS) {
                if (message.getContent().contains(keyword)) {
                    return keyword + "风格";
                }
            }
        }
        return null;
    }

    private static void validateComplete(RequirementSummary summary) {
        if (!hasText(summary.getGradeLevel())
                || !hasText(summary.getSubject())
                || !hasText(summary.getTopic())
                || !hasText(summary.getLessonDuration())
                || !hasText(summary.getTeachingGoals())
                || summary.getOutputTypes().isEmpty()) {
            throw new BadRequestException("The requirement summary is incomplete and cannot be confirmed");
        }
    }

    private RequirementSummaryResponse toResponse(RequirementSummary summary) {
        return new RequirementSummaryResponse(
                summary.getId(),
                summary.getProjectId(),
                summary.getSourceRequirementId(),
                summary.getGradeLevel(),
                summary.getSubject(),
                summary.getTopic(),
                summary.getBaselineLevel(),
                summary.getLessonDuration(),
                summary.getTeachingGoals(),
                summary.getKeyPoints(),
                summary.getDifficultPoints(),
                summary.getOutputTypes(),
                summary.getStylePreference(),
                summary.getInteractionType(),
                normalizeMode(summary.getGenerationMode()),
                summary.getStatus(),
                summary.getCreatedAt(),
                summary.getUpdatedAt(),
                summary.getConfirmedAt()
        );
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

    private static String normalizeMode(GenerationMode mode) {
        if (mode == null) {
            return "STANDARD";
        }
        return mode == GenerationMode.HIGH_QUALITY ? "QUALITY" : mode.name();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        String normalized = trimToNull(preferred);
        return normalized == null ? fallback : normalized;
    }
}
