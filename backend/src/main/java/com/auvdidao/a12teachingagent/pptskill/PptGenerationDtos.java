package com.auvdidao.a12teachingagent.pptskill;

public final class PptGenerationDtos {
    private PptGenerationDtos() {}

    public record GenerationResponse(
            Long projectId,
            Long artifactId,
            Long versionId,
            Integer versionNumber,
            String generator,
            String runnerJobId,
            String fileName,
            long sizeBytes,
            String sha256,
            String qaLevel,
            boolean qaPassed,
            long buildDurationMs,
            long qaDurationMs,
            long totalDurationMs,
            String downloadUrl
    ) {}
}
