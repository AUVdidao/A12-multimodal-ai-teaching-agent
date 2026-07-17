package com.auvdidao.a12teachingagent.material.parse;

import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;

import java.util.List;

public interface MaterialPrototypeParser {

    ParsedContent parse(UploadedMaterial material, List<PurposeType> usageTypes, RequirementSummary requirementSummary);

    record ParsedContent(
            String summary,
            List<String> keywords,
            List<String> teachingStages,
            String analysisText
    ) {

        public ParsedContent(String summary, List<String> keywords, List<String> teachingStages) {
            this(summary, keywords, teachingStages, summary);
        }
    }
}
