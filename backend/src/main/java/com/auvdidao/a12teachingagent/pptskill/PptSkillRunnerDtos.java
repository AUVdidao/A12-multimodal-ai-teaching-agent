package com.auvdidao.a12teachingagent.pptskill;

import java.util.Map;

public final class PptSkillRunnerDtos {
    private PptSkillRunnerDtos() {}

    public record RunnerResult(
            String jobId,
            String status,
            String fileName,
            long sizeBytes,
            String sha256,
            RunnerQa qa,
            long buildDurationMs,
            long qaDurationMs,
            long totalDurationMs,
            byte[] pptx,
            String outlineJson,
            String qaReportJson
    ) {}

    public record RunnerQa(boolean passed, String qaLevel, Map<String, Object> report) {}
}
