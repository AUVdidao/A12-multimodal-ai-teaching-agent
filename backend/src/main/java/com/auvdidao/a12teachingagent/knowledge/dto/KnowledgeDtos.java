package com.auvdidao.a12teachingagent.knowledge.dto;

import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    public record KnowledgeChunkResponse(
            Long chunkId,
            Long projectId,
            Long materialId,
            Integer chunkNo,
            String sourceFilename,
            String title,
            String content,
            List<String> keywords,
            List<PurposeType> usageTypes,
            LocalDateTime createdAt
    ) {
    }

    public record KnowledgeOverviewResponse(
            long indexedMaterialCount,
            long chunkCount,
            List<KnowledgeChunkResponse> chunks,
            boolean prototype
    ) {
    }

    public record KnowledgeSearchRequest(
            @NotBlank(message = "Search query is required") String query,
            @Min(value = 1, message = "limit must be at least 1")
            @Max(value = 20, message = "limit must be at most 20")
            Integer limit
    ) {
    }

    public record KnowledgeHitResponse(
            Long chunkId,
            Long materialId,
            String sourceFilename,
            String title,
            String content,
            double score,
            String hitReason,
            List<PurposeType> usageTypes,
            List<String> keywords
    ) {
    }

    public record KnowledgeSearchResponse(
            String query,
            List<KnowledgeHitResponse> hits,
            boolean prototype,
            String algorithm
    ) {
    }
}
