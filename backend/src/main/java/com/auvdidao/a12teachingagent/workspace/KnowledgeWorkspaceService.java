package com.auvdidao.a12teachingagent.workspace;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.material.MaterialLabels;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.KnowledgeWorkspaceHit;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.KnowledgeWorkspaceSearchRequest;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.KnowledgeWorkspaceSearchResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class KnowledgeWorkspaceService {

    private final ProjectRepository projectRepository;
    private final UploadedMaterialRepository materialRepository;
    private final KnowledgeChunkRepository chunkRepository;

    public KnowledgeWorkspaceService(
            ProjectRepository projectRepository,
            UploadedMaterialRepository materialRepository,
            KnowledgeChunkRepository chunkRepository
    ) {
        this.projectRepository = projectRepository;
        this.materialRepository = materialRepository;
        this.chunkRepository = chunkRepository;
    }

    @Transactional(readOnly = true)
    public KnowledgeWorkspaceSearchResponse search(Long projectId, KnowledgeWorkspaceSearchRequest request) {
        requireProject(projectId);
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        String query = request.query() == null ? null : request.query().trim();
        if (query == null || query.isEmpty()) {
            throw new BadRequestException("Search query is required");
        }
        String matchMode = normalizeMatchMode(request.matchMode());
        boolean caseSensitive = Boolean.TRUE.equals(request.caseSensitive());
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 10 : request.size();
        if (page < 0) throw new BadRequestException("page must be at least 0");
        if (size < 1 || size > 50) throw new BadRequestException("size must be between 1 and 50");

        if (request.materialId() != null
                && materialRepository.findByIdAndProjectId(request.materialId(), projectId).isEmpty()) {
            throw new ResourceNotFoundException("Material not found in project: " + request.materialId());
        }

        List<String> terms = queryTerms(query, matchMode, caseSensitive);
        List<ScoredChunk> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : chunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(projectId)) {
            if (request.materialId() != null && !request.materialId().equals(chunk.getMaterialId())) continue;
            ScoredChunk value = score(chunk, terms, matchMode, caseSensitive);
            if (value.score() > 0) scored.add(value);
        }
        scored.sort(Comparator.comparingInt(ScoredChunk::score).reversed()
                .thenComparing(value -> value.chunk().getId()));

        int fromIndex = Math.min(page * size, scored.size());
        int toIndex = Math.min(fromIndex + size, scored.size());
        List<KnowledgeWorkspaceHit> hits = scored.subList(fromIndex, toIndex).stream()
                .map(this::toHit)
                .toList();
        int totalPages = scored.isEmpty() ? 0 : (int) Math.ceil((double) scored.size() / size);
        return new KnowledgeWorkspaceSearchResponse(
                projectId,
                query,
                matchMode,
                caseSensitive,
                page,
                size,
                scored.size(),
                totalPages,
                hits,
                matchMode.equals("PRECISE")
                        ? "确定性精确短语、关键词、标题与正文加权"
                        : "确定性分词、关键词、标题与正文加权",
                true
        );
    }

    private ScoredChunk score(KnowledgeChunk chunk, List<String> terms, String matchMode, boolean caseSensitive) {
        int score = 0;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        String title = normalize(chunk.getTitle(), caseSensitive);
        String content = normalize(chunk.getContent(), caseSensitive);

        for (String term : terms) {
            String matchedKeyword = chunk.getKeywords().stream()
                    .filter(keyword -> keywordMatches(normalize(keyword, caseSensitive), term, matchMode))
                    .findFirst()
                    .orElse(null);
            if (matchedKeyword != null) {
                score += matchMode.equals("PRECISE") ? 55 : 35;
                reasons.add("命中关键词「" + matchedKeyword + "」");
            }
            if (textMatches(title, term, matchMode)) {
                score += matchMode.equals("PRECISE") ? 28 : 20;
                reasons.add("标题匹配");
            }
            if (textMatches(content, term, matchMode)) {
                score += matchMode.equals("PRECISE") ? 17 : 12;
                reasons.add("正文内容匹配");
            }
            boolean usageMatched = chunk.getUsageTypes().stream()
                    .map(MaterialLabels::usageLabel)
                    .map(value -> normalize(value, caseSensitive))
                    .anyMatch(value -> textMatches(value, term, "BROAD"));
            if (usageMatched) {
                score += 8;
                reasons.add("资料用途匹配");
            }
        }
        return new ScoredChunk(chunk, Math.min(100, score), String.join("，", reasons) + (reasons.isEmpty() ? "" : "。"));
    }

    private KnowledgeWorkspaceHit toHit(ScoredChunk value) {
        KnowledgeChunk chunk = value.chunk();
        return new KnowledgeWorkspaceHit(
                chunk.getId(),
                chunk.getMaterialId(),
                chunk.getChunkNo() == null ? 0 : chunk.getChunkNo(),
                value.score(),
                chunk.getTitle(),
                chunk.getContent(),
                chunk.getSourceFilename(),
                "知识片段 #" + (chunk.getChunkNo() == null ? 0 : chunk.getChunkNo()),
                chunk.getKeywords(),
                chunk.getUsageTypes(),
                value.reason()
        );
    }

    private void requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BadRequestException("projectId must be greater than 0");
        }
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
    }

    private static List<String> queryTerms(String query, String matchMode, boolean caseSensitive) {
        String normalized = normalize(query, caseSensitive);
        if (matchMode.equals("PRECISE")) return List.of(normalized);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(normalized);
        for (String part : normalized.split("[\\s,，。;；:：、!?！？]+")) {
            if (!part.isBlank()) terms.add(part);
        }
        return List.copyOf(terms);
    }

    private static boolean keywordMatches(String keyword, String term, String matchMode) {
        return matchMode.equals("PRECISE") ? keyword.equals(term) || keyword.contains(term) : containsEither(keyword, term);
    }

    private static boolean textMatches(String value, String term, String matchMode) {
        return matchMode.equals("PRECISE") ? value.contains(term) : containsEither(value, term);
    }

    private static boolean containsEither(String left, String right) {
        return left.contains(right) || right.contains(left);
    }

    private static String normalize(String value, boolean caseSensitive) {
        String normalized = value == null ? "" : value.trim();
        return caseSensitive ? normalized : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeMatchMode(String value) {
        if (value == null || value.isBlank()) return "PRECISE";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("EXACT".equals(normalized)) normalized = "PRECISE";
        if (!List.of("PRECISE", "BROAD").contains(normalized)) {
            throw new BadRequestException("Unsupported matchMode: " + value);
        }
        return normalized;
    }

    private record ScoredChunk(KnowledgeChunk chunk, int score, String reason) {
    }
}
