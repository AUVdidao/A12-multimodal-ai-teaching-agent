package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeHitResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeOverviewResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeSearchResponse;
import com.auvdidao.a12teachingagent.material.MaterialLabels;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class KnowledgeSearchService {

    private final ProjectRepository projectRepository;
    private final KnowledgeChunkRepository chunkRepository;

    public KnowledgeSearchService(ProjectRepository projectRepository, KnowledgeChunkRepository chunkRepository) {
        this.projectRepository = projectRepository;
        this.chunkRepository = chunkRepository;
    }

    @Transactional(readOnly = true)
    public KnowledgeOverviewResponse overview(Long projectId) {
        requireProject(projectId);
        List<KnowledgeChunk> chunks = chunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(projectId);
        long materialCount = chunks.stream().map(KnowledgeChunk::getMaterialId).distinct().count();
        return new KnowledgeOverviewResponse(
                materialCount,
                chunks.size(),
                chunks.stream().map(KnowledgeIndexService::toResponse).toList(),
                true
        );
    }

    @Transactional(readOnly = true)
    public KnowledgeSearchResponse search(Long projectId, String query, Integer requestedLimit) {
        requireProject(projectId);
        String normalizedQuery = normalizeQuery(query);
        int limit = requestedLimit == null ? 10 : requestedLimit;
        if (limit < 1 || limit > 20) {
            throw new BadRequestException("limit must be between 1 and 20");
        }

        List<String> terms = queryTerms(normalizedQuery);
        List<ScoredChunk> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : chunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(projectId)) {
            ScoredChunk value = score(chunk, terms);
            if (value.score() > 0) {
                scored.add(value);
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(value -> value.chunk().getId()));

        List<KnowledgeHitResponse> hits = scored.stream().limit(limit).map(value -> new KnowledgeHitResponse(
                value.chunk().getId(),
                value.chunk().getMaterialId(),
                value.chunk().getSourceFilename(),
                value.chunk().getTitle(),
                value.chunk().getContent(),
                value.score(),
                value.hitReason(),
                value.chunk().getUsageTypes(),
                value.chunk().getKeywords()
        )).toList();
        return new KnowledgeSearchResponse(
                query.trim(),
                hits,
                true,
                "确定性关键词、标题、内容与资料用途加权"
        );
    }

    private ScoredChunk score(KnowledgeChunk chunk, List<String> terms) {
        double score = 0;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        String title = normalize(chunk.getTitle());
        String content = normalize(chunk.getContent());

        for (String term : terms) {
            String matchedKeyword = chunk.getKeywords().stream()
                    .filter(keyword -> containsEither(normalize(keyword), term))
                    .findFirst()
                    .orElse(null);
            if (matchedKeyword != null) {
                score += 5;
                reasons.add("命中资料关键词「" + matchedKeyword + "」");
            }
            if (title.contains(term)) {
                score += 3;
                reasons.add("标题包含查询词");
            }
            if (content.contains(term)) {
                score += 2;
                reasons.add("知识片段内容包含查询词");
            }
            for (PurposeType usage : chunk.getUsageTypes()) {
                if (normalize(MaterialLabels.usageLabel(usage)).contains(term)) {
                    score += 1;
                    reasons.add("资料用途匹配" + MaterialLabels.usageLabel(usage));
                }
            }
        }

        if (score > 0 && !chunk.getUsageTypes().isEmpty()) {
            reasons.add("来源资料被标记为" + MaterialLabels.usageLabel(chunk.getUsageTypes().get(0)));
        }
        return new ScoredChunk(chunk, score, String.join("，", reasons) + "。");
    }

    private void requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BadRequestException("projectId must be greater than 0");
        }
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new BadRequestException("Search query is required");
        }
        return normalize(query.trim());
    }

    private static List<String> queryTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(query);
        for (String part : query.split("[\\s,，。;；:：、]+")) {
            if (!part.isBlank()) {
                terms.add(part);
            }
        }
        return List.copyOf(terms);
    }

    private static boolean containsEither(String left, String right) {
        return left.contains(right) || right.contains(left);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record ScoredChunk(KnowledgeChunk chunk, double score, String hitReason) {
    }
}
