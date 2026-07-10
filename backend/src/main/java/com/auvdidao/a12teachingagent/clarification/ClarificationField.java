package com.auvdidao.a12teachingagent.clarification;

import java.util.Arrays;
import java.util.Optional;

public enum ClarificationField {

    GRADE_LEVEL(
            "gradeLevel",
            "年级",
            "生成教学内容需要明确学生学段",
            "请补充本节课面向哪个年级学生？"
    ),
    SUBJECT(
            "subject",
            "学科",
            "不同学科的知识组织方式和表达风格不同",
            "请补充本节课属于哪个学科？"
    ),
    TOPIC(
            "topic",
            "课题",
            "生成课件前需要明确具体课题",
            "请补充本节课的具体课题。"
    ),
    LESSON_DURATION(
            "lessonDuration",
            "课时",
            "课时长度会影响内容容量和活动安排",
            "本节课预计多少分钟？"
    ),
    TEACHING_GOALS(
            "teachingGoals",
            "教学目标",
            "教学目标决定课件结构、讲解深度和评价方式",
            "请补充本节课希望学生达成的教学目标。"
    ),
    OUTPUT_TYPES(
            "outputTypes",
            "输出类型",
            "系统需要明确要生成 PPT、教案或互动内容",
            "你希望生成 PPT、教案还是互动内容？"
    );

    private final String code;
    private final String label;
    private final String reason;
    private final String defaultQuestion;

    ClarificationField(String code, String label, String reason, String defaultQuestion) {
        this.code = code;
        this.label = label;
        this.reason = reason;
        this.defaultQuestion = defaultQuestion;
    }

    public String code() {
        return code;
    }

    public String defaultQuestion() {
        return defaultQuestion;
    }

    public MissingField toMissingField() {
        return new MissingField(code, label, reason);
    }

    public static Optional<ClarificationField> fromCode(String code) {
        return Arrays.stream(values())
                .filter(field -> field.code.equals(code))
                .findFirst();
    }
}
