package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeHitResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeMaterialReadResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeOverviewResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeSearchResponse;
import com.auvdidao.a12teachingagent.material.MaterialLabels;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeSearchService {

    private static final Pattern CHUNK_LOCATOR = Pattern.compile("^(?:chunk[-:]?)?(\\d+)$", Pattern.CASE_INSENSITIVE);

    private final ProjectRepository projectRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final ProjectAccessService projectAccessService;
    private final KnowledgeVectorStore vectorStore;

    public KnowledgeSearchService(
            ProjectRepository projectRepository,
            KnowledgeChunkRepository chunkRepository,
            ProjectAccessService projectAccessService
    ) {
        this(projectRepository, chunkRepository, projectAccessService, null);
    }

    @Autowired
    public KnowledgeSearchService(
            ProjectRepository projectRepository,
            KnowledgeChunkRepository chunkRepository,
            ProjectAccessService projectAccessService,
            KnowledgeVectorStore vectorStore
    ) {
        this.projectRepository = projectRepository;
        this.chunkRepository = chunkRepository;
        this.projectAccessService = projectAccessService;
        this.vectorStore = vectorStore;
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
        return searchWithinMaterialIds(projectId, query, requestedLimit, null);
    }

    @Transactional(readOnly = true)
    public KnowledgeSearchResponse searchWithinMaterialIds(
            Long projectId,
            String query,
            Integer requestedLimit,
            java.util.Set<Long> allowedMaterialIds
    ) {
        return searchWithinMaterialIds(projectId, query, requestedLimit, allowedMaterialIds, null, null);
    }

    @Transactional(readOnly = true)
    public KnowledgeSearchResponse searchWithinMaterialIds(
            Long projectId,
            String query,
            Integer requestedLimit,
            java.util.Set<Long> allowedMaterialIds,
            List<Double> queryEmbedding,
            String embeddingFallbackReason
    ) {
        requireProject(projectId);
        String normalizedQuery = normalizeQuery(query);
        int limit = requestedLimit == null ? 10 : requestedLimit;
        if (limit < 1 || limit > 20) {
            throw new BadRequestException("limit must be between 1 and 20");
        }

        if (queryEmbedding != null && !queryEmbedding.isEmpty() && vectorStore != null && vectorStore.available()) {
            try {
                List<Long> materialIds = allowedMaterialIds == null ? List.of() : allowedMaterialIds.stream().toList();
                List<KnowledgeVectorStore.VectorHit> vectorHits = vectorStore.search(projectId, materialIds, queryEmbedding, queryEmbedding.size(), limit);
                if (!vectorHits.isEmpty()) {
                    var chunks = chunkRepository.findAllById(vectorHits.stream().map(KnowledgeVectorStore.VectorHit::chunkId).toList())
                            .stream().collect(java.util.stream.Collectors.toMap(KnowledgeChunk::getId, chunk -> chunk));
                    List<KnowledgeHitResponse> hits = vectorHits.stream()
                            .map(hit -> chunks.get(hit.chunkId()) == null ? null : new KnowledgeHitResponse(
                                    hit.chunkId(), hit.materialId(), chunks.get(hit.chunkId()).getSourceFilename(),
                                    chunks.get(hit.chunkId()).getTitle(), chunks.get(hit.chunkId()).getContent(),
                                    hit.score(), "向量余弦相似度", chunks.get(hit.chunkId()).getUsageTypes(), chunks.get(hit.chunkId()).getKeywords()))
                            .filter(java.util.Objects::nonNull)
                            .toList();
                    if (!hits.isEmpty()) {
                        return new KnowledgeSearchResponse(query.trim(), hits, false, "PostgreSQL double precision[] 向量余弦相似度", "VECTOR");
                    }
                }
            } catch (RuntimeException ignored) {
                // The keyword path is an explicit, observable degradation. The
                // response below carries the fallback mode and reason.
            }
        }

        List<String> terms = queryTerms(normalizedQuery);
        List<ScoredChunk> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : chunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(projectId)) {
            if (allowedMaterialIds != null && !allowedMaterialIds.contains(chunk.getMaterialId())) {
                continue;
            }
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
                "确定性关键词、标题、内容与资料用途加权（向量不可用或无命中时降级）" + fallbackReasonSuffix(embeddingFallbackReason),
                "KEYWORD_FALLBACK"
        );
    }

    private static String fallbackReasonSuffix(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        String normalized = reason.replaceAll("[^A-Za-z0-9_.-]", "_");
        return "；向量降级原因=" + normalized.substring(0, Math.min(80, normalized.length()));
    }

    @Transactional(readOnly = true)
    public KnowledgeMaterialReadResponse readMaterial(Long projectId, Long materialId, String locator) {
        requireProject(projectId);
        if (materialId == null || materialId <= 0) {
            throw new BadRequestException("materialId must be greater than 0");
        }
        List<KnowledgeChunk> chunks = chunkRepository.findByMaterialIdOrderByChunkNoAsc(materialId).stream()
                .filter(chunk -> projectId.equals(chunk.getProjectId()))
                .toList();
        if (chunks.isEmpty()) {
            throw new ResourceNotFoundException("Knowledge material not found: " + materialId);
        }

        String requestedLocator = locator == null ? "" : locator.trim();
        List<KnowledgeChunk> selected = selectChunks(chunks, requestedLocator);
        if (selected.isEmpty()) {
            throw new ResourceNotFoundException("Knowledge locator not found: " + requestedLocator);
        }
        String content = selected.stream()
                .map(KnowledgeChunk::getContent)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        return new KnowledgeMaterialReadResponse(projectId, materialId, selected.get(0).getSourceFilename(), requestedLocator, content);
    }

    private List<KnowledgeChunk> selectChunks(List<KnowledgeChunk> chunks, String locator) {
        if (locator.isBlank()) {
            return chunks;
        }
        Matcher matcher = CHUNK_LOCATOR.matcher(locator);
        if (matcher.matches()) {
            int chunkNo;
            try {
                chunkNo = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                return List.of();
            }
            return chunks.stream().filter(chunk -> Integer.valueOf(chunkNo).equals(chunk.getChunkNo())).toList();
        }
        String needle = normalize(locator);
        return chunks.stream()
                .filter(chunk -> normalize(chunk.getTitle()).contains(needle) || normalize(chunk.getContent()).contains(needle))
                .toList();
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
        projectAccessService.requireAccess(projectId);
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
