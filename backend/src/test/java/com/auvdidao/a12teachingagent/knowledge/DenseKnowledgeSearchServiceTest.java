package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunkEmbedding;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkEmbeddingRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DenseKnowledgeSearchServiceTest {

    @Mock
    private KnowledgeChunkEmbeddingRepository embeddingRepository;

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    private DenseKnowledgeSearchService service;

    @BeforeEach
    void setUp() {
        service = new DenseKnowledgeSearchService(
                embeddingRepository,
                chunkRepository,
                new VectorCodec()
        );
    }

    @Test
    void computesExactOrthogonalScoresRanksAndPreservesTraceability() {
        KnowledgeChunk a = chunk(10L, 1L, 100L, 1, "A", "alpha content", "alpha.pdf");
        KnowledgeChunk b = chunk(11L, 1L, 101L, 2, "B", "beta content", "beta.pdf");
        KnowledgeChunk c = chunk(12L, 1L, 102L, 3, "C", "gamma content", "gamma.pdf");
        stub(List.of(
                embedding(10L, a, List.of(1.0, 0.0)),
                embedding(11L, b, List.of(0.8, 0.2)),
                embedding(12L, c, List.of(0.0, 1.0))
        ), List.of(a, b, c));

        List<DenseKnowledgeHit> hits = service.search(1L, List.of(1.0, 0.0), 3);

        assertThat(hits).extracting(DenseKnowledgeHit::chunkId).containsExactly(10L, 11L, 12L);
        assertThat(hits.get(0).score()).isEqualTo(1.0);
        assertThat(hits.get(2).score()).isEqualTo(0.0);
        assertThat(hits.get(1).score()).isCloseTo(0.9701425001, org.assertj.core.data.Offset.offset(0.000000001));
        assertThat(hits.get(0))
                .extracting(DenseKnowledgeHit::materialId, DenseKnowledgeHit::chunkNo,
                        DenseKnowledgeHit::title, DenseKnowledgeHit::content, DenseKnowledgeHit::sourceFilename)
                .containsExactly(100L, 1, "A", "alpha content", "alpha.pdf");
    }

    @Test
    void appliesLimitAndDeterministicChunkIdTieBreak() {
        KnowledgeChunk lowId = chunk(20L, 1L, 120L, 1, "Low", "low content", "low.pdf");
        KnowledgeChunk highId = chunk(30L, 1L, 130L, 1, "High", "high content", "high.pdf");
        stub(List.of(
                embedding(2L, highId, List.of(1.0, 0.0)),
                embedding(1L, lowId, List.of(1.0, 0.0))
        ), List.of(highId, lowId));

        List<DenseKnowledgeHit> hits = service.search(1L, List.of(1.0, 0.0), 1);

        assertThat(hits).extracting(DenseKnowledgeHit::chunkId).containsExactly(20L);
    }

    @Test
    void rejectsZeroQueryAndSkipsDimensionMismatch() {
        KnowledgeChunk valid = chunk(40L, 1L, 140L, 1, "Valid", "valid content", "valid.pdf");
        KnowledgeChunk wrongDimension = chunk(41L, 1L, 141L, 2, "Wrong", "wrong content", "wrong.pdf");
        stub(List.of(
                embedding(40L, valid, List.of(1.0, 0.0)),
                embedding(41L, wrongDimension, List.of(1.0, 0.0, 0.0))
        ), List.of(valid, wrongDimension));

        assertThatThrownBy(() -> service.search(1L, List.of(0.0, 0.0), 5))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.search(1L, List.of(1.0, 0.0), 5))
                .extracting(DenseKnowledgeHit::chunkId)
                .containsExactly(40L);
    }

    @Test
    void skipsMissingChunkProjectMismatchAndStaleContent() {
        KnowledgeChunk valid = chunk(50L, 1L, 150L, 1, "Valid", "valid content", "valid.pdf");
        KnowledgeChunk wrongProject = chunk(51L, 2L, 151L, 2, "Other", "other content", "other.pdf");
        KnowledgeChunk stale = chunk(52L, 1L, 152L, 3, "Stale", "new content", "stale.pdf");

        KnowledgeChunkEmbedding validEmbedding = embedding(50L, valid, List.of(1.0, 0.0));
        KnowledgeChunkEmbedding wrongProjectEmbedding = embedding(51L, wrongProject, List.of(1.0, 0.0));
        wrongProjectEmbedding.setProjectId(1L);
        KnowledgeChunkEmbedding staleEmbedding = embedding(52L, stale, List.of(1.0, 0.0));
        staleEmbedding.setContentHash(KnowledgeEmbeddingStore.contentHash("old content"));
        KnowledgeChunkEmbedding missingEmbedding = embedding(999L, valid, List.of(1.0, 0.0));
        missingEmbedding.setKnowledgeChunkId(999L);

        stub(List.of(validEmbedding, wrongProjectEmbedding, staleEmbedding, missingEmbedding),
                List.of(valid, wrongProject, stale));

        assertThat(service.search(1L, List.of(1.0, 0.0), 10))
                .extracting(DenseKnowledgeHit::chunkId)
                .containsExactly(50L);
    }

    @Test
    void scansApproximatelyOneThousandFixedDimensionVectorsWithoutFragileTimingAssertion() {
        List<KnowledgeChunkEmbedding> embeddings = new ArrayList<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (long index = 1; index <= 1000; index++) {
            String content = "fixed content " + index;
            KnowledgeChunk chunk = chunk(
                    index,
                    99L,
                    9000L + index,
                    (int) index,
                    "Chunk " + index,
                    content,
                    "fixed.pdf"
            );
            List<Double> vector = List.of(1.0, (double) (index % 8), 0.5, 1.0);
            chunks.add(chunk);
            embeddings.add(embedding(index, chunk, vector));
        }
        stub(embeddings, chunks);

        List<DenseKnowledgeHit> hits = service.search(99L, List.of(1.0, 0.0, 0.0, 1.0), 20);

        assertThat(hits).hasSize(20);
        assertThat(hits).allSatisfy(hit -> assertThat(hit.score()).isFinite());
    }

    private void stub(List<KnowledgeChunkEmbedding> embeddings, List<KnowledgeChunk> chunks) {
        when(embeddingRepository.findAllByProjectIdOrderByKnowledgeChunkIdAsc(anyLong()))
                .thenReturn(embeddings);
        when(chunkRepository.findAllById(any())).thenReturn(chunks);
    }

    private static KnowledgeChunkEmbedding embedding(long id, KnowledgeChunk chunk, List<Double> vector) {
        KnowledgeChunkEmbedding embedding = new KnowledgeChunkEmbedding();
        embedding.setId(id);
        embedding.setProjectId(chunk.getProjectId());
        embedding.setMaterialId(chunk.getMaterialId());
        embedding.setKnowledgeChunkId(chunk.getId());
        embedding.setProvider("manual");
        embedding.setModel("fixed-v1");
        embedding.setDimensions(vector.size());
        embedding.setVector(new VectorCodec().encode(vector));
        embedding.setContentHash(KnowledgeEmbeddingStore.contentHash(chunk.getContent()));
        return embedding;
    }

    private static KnowledgeChunk chunk(
            Long id,
            Long projectId,
            Long materialId,
            int chunkNo,
            String title,
            String content,
            String sourceFilename
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setProjectId(projectId);
        chunk.setMaterialId(materialId);
        chunk.setChunkNo(chunkNo);
        chunk.setTitle(title);
        chunk.setContent(content);
        chunk.setSourceFilename(sourceFilename);
        return chunk;
    }
}
