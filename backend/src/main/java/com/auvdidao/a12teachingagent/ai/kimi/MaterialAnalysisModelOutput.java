package com.auvdidao.a12teachingagent.ai.kimi;

import java.util.List;

public record MaterialAnalysisModelOutput(
        String summary,
        List<String> keywords,
        List<String> teachingUses
) {
}
