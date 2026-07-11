package com.auvdidao.a12teachingagent.material.parse;

import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.material.MaterialLabels;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class DeterministicMaterialPrototypeParser implements MaterialPrototypeParser {

    @Override
    public ParsedContent parse(
            UploadedMaterial material,
            List<PurposeType> usageTypes,
            RequirementSummary requirementSummary
    ) {
        List<String> stages = MaterialLabels.teachingStages(usageTypes);
        String usageText = usageTypes.stream()
                .map(MaterialLabels::usageLabel)
                .collect(Collectors.joining("、"));
        String topic = fallback(requirementSummary.getTopic(), "当前课题");
        String goal = fallback(requirementSummary.getTeachingGoals(), "教师已确认的教学目标");
        String summary = "该资料以「" + material.getOriginalFileName() + "」的文件名、资料用途和已确认教学需求为依据，"
                + "作为" + usageText + "用于「" + topic + "」课程的" + String.join("、", stages)
                + "，可支撑“" + abbreviate(goal, 90) + "”。本结果为确定性原型摘要，未读取或声称理解文件全文。";

        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        addKeyword(keywords, requirementSummary.getSubject());
        addKeyword(keywords, requirementSummary.getTopic());
        addKeyword(keywords, filenameStem(material.getOriginalFileName()));
        usageTypes.stream().map(MaterialLabels::usageLabel).forEach(value -> addKeyword(keywords, value));
        stages.forEach(value -> addKeyword(keywords, value));
        while (keywords.size() < 3) {
            keywords.add("教学资料" + (keywords.size() + 1));
        }

        return new ParsedContent(summary, keywords.stream().limit(6).toList(), stages);
    }

    private static void addKeyword(LinkedHashSet<String> keywords, String value) {
        if (value != null && !value.isBlank()) {
            keywords.add(value.trim());
        }
    }

    private static String filenameStem(String filename) {
        if (filename == null || filename.isBlank()) {
            return "教学资料";
        }
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename).trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String abbreviate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "…";
    }
}
