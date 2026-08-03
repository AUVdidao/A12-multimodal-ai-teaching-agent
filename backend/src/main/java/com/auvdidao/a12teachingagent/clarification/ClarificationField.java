package com.auvdidao.a12teachingagent.clarification;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum ClarificationField {

    GRADE_LEVEL(
            "gradeLevel",
            "年级",
            "生成教学内容需要明确学生学段",
            "请补充本节课面向哪个年级学生？"
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
    BASELINE_LEVEL(
            "baselineLevel",
            "基础水平",
            "学生基础决定讲解起点和内容深度",
            "学生目前具备怎样的基础知识和能力？"
    ),
    DIFFICULT_POINTS(
            "difficultPoints",
            "重点难点",
            "重点难点决定讲解和练习的侧重",
            "本节课需要重点突破哪些知识点或难点？"
    ),
    STYLE_PREFERENCE(
            "stylePreference",
            "教学风格",
            "教学风格影响内容组织和表达方式",
            "你希望采用怎样的教学风格？"
    ),
    INTERACTION_TYPE(
            "interactionType",
            "互动设计",
            "互动设计决定课堂活动和参与方式",
            "你希望安排哪些课堂互动形式？"
    ),
    OUTPUT_TYPES(
            "outputTypes",
            "输出类型",
            "系统需要明确要生成 PPT、教案或互动内容",
            "你希望生成 PPT、教案还是互动内容？"
    );

    public static final List<ClarificationField> REQUIRED_FIELDS = List.of(
            GRADE_LEVEL,
            TOPIC,
            LESSON_DURATION,
            TEACHING_GOALS,
            BASELINE_LEVEL,
            DIFFICULT_POINTS,
            STYLE_PREFERENCE,
            INTERACTION_TYPE,
            OUTPUT_TYPES
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

    public String label() {
        return label;
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
