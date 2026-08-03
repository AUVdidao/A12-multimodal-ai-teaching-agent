package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationQuestion;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryData;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.requirement.RequirementInputService;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputDtos.RequirementInputResponse;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.time.LocalDateTime;

@Service
public class ClarificationService {

    private static final Pattern GRADE_PATTERN = Pattern.compile(
            "(一年级|二年级|三年级|四年级|五年级|六年级|七年级|八年级|九年级|高一|高二|高三|初一|初二|初三|小学|初中|高中|大学)"
    );
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+\\s*分钟|\\d+\\s*课时|一课时|两课时|半课时)");
    private static final List<String> KNOWN_SUBJECTS = List.of(
            "语文", "数学", "英语", "科学", "物理", "化学", "生物", "历史", "地理", "政治", "信息技术", "人工智能"
    );
    private static final List<String> OUTPUT_TYPE_KEYWORDS = List.of(
            "ppt", "幻灯片", "课件", "教案", "教学设计", "练习题", "互动内容"
    );
    private static final List<String> NEGATION_KEYWORDS = List.of("不要", "不需要", "无需", "别", "禁止");

    private final AIWorkflowGateway aiWorkflowGateway;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;
    private final ClarificationQuestionRepository questionRepository;
    private final RequirementInputService requirementInputService;
    private final ClarificationQuestionTransactionService questionTransactionService;

    public ClarificationService(
            AIWorkflowGateway aiWorkflowGateway,
            ProjectRepository projectRepository,
            ProjectAccessService projectAccessService
    ) {
        this(aiWorkflowGateway, projectRepository, projectAccessService, null, null, null);
    }

    public ClarificationService(
            AIWorkflowGateway aiWorkflowGateway,
            ProjectRepository projectRepository,
            ProjectAccessService projectAccessService,
            ObjectProvider<ClarificationQuestionRepository> questionRepositoryProvider,
            ObjectProvider<RequirementInputService> requirementInputServiceProvider
    ) {
        this(aiWorkflowGateway, projectRepository, projectAccessService,
                questionRepositoryProvider, requirementInputServiceProvider, null);
    }

    @Autowired
    public ClarificationService(
            AIWorkflowGateway aiWorkflowGateway,
            ProjectRepository projectRepository,
            ProjectAccessService projectAccessService,
            ObjectProvider<ClarificationQuestionRepository> questionRepositoryProvider,
            ObjectProvider<RequirementInputService> requirementInputServiceProvider,
            ObjectProvider<ClarificationQuestionTransactionService> questionTransactionServiceProvider
    ) {
        this.aiWorkflowGateway = aiWorkflowGateway;
        this.projectRepository = projectRepository;
        this.projectAccessService = projectAccessService;
        this.questionRepository = questionRepositoryProvider.getIfAvailable();
        this.requirementInputService = requirementInputServiceProvider.getIfAvailable();
        this.questionTransactionService = questionTransactionServiceProvider == null
                ? null : questionTransactionServiceProvider.getIfAvailable();
    }

    @Transactional(readOnly = true)
    public ClarificationResult check(Long projectId, ClarificationCheckRequest request) {
        Project project = requireProject(projectId);
        Evaluation evaluation = evaluate(project, request);
        return new ClarificationResult(evaluation.complete(), evaluation.missingFields(), List.of());
    }

    public ClarificationResult questions(Long projectId, ClarificationCheckRequest request) {
        Project project = questionTransactionService == null
                ? requireProjectForUpdate(projectId)
                : requireProject(projectId);
        Evaluation evaluation = evaluate(project, request);

        if (questionRepository != null) {
            if (questionTransactionService != null) {
                List<String> missingCodes = evaluation.missingFields().stream()
                        .map(MissingField::field)
                        .toList();
                ClarificationQuestionTransactionService.ClarificationQuestionSnapshot pending =
                        questionTransactionService.findValidPendingOrObsolete(projectId, missingCodes);
                if (pending != null) {
                    return new ClarificationResult(
                            false,
                            evaluation.missingFields(),
                            List.of(new ClarificationQuestion(
                                    pending.questionId(), pending.targetField(), pending.question()))
                    );
                }
            } else {
                ClarificationQuestionEntity pending = questionRepository
                        .findFirstByProjectIdAndStatusOrderByCreatedAtDescIdDesc(
                                projectId, ClarificationQuestionStatus.PENDING)
                        .orElse(null);
                if (pending != null) {
                    boolean targetStillMissing = evaluation.missingFields().stream()
                            .map(MissingField::field)
                            .anyMatch(field -> java.util.Objects.equals(field, pending.getTargetField()));
                    if (targetStillMissing) {
                        return new ClarificationResult(
                                false,
                                evaluation.missingFields(),
                                List.of(new ClarificationQuestion(
                                        pending.getQuestionId(), pending.getTargetField(), pending.getQuestion()))
                        );
                    }
                    // Existing H2 file databases may still have a CHECK constraint
                    // that only permits PENDING/ANSWERED. A stale pending question
                    // is no longer answerable, so remove it instead of persisting
                    // OBSOLETE into that legacy schema.
                    questionRepository.delete(pending);
                }
            }
        }

        if (evaluation.complete()) {
            return new ClarificationResult(true, List.of(), List.of());
        }

        List<String> missingCodes = evaluation.missingFields().stream()
                .map(MissingField::field)
                .toList();
        ClarificationResponse response = aiWorkflowGateway.clarifyRequirement(new ClarificationRequest(
                projectId,
                normalizedRawRequirement(request),
                evaluation.knownFields(),
                project.getGenerationMode() == null ? GenerationMode.STANDARD : project.getGenerationMode(),
                missingCodes,
                projectContext(project, request)
        ));

        List<ClarificationQuestion> questions = adaptQuestions(missingCodes, response);
        if (questionRepository == null) {
            return new ClarificationResult(false, evaluation.missingFields(), questions);
        }

        ClarificationQuestion first = questions.get(0);
        if (questionTransactionService != null) {
            ClarificationQuestionTransactionService.ClarificationQuestionSnapshot saved =
                    questionTransactionService.saveIfAbsent(projectId, first.targetField(), first.question());
            return new ClarificationResult(
                    false,
                    evaluation.missingFields(),
                    List.of(new ClarificationQuestion(
                            saved.questionId(), saved.targetField(), saved.question()))
            );
        }

        ClarificationQuestionEntity entity = new ClarificationQuestionEntity();
        entity.setQuestionId(UUID.randomUUID().toString());
        entity.setProjectId(projectId);
        entity.setTargetField(first.targetField());
        entity.setQuestion(first.question());
        entity.setStatus(ClarificationQuestionStatus.PENDING);
        questionRepository.save(entity);
        return new ClarificationResult(
                false,
                evaluation.missingFields(),
                List.of(new ClarificationQuestion(entity.getQuestionId(), entity.getTargetField(), entity.getQuestion()))
        );
    }

    @Transactional
    public RequirementInputResponse answer(Long projectId, String questionId, String answer) {
        requireProject(projectId);
        if (!hasText(questionId) || !hasText(answer)) {
            throw new BadRequestException("questionId and answer are required");
        }
        if (questionRepository == null || requirementInputService == null) {
            throw new BadRequestException("Clarification question persistence is unavailable");
        }
        ClarificationQuestionEntity entity = questionRepository.findByQuestionIdForUpdate(questionId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Clarification question not found"));
        if (!projectId.equals(entity.getProjectId())) {
            throw new BadRequestException("Clarification question does not belong to this project");
        }
        if (entity.getStatus() != ClarificationQuestionStatus.PENDING) {
            throw new BadRequestException("Clarification question has already been answered");
        }
        String targetField = entity.getTargetField();
        if (ClarificationField.fromCode(targetField).isEmpty()) {
            throw new BadRequestException("Invalid clarification targetField");
        }
        RequirementInputResponse updated = requirementInputService.applyClarificationAnswer(
                projectId, targetField, answer.trim());
        entity.setStatus(ClarificationQuestionStatus.ANSWERED);
        entity.setAnsweredAt(LocalDateTime.now());
        questionRepository.save(entity);
        return updated;
    }

    private Evaluation evaluate(Project project, ClarificationCheckRequest request) {
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }

        String rawText = valueOrEmpty(request.rawRequirementText());
        List<MissingField> missingFields = new ArrayList<>();
        List<String> knownFields = new ArrayList<>();

        evaluateField(missingFields, knownFields, ClarificationField.GRADE_LEVEL,
                hasText(request.gradeLevel())
                        || hasText(project.getTargetAudience())
                        || GRADE_PATTERN.matcher(rawText).find());
        evaluateField(missingFields, knownFields, ClarificationField.TOPIC,
                hasText(request.topic())
                        || hasText(project.getChapterTopic())
                        || looksLikeTopicInRawText(rawText));
        evaluateField(missingFields, knownFields, ClarificationField.LESSON_DURATION,
                hasText(request.lessonDuration())
                        || (project.getLessonDurationMinutes() != null && project.getLessonDurationMinutes() > 0)
                        || DURATION_PATTERN.matcher(rawText).find());
        evaluateField(missingFields, knownFields, ClarificationField.TEACHING_GOALS,
                hasText(request.teachingGoals()));
        evaluateField(missingFields, knownFields, ClarificationField.BASELINE_LEVEL,
                hasText(request.baselineLevel()));
        evaluateField(missingFields, knownFields, ClarificationField.DIFFICULT_POINTS,
                hasText(request.difficultPoints()));
        evaluateField(missingFields, knownFields, ClarificationField.STYLE_PREFERENCE,
                hasText(request.stylePreference()));
        evaluateField(missingFields, knownFields, ClarificationField.INTERACTION_TYPE,
                hasText(request.interactionType()));
        evaluateField(missingFields, knownFields, ClarificationField.OUTPUT_TYPES,
                hasOutputTypes(request.outputTypes()) || containsPositiveOutputType(rawText));

        addOptionalKnownField(knownFields, "keyPoints", request.keyPoints());
        addOptionalKnownField(knownFields, "subject", request.subject());
        return new Evaluation(missingFields.isEmpty(), List.copyOf(missingFields), List.copyOf(knownFields));
    }

    private static void evaluateField(
            List<MissingField> missingFields,
            List<String> knownFields,
            ClarificationField field,
            boolean present
    ) {
        if (present) {
            knownFields.add(field.code());
        } else {
            missingFields.add(field.toMissingField());
        }
    }

    private static List<ClarificationQuestion> adaptQuestions(
            List<String> missingCodes,
            ClarificationResponse response
    ) {
        if (response == null || response.questions() == null) {
            throw new AiWorkflowUnavailableException("AI workflow returned an incomplete clarification response");
        }

        List<ClarificationQuestion> questions = new ArrayList<>();
        for (ClarificationQuestion question : response.questions()) {
            if (question == null
                    || !hasText(question.targetField())
                    || !hasText(question.question())) {
                throw new AiWorkflowUnavailableException(
                        "AI workflow returned a clarification question without targetField or question"
                );
            }
            String targetField = question.targetField().trim();
            if (ClarificationField.fromCode(targetField).isEmpty()) {
                throw new AiWorkflowUnavailableException(
                        "AI workflow returned an invalid clarification targetField: " + targetField
                );
            }
            if (!missingCodes.contains(targetField)) {
                throw new AiWorkflowUnavailableException(
                        "AI workflow returned a clarification targetField that is not missing: " + targetField
                );
            }
            questions.add(new ClarificationQuestion(null, targetField, question.question().trim()));
        }
        if (questions.isEmpty()) {
            throw new AiWorkflowUnavailableException("AI workflow returned no clarification questions");
        }
        return List.copyOf(questions);
    }

    private Project requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BadRequestException("projectId must be greater than 0");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        projectAccessService.requireAccess(projectId);
        return project;
    }

    private Project requireProjectForUpdate(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BadRequestException("projectId must be greater than 0");
        }
        Project project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        projectAccessService.requireAccess(projectId);
        return project;
    }

    private static RequirementSummaryData projectContext(Project project, ClarificationCheckRequest request) {
        return new RequirementSummaryData(
                firstNonBlank(request.subject(), project.getCourseName()),
                firstNonBlank(request.topic(), project.getChapterTopic()),
                firstNonBlank(request.gradeLevel(), project.getTargetAudience()),
                lessonDurationMinutes(request.lessonDuration(), project.getLessonDurationMinutes()),
                textValues(request.teachingGoals()),
                textValues(request.keyPoints(), request.difficultPoints()),
                normalizedValues(request.outputTypes()),
                null,
                null
        );
    }

    private static Integer lessonDurationMinutes(String value, Integer fallback) {
        if (hasText(value)) {
            java.util.regex.Matcher matcher = Pattern.compile("(\\d+)").matcher(value);
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
            if (hasText(value)) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizedValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(ClarificationService::hasText).map(String::trim).distinct().toList();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return hasText(preferred) ? preferred.trim() : (hasText(fallback) ? fallback.trim() : null);
    }

    private static String normalizedRawRequirement(ClarificationCheckRequest request) {
        String rawText = valueOrEmpty(request.rawRequirementText()).trim();
        return rawText.isEmpty() ? "待补充教学需求" : rawText;
    }

    private static void addOptionalKnownField(List<String> knownFields, String field, String value) {
        if (hasText(value)) {
            knownFields.add(field);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasOutputTypes(List<String> outputTypes) {
        return outputTypes != null && outputTypes.stream().anyMatch(ClarificationService::hasText);
    }

    private static boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private static boolean containsPositiveOutputType(String rawText) {
        String normalized = valueOrEmpty(rawText).toLowerCase(Locale.ROOT);
        for (String keyword : OUTPUT_TYPE_KEYWORDS) {
            int fromIndex = 0;
            int keywordIndex;
            while ((keywordIndex = normalized.indexOf(keyword, fromIndex)) >= 0) {
                int prefixStart = Math.max(0, keywordIndex - 8);
                String prefix = normalized.substring(prefixStart, keywordIndex);
                if (NEGATION_KEYWORDS.stream().noneMatch(prefix::contains)) {
                    return true;
                }
                fromIndex = keywordIndex + keyword.length();
            }
        }
        return false;
    }

    private static boolean looksLikeTopicInRawText(String rawText) {
        if (!hasText(rawText)) {
            return false;
        }
        String cleaned = rawText.toLowerCase(Locale.ROOT);
        for (String phrase : List.of(
                "教学设计", "互动内容", "幻灯片", "练习题", "课件", "ppt",
                "帮我", "生成", "设计", "制作", "一节", "一堂", "一份", "一个", "课程", "做"
        )) {
            cleaned = cleaned.replace(phrase, "");
        }
        cleaned = cleaned.replace("课", "").trim();
        for (String subject : KNOWN_SUBJECTS) {
            cleaned = cleaned.replace(subject.toLowerCase(Locale.ROOT), "");
        }
        cleaned = GRADE_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = DURATION_PATTERN.matcher(cleaned).replaceAll("");
        return cleaned.trim().length() >= 3;
    }

    private record Evaluation(
            boolean complete,
            List<MissingField> missingFields,
            List<String> knownFields
    ) {
    }
}
