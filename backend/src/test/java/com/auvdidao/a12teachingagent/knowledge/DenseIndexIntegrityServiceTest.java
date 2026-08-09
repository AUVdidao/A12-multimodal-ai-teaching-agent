package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunkEmbedding;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkEmbeddingRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DenseIndexIntegrityServiceTest {

    private static final long PROJECT_ID = 7L;
    private static final long MATERIAL_ID = 10L;
    private static final String PROVIDER = "manual";
    private static final String MODEL = "fixed-v1";

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private KnowledgeChunkEmbeddingRepository embeddingRepository;

    private final VectorCodec vectorCodec = new VectorCodec();

    @Test
    void fullyReadyProjectIsDenseReady() {
        KnowledgeChunk first = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunk second = chunk(2L, PROJECT_ID, MATERIAL_ID, "beta");
        List<KnowledgeChunk> chunks = List.of(first, second);
        stubProject(PROJECT_ID, PROVIDER, MODEL, chunks, List.of(
                readyEmbedding(first, List.of(1.0, 0.0)),
                readyEmbedding(second, List.of(0.0, 1.0))
        ), chunks);

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.chunkCount()).isEqualTo(2);
        assertThat(inspection.embeddingRowCount()).isEqualTo(2);
        assertThat(inspection.readyCount()).isEqualTo(2);
        assertThat(inspection.missingEmbeddingCount()).isZero();
        assertThat(inspection.staleEmbeddingCount()).isZero();
        assertThat(inspection.orphanEmbeddingCount()).isZero();
        assertThat(inspection.invalidVectorCount()).isZero();
        assertThat(inspection.dimensions()).isEqualTo(2);
        assertThat(inspection.denseReady()).isTrue();
    }

    @Test
    void fullyReadyMaterialIsDenseReady() {
        KnowledgeChunk first = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunk second = chunk(2L, PROJECT_ID, MATERIAL_ID, "beta");
        List<KnowledgeChunk> chunks = List.of(first, second);
        stubMaterial(PROJECT_ID, MATERIAL_ID, PROVIDER, MODEL, chunks, List.of(
                readyEmbedding(first, List.of(1.0, 0.0)),
                readyEmbedding(second, List.of(0.0, 1.0))
        ), chunks);

        DenseIndexInspection inspection = service()
                .inspectMaterial(PROJECT_ID, MATERIAL_ID, PROVIDER, MODEL);

        assertThat(inspection.materialId()).isEqualTo(MATERIAL_ID);
        assertThat(inspection.chunkCount()).isEqualTo(2);
        assertThat(inspection.readyCount()).isEqualTo(2);
        assertThat(inspection.denseReady()).isTrue();
    }

    @Test
    void missingEmbeddingIsCounted() {
        KnowledgeChunk first = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunk second = chunk(2L, PROJECT_ID, MATERIAL_ID, "beta");
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(first, second),
                List.of(readyEmbedding(first, List.of(1.0, 0.0))), List.of(first, second));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.readyCount()).isEqualTo(1);
        assertThat(inspection.missingEmbeddingCount()).isEqualTo(1);
        assertThat(inspection.staleEmbeddingCount()).isZero();
        assertThat(inspection.denseReady()).isFalse();
    }

    @Test
    void staleHashIsCountedWithoutAlsoBeingMissing() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "new content");
        KnowledgeChunkEmbedding stale = embedding(
                101L, PROJECT_ID, MATERIAL_ID, 1L, PROVIDER, MODEL,
                "[1.0,0.0]", 2, KnowledgeEmbeddingStore.contentHash("old content")
        );
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(chunk), List.of(stale), List.of(chunk));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.readyCount()).isZero();
        assertThat(inspection.missingEmbeddingCount()).isZero();
        assertThat(inspection.staleEmbeddingCount()).isEqualTo(1);
        assertThat(inspection.denseReady()).isFalse();
    }

    @Test
    void orphanEmbeddingIsCounted() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunkEmbedding healthy = readyEmbedding(chunk, List.of(1.0, 0.0));
        KnowledgeChunkEmbedding orphan = embedding(
                999L, PROJECT_ID, MATERIAL_ID, 999L, PROVIDER, MODEL,
                "[0.0,1.0]", 2, KnowledgeEmbeddingStore.contentHash("orphan")
        );
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(chunk), List.of(healthy, orphan), List.of(chunk));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.readyCount()).isEqualTo(1);
        assertThat(inspection.orphanEmbeddingCount()).isEqualTo(1);
        assertThat(inspection.denseReady()).isFalse();
    }

    @Test
    void orphanCleanupDeletesOnlyRowsWithoutChunks() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunkEmbedding healthy = readyEmbedding(chunk, List.of(1.0, 0.0));
        KnowledgeChunkEmbedding orphan = embedding(
                999L, PROJECT_ID, MATERIAL_ID, 999L, PROVIDER, MODEL,
                "[0.0,1.0]", 2, KnowledgeEmbeddingStore.contentHash("orphan")
        );
        stubCleanup(PROJECT_ID, PROVIDER, MODEL, List.of(healthy, orphan), List.of(chunk));

        long deleted = service().cleanupOrphansForProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(deleted).isEqualTo(1);
        ArgumentCaptor<List<KnowledgeChunkEmbedding>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingRepository).deleteAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(orphan);
    }

    @Test
    void orphanCleanupDoesNotDeleteHealthyRow() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunkEmbedding healthy = readyEmbedding(chunk, List.of(1.0, 0.0));
        stubCleanup(PROJECT_ID, PROVIDER, MODEL, List.of(healthy), List.of(chunk));

        long deleted = service().cleanupOrphansForProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(deleted).isZero();
        verify(embeddingRepository, never()).deleteAll(any());
    }

    @Test
    void providerMismatchIsMissingForCurrentSpace() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        stubProject(PROJECT_ID, "provider-b", MODEL, List.of(chunk), List.of(), List.of(chunk));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, "provider-b", MODEL);

        assertThat(inspection.readyCount()).isZero();
        assertThat(inspection.missingEmbeddingCount()).isEqualTo(1);
        assertThat(inspection.orphanEmbeddingCount()).isZero();
    }

    @Test
    void modelMismatchIsMissingForCurrentSpace() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        stubProject(PROJECT_ID, PROVIDER, "model-2", List.of(chunk), List.of(), List.of(chunk));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, "model-2");

        assertThat(inspection.readyCount()).isZero();
        assertThat(inspection.missingEmbeddingCount()).isEqualTo(1);
        assertThat(inspection.orphanEmbeddingCount()).isZero();
    }

    @Test
    void projectMismatchIsCountedAndNotOrphan() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunkEmbedding wrongProject = embedding(
                101L, 99L, MATERIAL_ID, 1L, PROVIDER, MODEL,
                "[1.0,0.0]", 2, KnowledgeEmbeddingStore.contentHash("alpha")
        );
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(chunk), List.of(wrongProject), List.of(chunk));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.wrongProjectCount()).isEqualTo(1);
        assertThat(inspection.orphanEmbeddingCount()).isZero();
        assertThat(inspection.missingEmbeddingCount()).isZero();
        assertThat(inspection.denseReady()).isFalse();
    }

    @Test
    void materialMismatchIsCounted() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunkEmbedding wrongMaterial = embedding(
                101L, PROJECT_ID, 99L, 1L, PROVIDER, MODEL,
                "[1.0,0.0]", 2, KnowledgeEmbeddingStore.contentHash("alpha")
        );
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(chunk), List.of(wrongMaterial), List.of(chunk));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.wrongMaterialCount()).isEqualTo(1);
        assertThat(inspection.missingEmbeddingCount()).isZero();
        assertThat(inspection.denseReady()).isFalse();
    }

    @Test
    void malformedVectorIsCountedWithoutCrashingInspection() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunkEmbedding malformed = embedding(
                101L, PROJECT_ID, MATERIAL_ID, 1L, PROVIDER, MODEL,
                "not-json", 2, KnowledgeEmbeddingStore.contentHash("alpha")
        );
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(chunk), List.of(malformed), List.of(chunk));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.invalidVectorCount()).isEqualTo(1);
        assertThat(inspection.denseReady()).isFalse();
    }

    @Test
    void dimensionMismatchIsCountedAsInvalidVector() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunkEmbedding mismatch = embedding(
                101L, PROJECT_ID, MATERIAL_ID, 1L, PROVIDER, MODEL,
                "[1.0,0.0]", 3, KnowledgeEmbeddingStore.contentHash("alpha")
        );
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(chunk), List.of(mismatch), List.of(chunk));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.invalidVectorCount()).isEqualTo(1);
        assertThat(inspection.dimensions()).isNull();
    }

    @Test
    void zeroVectorIsCountedAsInvalidVector() {
        KnowledgeChunk chunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunkEmbedding zero = readyEmbedding(chunk, List.of(0.0, 0.0));
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(chunk), List.of(zero), List.of(chunk));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.invalidVectorCount()).isEqualTo(1);
        assertThat(inspection.readyCount()).isZero();
    }

    @Test
    void mixedReadyDimensionsMakeIndexNotReady() {
        KnowledgeChunk first = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunk second = chunk(2L, PROJECT_ID, MATERIAL_ID, "beta");
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(first, second), List.of(
                readyEmbedding(first, List.of(1.0, 0.0)),
                readyEmbedding(second, List.of(1.0, 0.0, 0.0))
        ), List.of(first, second));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.readyCount()).isEqualTo(2);
        assertThat(inspection.dimensionMismatchCount()).isEqualTo(1);
        assertThat(inspection.dimensions()).isNull();
        assertThat(inspection.denseReady()).isFalse();
    }

    @Test
    void noChunksIsNeverDenseReady() {
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(), List.of(), List.of());

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.chunkCount()).isZero();
        assertThat(inspection.embeddingRowCount()).isZero();
        assertThat(inspection.denseReady()).isFalse();
    }

    @Test
    void noEmbeddingsCountsEveryChunkAsMissing() {
        KnowledgeChunk first = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunk second = chunk(2L, PROJECT_ID, MATERIAL_ID, "beta");
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(first, second), List.of(), List.of(first, second));

        DenseIndexInspection inspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(inspection.chunkCount()).isEqualTo(2);
        assertThat(inspection.embeddingRowCount()).isZero();
        assertThat(inspection.missingEmbeddingCount()).isEqualTo(2);
        assertThat(inspection.denseReady()).isFalse();
    }

    @Test
    void materialInspectionAndCleanupStayWithinRequestedScope() {
        KnowledgeChunk requestedChunk = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunk otherMaterialChunk = chunk(2L, PROJECT_ID, 11L, "other material");
        KnowledgeChunk otherProjectChunk = chunk(3L, 99L, MATERIAL_ID, "other project");
        KnowledgeChunkEmbedding requestedOrphan = embedding(
                101L, PROJECT_ID, MATERIAL_ID, 101L, PROVIDER, MODEL,
                "[1.0,0.0]", 2, KnowledgeEmbeddingStore.contentHash("orphan")
        );
        KnowledgeChunkEmbedding otherMaterialOrphan = embedding(
                102L, PROJECT_ID, 11L, 102L, PROVIDER, MODEL,
                "[1.0,0.0]", 2, KnowledgeEmbeddingStore.contentHash("orphan")
        );
        KnowledgeChunkEmbedding otherProjectOrphan = embedding(
                103L, 99L, MATERIAL_ID, 103L, PROVIDER, MODEL,
                "[1.0,0.0]", 2, KnowledgeEmbeddingStore.contentHash("orphan")
        );
        KnowledgeChunkEmbedding otherProviderOrphan = embedding(
                104L, PROJECT_ID, MATERIAL_ID, 104L, "other-provider", MODEL,
                "[1.0,0.0]", 2, KnowledgeEmbeddingStore.contentHash("orphan")
        );
        stubCleanup(PROJECT_ID, PROVIDER, MODEL,
                List.of(requestedOrphan, otherMaterialOrphan, otherProjectOrphan, otherProviderOrphan),
                List.of(requestedChunk, otherMaterialChunk, otherProjectChunk));

        long deleted = service().cleanupOrphansForMaterial(
                PROJECT_ID, MATERIAL_ID, PROVIDER, MODEL
        );

        assertThat(deleted).isEqualTo(1);
        ArgumentCaptor<List<KnowledgeChunkEmbedding>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingRepository).deleteAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(requestedOrphan);
    }

    @Test
    void countsAreDeterministicAcrossRepeatedInspection() {
        KnowledgeChunk first = chunk(1L, PROJECT_ID, MATERIAL_ID, "alpha");
        KnowledgeChunk second = chunk(2L, PROJECT_ID, MATERIAL_ID, "beta");
        KnowledgeChunkEmbedding stale = embedding(
                101L, PROJECT_ID, MATERIAL_ID, 1L, PROVIDER, MODEL,
                "[1.0,0.0]", 2, KnowledgeEmbeddingStore.contentHash("old")
        );
        KnowledgeChunkEmbedding invalid = embedding(
                102L, PROJECT_ID, MATERIAL_ID, 2L, PROVIDER, MODEL,
                "bad", 2, KnowledgeEmbeddingStore.contentHash("beta")
        );
        stubProject(PROJECT_ID, PROVIDER, MODEL, List.of(first, second),
                List.of(stale, invalid), List.of(first, second));

        DenseIndexInspection firstInspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);
        DenseIndexInspection secondInspection = service().inspectProject(PROJECT_ID, PROVIDER, MODEL);

        assertThat(secondInspection).isEqualTo(firstInspection);
    }

    @Test
    void cleanupIsIdempotent() {
        KnowledgeChunkEmbedding orphan = embedding(
                999L, PROJECT_ID, MATERIAL_ID, 999L, PROVIDER, MODEL,
                "[1.0,0.0]", 2, KnowledgeEmbeddingStore.contentHash("orphan")
        );
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, PROVIDER, MODEL
        )).thenReturn(List.of(orphan), List.of());
        when(chunkRepository.findAllById(any())).thenReturn(List.of());

        DenseIndexIntegrityService integrityService = service();

        assertThat(integrityService.cleanupOrphansForProject(PROJECT_ID, PROVIDER, MODEL)).isEqualTo(1);
        assertThat(integrityService.cleanupOrphansForProject(PROJECT_ID, PROVIDER, MODEL)).isZero();
        verify(embeddingRepository).deleteAll(List.of(orphan));
    }

    @Test
    void blankScopeIdentityIsRejected() {
        assertThatThrownBy(() -> service().inspectProject(PROJECT_ID, "  ", MODEL))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("provider is required");
        assertThatThrownBy(() -> service().inspectProject(PROJECT_ID, PROVIDER, "\t"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("model is required");
    }

    private DenseIndexIntegrityService service() {
        return new DenseIndexIntegrityService(chunkRepository, embeddingRepository, vectorCodec);
    }

    private void stubProject(
            long projectId,
            String provider,
            String model,
            List<KnowledgeChunk> chunks,
            List<KnowledgeChunkEmbedding> embeddings,
            List<KnowledgeChunk> associatedChunks
    ) {
        when(chunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(projectId)).thenReturn(chunks);
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                projectId, provider, model
        )).thenReturn(embeddings);
        when(chunkRepository.findAllById(any())).thenReturn(associatedChunks);
    }

    private void stubMaterial(
            long projectId,
            long materialId,
            String provider,
            String model,
            List<KnowledgeChunk> chunks,
            List<KnowledgeChunkEmbedding> embeddings,
            List<KnowledgeChunk> associatedChunks
    ) {
        when(chunkRepository.findByMaterialIdOrderByChunkNoAsc(materialId)).thenReturn(chunks);
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                projectId, provider, model
        )).thenReturn(embeddings);
        when(chunkRepository.findAllById(any())).thenReturn(associatedChunks);
    }

    private void stubCleanup(
            long projectId,
            String provider,
            String model,
            List<KnowledgeChunkEmbedding> embeddings,
            List<KnowledgeChunk> associatedChunks
    ) {
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                projectId, provider, model
        )).thenReturn(embeddings);
        when(chunkRepository.findAllById(any())).thenReturn(associatedChunks);
    }

    private KnowledgeChunk chunk(long id, long projectId, long materialId, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setProjectId(projectId);
        chunk.setMaterialId(materialId);
        chunk.setChunkNo((int) id);
        chunk.setContent(content);
        return chunk;
    }

    private KnowledgeChunkEmbedding readyEmbedding(KnowledgeChunk chunk, List<Double> vector) {
        return embedding(
                chunk.getId() + 100L,
                chunk.getProjectId(),
                chunk.getMaterialId(),
                chunk.getId(),
                PROVIDER,
                MODEL,
                vectorCodec.encode(vector),
                vector.size(),
                KnowledgeEmbeddingStore.contentHash(chunk.getContent())
        );
    }

    private KnowledgeChunkEmbedding embedding(
            long id,
            long projectId,
            long materialId,
            long chunkId,
            String provider,
            String model,
            String vector,
            Integer dimensions,
            String contentHash
    ) {
        KnowledgeChunkEmbedding embedding = new KnowledgeChunkEmbedding();
        embedding.setId(id);
        embedding.setProjectId(projectId);
        embedding.setMaterialId(materialId);
        embedding.setKnowledgeChunkId(chunkId);
        embedding.setProvider(provider);
        embedding.setModel(model);
        embedding.setVector(vector);
        embedding.setDimensions(dimensions);
        embedding.setContentHash(contentHash);
        return embedding;
    }
}
