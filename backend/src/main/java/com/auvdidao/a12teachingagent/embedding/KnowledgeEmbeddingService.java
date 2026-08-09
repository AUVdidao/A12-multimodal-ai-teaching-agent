package com.auvdidao.a12teachingagent.embedding;

import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class KnowledgeEmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeEmbeddingService.class);

    private final EmbeddingProvider embeddingProvider;

    public KnowledgeEmbeddingService(EmbeddingProvider embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public List<EmbeddedKnowledgeChunk> embedChunks(List<KnowledgeChunk> chunks) {
        validateChunks(chunks);
        List<String> embeddingTexts = chunks.stream()
                .map(chunk -> normalizeChunkContent(chunk.getContent()))
                .toList();

        EmbeddingBatchResult result = embed(embeddingTexts);
        List<EmbeddingVector> orderedVectors = validateAndOrderVectors(result, chunks.size());
        LOGGER.info(
                "Knowledge chunk embeddings completed: projectId={}, chunks={}, provider={}, model={}, dimensions={}",
                chunks.get(0).getProjectId(),
                chunks.size(),
                result.provider(),
                result.model(),
                result.dimensions()
        );

        List<EmbeddedKnowledgeChunk> embeddedChunks = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunk chunk = chunks.get(index);
            embeddedChunks.add(new EmbeddedKnowledgeChunk(
                    chunk.getId(),
                    chunk.getProjectId(),
                    chunk.getMaterialId(),
                    chunk.getChunkNo(),
                    chunk.getSourceFilename(),
                    result.provider(),
                    result.model(),
                    result.dimensions(),
                    orderedVectors.get(index).values()
            ));
        }
        return List.copyOf(embeddedChunks);
    }

    public EmbeddedQuery embedQuery(String query) {
        if (!StringUtils.hasText(query)) {
            throw invalidInput("Embedding query must not be blank");
        }

        String normalizedQuery = query.strip();
        EmbeddingBatchResult result = embed(List.of(normalizedQuery));
        List<EmbeddingVector> orderedVectors = validateAndOrderVectors(result, 1);
        EmbeddingVector vector = orderedVectors.get(0);
        LOGGER.info(
                "Query embedding completed: queryLength={}, provider={}, model={}, dimensions={}",
                normalizedQuery.length(),
                result.provider(),
                result.model(),
                result.dimensions()
        );
        return new EmbeddedQuery(result.provider(), result.model(), result.dimensions(), vector.values());
    }

    private EmbeddingBatchResult embed(List<String> inputs) {
        EmbeddingBatchResult result = embeddingProvider.embed(inputs);
        if (result == null) {
            throw invalidResponse("Embedding provider returned no result");
        }
        return result;
    }

    private List<EmbeddingVector> validateAndOrderVectors(EmbeddingBatchResult result, int expectedCount) {
        if (result.dimensions() <= 0
                || !StringUtils.hasText(result.provider())
                || !StringUtils.hasText(result.model())
                || result.vectors() == null
                || result.vectors().size() != expectedCount) {
            throw invalidResponse("Embedding provider result violates the batch contract");
        }

        List<EmbeddingVector> ordered = new ArrayList<>(java.util.Collections.nCopies(expectedCount, null));
        Set<Integer> seen = new HashSet<>();
        for (EmbeddingVector vector : result.vectors()) {
            if (vector == null
                    || vector.values() == null
                    || vector.values().size() != result.dimensions()
                    || vector.index() < 0
                    || vector.index() >= expectedCount
                    || !seen.add(vector.index())) {
                throw invalidResponse("Embedding provider result contains invalid vector metadata");
            }
            ordered.set(vector.index(), vector);
        }
        if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalidResponse("Embedding provider result is missing a vector index");
        }
        return ordered;
    }

    private void validateChunks(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw invalidInput("Knowledge chunks must not be empty");
        }
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunk chunk = chunks.get(index);
            if (chunk == null) {
                throw invalidInput("Knowledge chunk at index " + index + " is missing");
            }
            if (chunk.getId() == null) {
                throw invalidInput("Knowledge chunk at index " + index + " has no id");
            }
            if (chunk.getProjectId() == null) {
                throw invalidInput("Knowledge chunk at index " + index + " has no project id");
            }
            if (chunk.getMaterialId() == null) {
                throw invalidInput("Knowledge chunk at index " + index + " has no material id");
            }
            if (chunk.getChunkNo() == null) {
                throw invalidInput("Knowledge chunk at index " + index + " has no chunk number");
            }
            if (!StringUtils.hasText(chunk.getContent())) {
                throw invalidInput("Knowledge chunk at index " + index + " has blank content");
            }
        }
    }

    private String normalizeChunkContent(String content) {
        return content.strip().replace("\r\n", "\n").replace('\r', '\n');
    }

    private EmbeddingException invalidInput(String message) {
        return new EmbeddingException(EmbeddingFailureKind.INVALID_INPUT, message);
    }

    private EmbeddingException invalidResponse(String message) {
        return new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE, message);
    }
}
