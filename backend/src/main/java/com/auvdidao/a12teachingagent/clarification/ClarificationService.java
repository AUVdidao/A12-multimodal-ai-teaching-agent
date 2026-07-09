package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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

    private final AIWorkflowGateway aiWorkflowGateway;

    public ClarificationService(AIWorkflowGateway aiWorkflowGateway) {
        this.aiWorkflowGateway = aiWorkflowGateway;
    }

    public ClarificationResult check(Long projectId, ClarificationCheckRequest request) {
        ClarificationCheckRequest safeRequest = request == null
                ? new ClarificationCheckRequest(null, null, null, null, null, null, null, null, null)
                : request;
        String rawText = valueOrEmpty(safeRequest.rawRequirementText());

        aiWorkflowGateway.clarifyRequirement(new ClarificationRequest(
                projectId,
                hasText(rawText) ? rawText : "待补充教学需求",
                knownFields(safeRequest, rawText),
                GenerationMode.MOCK
        ));

        List<MissingField> missingFields = new ArrayList<>();
        List<String> questions = new ArrayList<>();

        addIfMissing(missingFields, questions,
                hasText(safeRequest.gradeLevel()) || GRADE_PATTERN.matcher(rawText).find(),
                "gradeLevel",
                "年级",
                "生成教学内容需要明确学生学段",
                "请补充本节课面向哪个年级学生？");

        addIfMissing(missingFields, questions,
                hasText(safeRequest.subject()) || containsAny(rawText, KNOWN_SUBJECTS),
                "subject",
                "学科",
                "不同学科的知识组织方式和表达风格不同",
                "请补充本节课属于哪个学科？");

        addIfMissing(missingFields, questions,
                hasText(safeRequest.topic()) || looksLikeTopicInRawText(rawText),
                "topic",
                "课题",
                "生成课件前需要明确具体课题",
                "请补充本节课的具体课题。");

        addIfMissing(missingFields, questions,
                hasText(safeRequest.lessonDuration()) || DURATION_PATTERN.matcher(rawText).find(),
                "lessonDuration",
                "课时",
                "课时长度会影响内容容量和活动安排",
                "本节课预计多少分钟？");

        addIfMissing(missingFields, questions,
                hasText(safeRequest.teachingGoals()),
                "teachingGoals",
                "教学目标",
                "教学目标决定课件结构、讲解深度和评价方式",
                "请补充本节课希望学生达成的教学目标。");

        addIfMissing(missingFields, questions,
                hasOutputTypes(safeRequest.outputTypes()),
                "outputTypes",
                "输出类型",
                "系统需要明确要生成 PPT、教案或互动内容",
                "你希望生成 PPT、教案还是互动内容？");

        return new ClarificationResult(missingFields.isEmpty(), missingFields, questions);
    }

    private static List<String> knownFields(ClarificationCheckRequest request, String rawText) {
        List<String> fields = new ArrayList<>();
        addKnown(fields, "gradeLevel", hasText(request.gradeLevel()) || GRADE_PATTERN.matcher(rawText).find());
        addKnown(fields, "subject", hasText(request.subject()) || containsAny(rawText, KNOWN_SUBJECTS));
        addKnown(fields, "topic", hasText(request.topic()) || looksLikeTopicInRawText(rawText));
        addKnown(fields, "lessonDuration", hasText(request.lessonDuration()) || DURATION_PATTERN.matcher(rawText).find());
        addKnown(fields, "teachingGoals", hasText(request.teachingGoals()));
        addKnown(fields, "keyPoints", hasText(request.keyPoints()));
        addKnown(fields, "difficultPoints", hasText(request.difficultPoints()));
        addKnown(fields, "outputTypes", hasOutputTypes(request.outputTypes()));
        return fields;
    }

    private static void addKnown(List<String> fields, String field, boolean present) {
        if (present) {
            fields.add(field);
        }
    }

    private static void addIfMissing(
            List<MissingField> missingFields,
            List<String> questions,
            boolean present,
            String field,
            String label,
            String reason,
            String question
    ) {
        if (!present) {
            missingFields.add(new MissingField(field, label, reason));
            questions.add(question);
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

    private static boolean looksLikeTopicInRawText(String rawText) {
        if (!hasText(rawText)) {
            return false;
        }
        String cleaned = rawText
                .replace("帮我", "")
                .replace("生成", "")
                .replace("设计", "")
                .replace("一节", "")
                .replace("一堂", "")
                .replace("课程", "")
                .replace("课", "")
                .replace("PPT", "")
                .replace("ppt", "")
                .replace("课件", "")
                .trim();
        for (String subject : KNOWN_SUBJECTS) {
            cleaned = cleaned.replace(subject, "");
        }
        cleaned = GRADE_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = DURATION_PATTERN.matcher(cleaned).replaceAll("");
        return cleaned.length() >= 3;
    }
}
