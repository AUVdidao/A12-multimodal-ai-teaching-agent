package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationResponse;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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

    public ClarificationService(
            AIWorkflowGateway aiWorkflowGateway,
            ProjectRepository projectRepository,
            ProjectAccessService projectAccessService
    ) {
        this.aiWorkflowGateway = aiWorkflowGateway;
        this.projectRepository = projectRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(readOnly = true)
    public ClarificationResult check(Long projectId, ClarificationCheckRequest request) {
        requireProject(projectId);
        Evaluation evaluation = evaluate(request);
        return new ClarificationResult(evaluation.complete(), evaluation.missingFields(), List.of());
    }

    @Transactional(readOnly = true)
    public ClarificationResult questions(Long projectId, ClarificationCheckRequest request) {
        requireProject(projectId);
        Evaluation evaluation = evaluate(request);
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
                GenerationMode.MOCK,
                missingCodes
        ));

        return new ClarificationResult(false, evaluation.missingFields(), adaptQuestions(missingCodes, response));
    }

    private Evaluation evaluate(ClarificationCheckRequest request) {
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }

        String rawText = valueOrEmpty(request.rawRequirementText());
        List<MissingField> missingFields = new ArrayList<>();
        List<String> knownFields = new ArrayList<>();

        evaluateField(missingFields, knownFields, ClarificationField.GRADE_LEVEL,
                hasText(request.gradeLevel()) || GRADE_PATTERN.matcher(rawText).find());
        evaluateField(missingFields, knownFields, ClarificationField.SUBJECT,
                hasText(request.subject()) || containsAny(rawText, KNOWN_SUBJECTS));
        evaluateField(missingFields, knownFields, ClarificationField.TOPIC,
                hasText(request.topic()) || looksLikeTopicInRawText(rawText));
        evaluateField(missingFields, knownFields, ClarificationField.LESSON_DURATION,
                hasText(request.lessonDuration()) || DURATION_PATTERN.matcher(rawText).find());
        evaluateField(missingFields, knownFields, ClarificationField.TEACHING_GOALS,
                hasText(request.teachingGoals()));
        evaluateField(missingFields, knownFields, ClarificationField.OUTPUT_TYPES,
                hasOutputTypes(request.outputTypes()) || containsPositiveOutputType(rawText));

        addOptionalKnownField(knownFields, "keyPoints", request.keyPoints());
        addOptionalKnownField(knownFields, "difficultPoints", request.difficultPoints());
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

    private static List<String> adaptQuestions(List<String> missingCodes, ClarificationResponse response) {
        if (response == null || response.missingFields() == null || response.questions() == null) {
            throw new AiWorkflowUnavailableException("AI workflow returned an incomplete clarification response");
        }

        Map<String, String> questionsByField = new LinkedHashMap<>();
        int pairCount = Math.min(response.missingFields().size(), response.questions().size());
        for (int index = 0; index < pairCount; index++) {
            String field = response.missingFields().get(index);
            String question = response.questions().get(index);
            if (field != null && hasText(question)) {
                questionsByField.putIfAbsent(field, question.trim());
            }
        }

        List<String> questions = new ArrayList<>();
        for (String field : missingCodes) {
            String question = questionsByField.get(field);
            if (!hasText(question)) {
                throw new AiWorkflowUnavailableException(
                        "AI workflow did not return a question for missing field: " + field
                );
            }
            questions.add(question);
        }
        return List.copyOf(questions);
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
