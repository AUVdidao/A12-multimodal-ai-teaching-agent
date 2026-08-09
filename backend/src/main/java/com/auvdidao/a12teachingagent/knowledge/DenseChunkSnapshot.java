package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;

import java.util.Objects;

record DenseChunkSnapshot(KnowledgeChunk chunk, String contentHash) {

    DenseChunkSnapshot {
        chunk = Objects.requireNonNull(chunk, "chunk");
        contentHash = Objects.requireNonNull(contentHash, "contentHash");
    }
}
