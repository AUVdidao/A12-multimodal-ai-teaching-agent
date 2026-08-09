package com.auvdidao.a12teachingagent.agent.runtime;

/** Evidence item that keeps source identity alongside a bounded excerpt. */
public record GroundedEvidence(
        long chunkId,
        long materialId,
        String sourceFilename,
        String title,
        String contentExcerpt,
        double score,
        RetrievalMode retrievalMode
) {
    public static final int MAX_EXCERPT_CHARS = 4096;

    public GroundedEvidence {
        ContractValidation.requirePositive(chunkId, "chunkId");
        ContractValidation.requirePositive(materialId, "materialId");
        sourceFilename = ContractValidation.requiredText(sourceFilename, "sourceFilename", 512);
        title = ContractValidation.requiredText(title, "title", 512);
        contentExcerpt = ContractValidation.requiredText(contentExcerpt, "contentExcerpt", MAX_EXCERPT_CHARS);
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        if (retrievalMode == null) {
            throw new IllegalArgumentException("retrievalMode must not be null");
        }
    }
}
