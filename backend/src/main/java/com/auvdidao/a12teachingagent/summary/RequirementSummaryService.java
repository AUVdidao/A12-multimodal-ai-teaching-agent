package com.auvdidao.a12teachingagent.summary;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.DialogTurn;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryData;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
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
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final ProjectAccessService projectAccessService;
    private final AIWorkflowGateway aiWorkflowGateway;

    public RequirementSummaryService(
            ProjectRepository projectRepository,
            RequirementInputRepository requirementInputRepository,
            RequirementSummaryRepository requirementSummaryRepository,
            DialogMessageRepository dialogMessageRepository,
            ProjectAccessService projectAccessService,
            AIWorkflowGateway aiWorkflowGateway
    ) {
        this.projectRepository = projectRepository;
        this.requirementInputRepository = requirementInputRepository;
        this.requirementSummaryRepository = requirementSummaryRepository;
        this.dialogMessageRepository = dialogMessageRepository;
        this.projectAccessService = projectAccessService;
        this.aiWorkflowGateway = aiWorkflowGateway;
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

        GenerationMode generationMode = project.getGenerationMode() == null
                ? GenerationMode.STANDARD
                : project.getGenerationMode();
        List<DialogMessage> messages = dialogMessageRepository.findByProjectIdOrderByCreatedAtAscIdAsc(projectId);
        var aiResponse = aiWorkflowGateway.summarizeRequirement(new RequirementSummaryRequest(
                projectId,
                buildRawRequirement(requirement, project),
                toDialogTurns(messages),
                generationMode,
                toAiProjectContext(requirement, project, resolveStylePreference(messages))
        ));
        RequirementSummaryData aiSummary = requireValidAiSummary(aiResponse);
        boolean hasNarrativeInput = hasText(requirement.getRawRequirementText()) || hasText(requirement.getContent());

        RequirementSummary summary = new RequirementSummary();
        summary.setProjectId(projectId);
        summary.setSourceRequirementId(requirement.getId());
        summary.setGradeLevel(preferStructuredValue(requirement.getGradeLevel(), aiSummary.targetAudience(), hasNarrativeInput));
        summary.setSubject(preferStructuredValue(requirement.getSubject(), aiSummary.courseName(), hasNarrativeInput));
        summary.setTopic(preferStructuredValue(requirement.getTopic(), aiSummary.chapterTopic(), hasNarrativeInput));
        summary.setBaselineLevel(requirement.getBaselineLevel());
        summary.setLessonDuration(preferStructuredValue(
                requirement.getLessonDuration(),
                aiSummary.lessonDurationMinutes() + "分钟",
                hasNarrativeInput
        ));
        summary.setTeachingGoals(mergeText(requirement.getTeachingGoals(), aiSummary.teachingGoals()));
        summary.setKeyPoints(requirement.getKeyPoints());
        summary.setDifficultPoints(mergeText(requirement.getDifficultPoints(), aiSummary.keyDifficulties()));
        summary.setOutputTypes(resolveOutputTypes(requirement.getOutputTypes(), aiSummary.outputTypes(), hasNarrativeInput));
        summary.setStylePreference(firstNonBlank(
                requirement.getStylePreference(),
                firstNonBlank(resolveStylePreference(messages), aiSummary.coursewareStyle())
        ));
        summary.setInteractionType(firstNonBlank(requirement.getInteractionType(), aiSummary.interactionType()));
        summary.setGenerationMode(generationMode);
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
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        projectAccessService.requireAccess(project);
        return project;
    }

    private RequirementSummary requireSummary(Long projectId, Long summaryId) {
        RequirementSummary summary = requirementSummaryRepository.findById(summaryId)
                .orElseThrow(() -> new ResourceNotFoundException("Requirement summary not found: " + summaryId));
        if (!projectId.equals(summary.getProjectId())) {
            throw new ResourceNotFoundException("Requirement summary does not belong to project: " + projectId);
        }
        return summary;
    }

    private String resolveStylePreference(List<DialogMessage> messages) {
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

    private static RequirementSummaryData requireValidAiSummary(
            com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryResponse response
    ) {
        if (response == null || response.summary() == null) {
            throw new AiWorkflowUnavailableException("WF-02 returned no requirement summary");
        }
        RequirementSummaryData summary = response.summary();
        if (!hasText(summary.courseName())
                || !hasText(summary.chapterTopic())
                || !hasText(summary.targetAudience())
                || summary.lessonDurationMinutes() == null
                || summary.lessonDurationMinutes() <= 0
                || normalizeValues(summary.teachingGoals()).isEmpty()
                || normalizeValues(summary.keyDifficulties()).isEmpty()
                || normalizeOutputTypes(summary.outputTypes()).isEmpty()
                || !hasText(summary.coursewareStyle())
                || !hasText(summary.interactionType())) {
            throw new AiWorkflowUnavailableException("WF-02 returned an incomplete requirement summary");
        }
        return summary;
    }

    private static List<DialogTurn> toDialogTurns(List<DialogMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .filter(message -> message.getRole() != null && hasText(message.getContent()))
                .map(message -> new DialogTurn(message.getRole().name(), message.getContent().trim()))
                .toList();
    }

    private static RequirementSummaryData toAiProjectContext(
            RequirementInput requirement,
            Project project,
            String dialogStylePreference
    ) {
        return new RequirementSummaryData(
                firstNonBlank(requirement.getSubject(), project.getCourseName()),
                firstNonBlank(requirement.getTopic(), project.getChapterTopic()),
                firstNonBlank(requirement.getGradeLevel(), project.getTargetAudience()),
                resolveLessonDurationMinutes(requirement.getLessonDuration(), project.getLessonDurationMinutes()),
                textValues(requirement.getTeachingGoals()),
                textValues(requirement.getKeyPoints(), requirement.getDifficultPoints()),
                normalizeOutputTypes(requirement.getOutputTypes()),
                firstNonBlank(requirement.getStylePreference(), dialogStylePreference),
                trimToNull(requirement.getInteractionType())
        );
    }

    private static Integer resolveLessonDurationMinutes(String lessonDuration, Integer fallback) {
        String normalized = trimToNull(lessonDuration);
        if (normalized != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(normalized);
            if (matcher.find()) {
                try {
                    int minutes = Integer.parseInt(matcher.group(1));
                    if (minutes > 0) {
                        return minutes;
                    }
                } catch (NumberFormatException ignored) {
                    // Use the persisted project duration below.
                }
            }
        }
        return fallback != null && fallback > 0 ? fallback : null;
    }

    private static List<String> textValues(String... values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String text = trimToNull(value);
            if (text != null) {
                normalized.add(text);
            }
        }
        return List.copyOf(normalized);
    }

    private static String buildRawRequirement(RequirementInput requirement, Project project) {
        List<String> parts = new ArrayList<>();
        addPart(parts, requirement.getRawRequirementText());
        if (!sameText(requirement.getRawRequirementText(), requirement.getContent())) {
            addPart(parts, requirement.getContent());
        }
        addLabeledPart(parts, "课程", requirement.getSubject());
        addLabeledPart(parts, "课题", requirement.getTopic());
        addLabeledPart(parts, "对象", requirement.getGradeLevel());
        addLabeledPart(parts, "课时", requirement.getLessonDuration());
        addLabeledPart(parts, "教学目标", requirement.getTeachingGoals());
        addLabeledPart(parts, "教学重点", requirement.getKeyPoints());
        addLabeledPart(parts, "教学难点", requirement.getDifficultPoints());
        if (!requirement.getOutputTypes().isEmpty()) {
            parts.add("输出类型：" + String.join("、", requirement.getOutputTypes()));
        }
        if (parts.isEmpty()) {
            addLabeledPart(parts, "课程", project.getCourseName());
            addLabeledPart(parts, "课题", project.getChapterTopic());
        }
        if (parts.isEmpty()) {
            throw new BadRequestException("The teaching requirement has no usable content");
        }
        return String.join("\n", parts);
    }

    private static void addLabeledPart(List<String> parts, String label, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            parts.add(label + "：" + normalized);
        }
    }

    private static void addPart(List<String> parts, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            parts.add(normalized);
        }
    }

    private static boolean sameText(String first, String second) {
        String normalizedFirst = trimToNull(first);
        String normalizedSecond = trimToNull(second);
        return normalizedFirst != null && normalizedFirst.equals(normalizedSecond);
    }

    private static String preferStructuredValue(String structured, String aiValue, boolean hasNarrativeInput) {
        String normalized = trimToNull(structured);
        return normalized != null || !hasNarrativeInput ? normalized : trimToNull(aiValue);
    }

    private static List<String> resolveOutputTypes(
            List<String> structured,
            List<String> aiValues,
            boolean hasNarrativeInput
    ) {
        List<String> normalized = normalizeOutputTypes(structured);
        return !normalized.isEmpty() || !hasNarrativeInput ? normalized : normalizeOutputTypes(aiValues);
    }

    private static String mergeText(String structured, List<String> aiValues) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String normalizedStructured = trimToNull(structured);
        if (normalizedStructured != null) {
            values.add(normalizedStructured);
        }
        values.addAll(normalizeValues(aiValues));
        return values.isEmpty() ? null : String.join("；", values);
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

    private static List<String> normalizeValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                normalized.add(trimmed);
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
