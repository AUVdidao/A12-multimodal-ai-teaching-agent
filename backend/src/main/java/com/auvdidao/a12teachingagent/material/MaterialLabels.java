package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.domain.common.PurposeType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class MaterialLabels {

    public static final Set<PurposeType> SUPPORTED_USAGES = EnumSet.of(
            PurposeType.TEXTBOOK_BASIS,
            PurposeType.CASE_MATERIAL,
            PurposeType.EXERCISE_SOURCE,
            PurposeType.KNOWLEDGE_SUPPLEMENT,
            PurposeType.IMAGE_ASSET
    );

    private MaterialLabels() {
    }

    public static String usageLabel(PurposeType type) {
        return switch (type) {
            case TEXTBOOK_BASIS -> "教材依据";
            case CASE_MATERIAL -> "案例素材";
            case EXERCISE_SOURCE -> "习题来源";
            case KNOWLEDGE_SUPPLEMENT -> "知识补充";
            case IMAGE_ASSET -> "图片素材";
            default -> type.name();
        };
    }

    public static List<String> teachingStages(List<PurposeType> types) {
        java.util.LinkedHashSet<String> stages = new java.util.LinkedHashSet<>();
        for (PurposeType type : types) {
            switch (type) {
                case TEXTBOOK_BASIS -> stages.add("概念讲解");
                case CASE_MATERIAL -> {
                    stages.add("课堂导入");
                    stages.add("案例分析");
                }
                case EXERCISE_SOURCE -> stages.add("课堂练习");
                case KNOWLEDGE_SUPPLEMENT -> stages.add("课后拓展");
                case IMAGE_ASSET -> {
                    stages.add("课堂导入");
                    stages.add("案例分析");
                }
                default -> {
                }
            }
        }
        if (stages.isEmpty()) {
            stages.add("概念讲解");
        }
        return List.copyOf(stages);
    }
}
