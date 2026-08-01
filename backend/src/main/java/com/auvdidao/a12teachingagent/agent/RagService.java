package com.auvdidao.a12teachingagent.agent;

import java.util.List;

/** Retrieval contract for project-scoped knowledge augmentation. */
public interface RagService {
    List<KnowledgeChunk> retrieve(RagQuery query);

    record RagQuery(Long projectId, String query, List<String> keywords) {
        public RagQuery {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    record KnowledgeChunk(String title, String sourceName, String content, double score) {
    }
}
