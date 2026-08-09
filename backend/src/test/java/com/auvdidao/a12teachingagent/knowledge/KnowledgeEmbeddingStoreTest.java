package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunkEmbedding;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class KnowledgeEmbeddingStoreTest {

    @Autowired
    private KnowledgeChunkEmbeddingRepository embeddingRepository;

    private KnowledgeEmbeddingStore store;

    @BeforeEach
    void setUp() {
        store = new KnowledgeEmbeddingStore(embeddingRepository, new VectorCodec());
    }

    @Test
    void upsertInsertsThenReplacesOneEmbeddingAndTracksContentHash() {
        KnowledgeChunk original = chunk(101L, 11L, 21L, 1, "first content", "first.pdf");

        KnowledgeChunkEmbedding first = store.upsert(original, "provider-A", "model-1", List.of(1.0, 0.0));

        assertThat(first.getId()).isNotNull();
        assertThat(first.getDimensions()).isEqualTo(2);
        assertThat(first.getContentHash())
                .isEqualTo("2cd4837c7726f70047c8fdafb52801dbfef2cb4f7bc4cfb2e0441980f9d4a3b8");
        assertThat(store.findByChunkId(101L)).contains(first);
        assertThat(store.countByProjectId(11L)).isEqualTo(1);

        KnowledgeChunk changed = chunk(101L, 11L, 21L, 1, "changed content", "first.pdf");
        KnowledgeChunkEmbedding replacement = store.upsert(
                changed,
                "provider-B",
                "model-2",
                List.of(0.0, 1.0)
        );

        assertThat(replacement.getId()).isEqualTo(first.getId());
        assertThat(replacement.getProvider()).isEqualTo("provider-B");
        assertThat(replacement.getModel()).isEqualTo("model-2");
        assertThat(store.countByProjectId(11L)).isEqualTo(1);
        assertThat(store.findAllByProjectId(11L)).singleElement()
                .satisfies(value -> {
                    assertThat(value.getKnowledgeChunkId()).isEqualTo(101L);
                    assertThat(value.getProvider()).isEqualTo("provider-B");
                    assertThat(value.getModel()).isEqualTo("model-2");
                    assertThat(value.getContentHash()).isEqualTo(KnowledgeEmbeddingStore.contentHash("changed content"));
                    assertThat(value.getVector()).isEqualTo("[0.0,1.0]");
                });
    }

    @Test
    void deletesByChunkMaterialAndProject() {
        store.upsert(chunk(201L, 12L, 22L, 1, "one", "one.pdf"), "manual", "fixed-v1", List.of(1.0));
        store.upsert(chunk(202L, 12L, 23L, 1, "two", "two.pdf"), "manual", "fixed-v1", List.of(1.0));
        store.upsert(chunk(203L, 13L, 24L, 1, "three", "three.pdf"), "manual", "fixed-v1", List.of(1.0));

        assertThat(store.deleteByChunkId(201L)).isEqualTo(1);
        assertThat(store.deleteByMaterialId(23L)).isEqualTo(1);
        assertThat(store.countByProjectId(12L)).isZero();
        assertThat(store.deleteByProjectId(13L)).isEqualTo(1);
        assertThat(embeddingRepository.count()).isZero();
    }

    @Test
    void rejectsIncompleteChunkAndInvalidEmbeddingInputs() {
        KnowledgeChunk chunk = chunk(301L, 14L, 25L, 1, "valid", "valid.pdf");

        assertThatThrownBy(() -> store.upsert(null, "manual", "fixed-v1", List.of(1.0)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.upsert(chunk, "", "fixed-v1", List.of(1.0)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.upsert(chunk, "manual", "fixed-v1", List.of(Double.NaN)))
                .isInstanceOf(RuntimeException.class);
    }

    private static KnowledgeChunk chunk(
            Long id,
            Long projectId,
            Long materialId,
            int chunkNo,
            String content,
            String sourceFilename
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setProjectId(projectId);
        chunk.setMaterialId(materialId);
        chunk.setChunkNo(chunkNo);
        chunk.setTitle("Title " + chunkNo);
        chunk.setContent(content);
        chunk.setSourceFilename(sourceFilename);
        return chunk;
    }
}
