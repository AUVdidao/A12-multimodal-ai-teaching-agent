package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.embedding.EmbeddedKnowledgeChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DenseEmbeddingPersistenceServiceTest {

    @Mock private KnowledgeChunkRepository chunkRepository;
    @Mock private KnowledgeEmbeddingStore embeddingStore;

    @Test
    void contentMutationBeforePersistRejectsAllPendingWrites() {
        KnowledgeChunk original = chunk("old");
        DenseChunkSnapshot snapshot = new DenseChunkSnapshot(
                original, KnowledgeEmbeddingStore.contentHash(original.getContent())
        );
        KnowledgeChunk changed = chunk("changed");
        changed.setId(original.getId());
        changed.setChunkNo(original.getChunkNo());
        when(chunkRepository.findAllById(any())).thenReturn(List.of(changed));

        EmbeddedKnowledgeChunk embedded = new EmbeddedKnowledgeChunk(
                1L, 7L, 10L, 1, "source.pdf", "fake-provider", "fake-model", 2,
                List.of(1.0, 0.0)
        );

        assertThatThrownBy(() -> new DenseEmbeddingPersistenceService(chunkRepository, embeddingStore)
                .persist(List.of(snapshot), List.of(embedded), "fake-provider", "fake-model"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no embeddings were persisted");
        verify(embeddingStore, never()).upsert(any(), any(), any(), anyList());
    }

    private KnowledgeChunk chunk(String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(1L);
        chunk.setProjectId(7L);
        chunk.setMaterialId(10L);
        chunk.setChunkNo(1);
        chunk.setContent(content);
        chunk.setSourceFilename("source.pdf");
        return chunk;
    }
}
