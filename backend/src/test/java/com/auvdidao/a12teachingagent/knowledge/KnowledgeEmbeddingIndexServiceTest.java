package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunkEmbedding;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkEmbeddingRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.embedding.EmbeddedKnowledgeChunk;
import com.auvdidao.a12teachingagent.embedding.EmbeddingException;
import com.auvdidao.a12teachingagent.embedding.EmbeddingFailureKind;
import com.auvdidao.a12teachingagent.embedding.EmbeddingProvider;
import com.auvdidao.a12teachingagent.embedding.EmbeddingProviderDescriptor;
import com.auvdidao.a12teachingagent.embedding.KnowledgeEmbeddingService;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeEmbeddingIndexServiceTest {

    private static final long PROJECT_ID = 7L;
    private static final long MATERIAL_ID = 10L;
    private static final String PROVIDER = "fake-provider";
    private static final String MODEL = "fake-model";

    @Mock private KnowledgeChunkRepository chunkRepository;
    @Mock private KnowledgeChunkEmbeddingRepository embeddingRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private UploadedMaterialRepository materialRepository;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private EmbeddingProvider provider;
    @Mock private KnowledgeEmbeddingService embeddingService;
    @Mock private DenseEmbeddingPersistenceService persistenceService;
    @Mock private DenseIndexIntegrityService integrityService;

    private final VectorCodec vectorCodec = new VectorCodec();

    @Test
    void firstProjectRefreshEmbedsAndReportsReady() {
        List<KnowledgeChunk> chunks = List.of(chunk(1L, MATERIAL_ID, "alpha"), chunk(2L, MATERIAL_ID, "beta"));
        stubProject(chunks);
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, PROVIDER, MODEL)).thenReturn(List.of());
        when(provider.describe()).thenReturn(descriptor());
        when(embeddingService.embedChunks(anyList())).thenReturn(embedded(chunks, 2));
        when(integrityService.inspectProject(PROJECT_ID, PROVIDER, MODEL))
                .thenReturn(inspection(null, 2, 2, 2, true, 2));

        DenseRefreshResult result = service().refreshProject(PROJECT_ID);

        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.embeddedCount()).isEqualTo(2);
        assertThat(result.reusedCount()).isZero();
        assertThat(result.refreshedCount()).isEqualTo(2);
        assertThat(result.denseReady()).isTrue();
        verify(persistenceService).persist(anyList(), anyList(), org.mockito.ArgumentMatchers.eq(PROVIDER), org.mockito.ArgumentMatchers.eq(MODEL));
    }

    @Test
    void readyProjectIsNoOpReuseAndDoesNotCallProvider() {
        List<KnowledgeChunk> chunks = List.of(chunk(1L, MATERIAL_ID, "alpha"), chunk(2L, MATERIAL_ID, "beta"));
        stubProject(chunks);
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, PROVIDER, MODEL)).thenReturn(List.of(row(chunks.get(0), List.of(1.0, 0.0)), row(chunks.get(1), List.of(0.0, 1.0))));
        when(provider.describe()).thenReturn(descriptor());
        when(integrityService.inspectProject(PROJECT_ID, PROVIDER, MODEL))
                .thenReturn(inspection(null, 2, 2, 2, true, 2));

        DenseRefreshResult result = service().refreshProject(PROJECT_ID);

        assertThat(result.reusedCount()).isEqualTo(2);
        assertThat(result.embeddedCount()).isZero();
        verify(embeddingService, never()).embedChunks(anyList());
        verify(persistenceService, never()).persist(anyList(), anyList(), any(), any());
    }

    @Test
    void staleContentOnlyRefreshesOneChunk() {
        KnowledgeChunk first = chunk(1L, MATERIAL_ID, "alpha-new");
        KnowledgeChunk second = chunk(2L, MATERIAL_ID, "beta");
        List<KnowledgeChunk> chunks = List.of(first, second);
        stubProject(chunks);
        KnowledgeChunkEmbedding stale = row(first, List.of(1.0, 0.0));
        stale.setContentHash(KnowledgeEmbeddingStore.contentHash("alpha-old"));
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, PROVIDER, MODEL)).thenReturn(List.of(stale, row(second, List.of(0.0, 1.0))));
        when(provider.describe()).thenReturn(descriptor());
        when(embeddingService.embedChunks(anyList())).thenReturn(embedded(List.of(first), 2));
        when(integrityService.inspectProject(PROJECT_ID, PROVIDER, MODEL))
                .thenReturn(inspection(null, 2, 2, 2, true, 2));

        DenseRefreshResult result = service().refreshProject(PROJECT_ID);

        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.reusedCount()).isEqualTo(1);
        assertThat(result.embeddedCount()).isEqualTo(1);
        assertThat(result.refreshedCount()).isEqualTo(1);
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService).embedChunks(captor.capture());
        assertThat(captor.getValue()).extracting(KnowledgeChunk::getId).containsExactly(1L);
    }

    @Test
    void providerFailureDoesNotPersistAnyNewRows() {
        List<KnowledgeChunk> chunks = List.of(chunk(1L, MATERIAL_ID, "alpha"));
        stubProject(chunks);
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, PROVIDER, MODEL)).thenReturn(List.of());
        when(provider.describe()).thenReturn(descriptor());
        when(embeddingService.embedChunks(anyList())).thenThrow(
                new EmbeddingException(EmbeddingFailureKind.UPSTREAM_FAILURE, "fake failure"));

        assertThatThrownBy(() -> service().refreshProject(PROJECT_ID))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("fake failure");
        verify(persistenceService, never()).persist(anyList(), anyList(), any(), any());
    }

    @Test
    void sixHundredOneChunkProviderFailureDoesNotStartPersistence() {
        List<KnowledgeChunk> chunks = java.util.stream.LongStream.rangeClosed(1, 601)
                .mapToObj(id -> chunk(id, MATERIAL_ID, "content-" + id))
                .toList();
        stubProject(chunks);
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, PROVIDER, MODEL)).thenReturn(List.of());
        when(provider.describe()).thenReturn(descriptor());
        EmbeddingException failure = new EmbeddingException(
                EmbeddingFailureKind.UPSTREAM_FAILURE, "601 chunk provider failure"
        );
        when(embeddingService.embedChunks(anyList())).thenThrow(failure);

        assertThatThrownBy(() -> service().refreshProject(PROJECT_ID))
                .isSameAs(failure);
        verify(embeddingService).embedChunks(org.mockito.ArgumentMatchers.argThat(value -> value.size() == 601));
        verify(persistenceService, never()).persist(anyList(), anyList(), any(), any());
    }

    @Test
    void providerOrModelChangeDoesNotReuseThePreviousSpace() {
        List<KnowledgeChunk> chunks = List.of(chunk(1L, MATERIAL_ID, "alpha"));
        stubProject(chunks);
        EmbeddingProviderDescriptor changed = new EmbeddingProviderDescriptor("new-provider", "new-model", true, true);
        when(provider.describe()).thenReturn(changed);
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, "new-provider", "new-model")).thenReturn(List.of());
        when(embeddingService.embedChunks(anyList())).thenReturn(
                embedded(chunks, "new-provider", "new-model", 2));
        when(integrityService.inspectProject(PROJECT_ID, "new-provider", "new-model"))
                .thenReturn(inspection(null, 1, 1, 1, true, 2));

        DenseRefreshResult result = service().refreshProject(PROJECT_ID);

        assertThat(result.provider()).isEqualTo("new-provider");
        assertThat(result.model()).isEqualTo("new-model");
        verify(embeddingService).embedChunks(anyList());
        verify(persistenceService).persist(anyList(), anyList(), org.mockito.ArgumentMatchers.eq("new-provider"), org.mockito.ArgumentMatchers.eq("new-model"));
    }

    @Test
    void invalidStoredVectorIsReembedded() {
        List<KnowledgeChunk> chunks = List.of(chunk(1L, MATERIAL_ID, "alpha"));
        stubProject(chunks);
        KnowledgeChunkEmbedding invalid = row(chunks.get(0), List.of(1.0, 0.0));
        invalid.setVector("not-json");
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, PROVIDER, MODEL)).thenReturn(List.of(invalid));
        when(provider.describe()).thenReturn(descriptor());
        when(embeddingService.embedChunks(anyList())).thenReturn(embedded(chunks, 2));
        when(integrityService.inspectProject(PROJECT_ID, PROVIDER, MODEL))
                .thenReturn(inspection(null, 1, 1, 1, true, 2));

        DenseRefreshResult result = service().refreshProject(PROJECT_ID);

        assertThat(result.embeddedCount()).isEqualTo(1);
        verify(embeddingService).embedChunks(anyList());
    }

    @Test
    void providerDimensionChangeTriggersFullProjectRefresh() {
        KnowledgeChunk first = chunk(1L, MATERIAL_ID, "alpha");
        KnowledgeChunk second = chunk(2L, MATERIAL_ID, "beta");
        KnowledgeChunk third = chunk(3L, MATERIAL_ID, "gamma-new");
        List<KnowledgeChunk> chunks = List.of(first, second, third);
        stubProject(chunks);
        KnowledgeChunkEmbedding firstRow = row(first, List.of(1.0, 0.0, 0.0, 0.0));
        KnowledgeChunkEmbedding secondRow = row(second, List.of(0.0, 1.0, 0.0, 0.0));
        KnowledgeChunkEmbedding staleThird = row(third, List.of(0.0, 0.0, 1.0, 0.0));
        staleThird.setContentHash(KnowledgeEmbeddingStore.contentHash("gamma-old"));
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, PROVIDER, MODEL)).thenReturn(List.of(firstRow, secondRow, staleThird));
        when(provider.describe()).thenReturn(descriptor());
        when(embeddingService.embedChunks(anyList())).thenReturn(
                embedded(List.of(third), 6), embedded(chunks, 6));
        when(integrityService.inspectProject(PROJECT_ID, PROVIDER, MODEL))
                .thenReturn(inspection(null, 3, 3, 3, true, 6));

        DenseRefreshResult result = service().refreshProject(PROJECT_ID);

        assertThat(result.chunkCount()).isEqualTo(3);
        assertThat(result.embeddedCount()).isEqualTo(3);
        assertThat(result.reusedCount()).isZero();
        assertThat(result.refreshedCount()).isEqualTo(3);
        assertThat(result.dimensions()).isEqualTo(6);
        assertThat(result.denseReady()).isTrue();
        verify(embeddingService, times(2)).embedChunks(anyList());
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService, times(2)).embedChunks(captor.capture());
        assertThat(captor.getAllValues().get(0)).extracting(KnowledgeChunk::getId).containsExactly(3L);
        assertThat(captor.getAllValues().get(1)).extracting(KnowledgeChunk::getId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void materialRefreshDoesNotEmbedAnotherProjectMaterial() {
        KnowledgeChunk requested = chunk(1L, MATERIAL_ID, "requested");
        KnowledgeChunk otherMaterial = chunk(2L, 11L, "other");
        List<KnowledgeChunk> chunks = List.of(requested, otherMaterial);
        stubProject(chunks);
        when(materialRepository.findByIdAndProjectId(MATERIAL_ID, PROJECT_ID)).thenReturn(Optional.of(new UploadedMaterial()));
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, PROVIDER, MODEL)).thenReturn(List.of());
        when(provider.describe()).thenReturn(descriptor());
        when(embeddingService.embedChunks(anyList())).thenReturn(embedded(List.of(requested), 2));
        when(integrityService.inspectMaterial(PROJECT_ID, MATERIAL_ID, PROVIDER, MODEL))
                .thenReturn(inspection(MATERIAL_ID, 1, 1, 1, true, 2));

        DenseRefreshResult result = service().refreshMaterial(PROJECT_ID, MATERIAL_ID);

        assertThat(result.materialId()).isEqualTo(MATERIAL_ID);
        assertThat(result.chunkCount()).isEqualTo(1);
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService).embedChunks(captor.capture());
        assertThat(captor.getValue()).extracting(KnowledgeChunk::getMaterialId).containsExactly(MATERIAL_ID);
    }

    @Test
    void materialDimensionDriftRefreshesTheWholeProjectButReportsRequestedScope() {
        KnowledgeChunk materialAFirst = chunk(1L, MATERIAL_ID, "a-one-new");
        KnowledgeChunk materialASecond = chunk(2L, MATERIAL_ID, "a-two");
        KnowledgeChunk materialB = chunk(3L, 11L, "b-one");
        List<KnowledgeChunk> chunks = List.of(materialAFirst, materialASecond, materialB);
        stubProject(chunks);
        when(materialRepository.findByIdAndProjectId(MATERIAL_ID, PROJECT_ID)).thenReturn(Optional.of(new UploadedMaterial()));
        KnowledgeChunkEmbedding stale = row(materialAFirst, List.of(1.0, 0.0, 0.0, 0.0));
        stale.setContentHash(KnowledgeEmbeddingStore.contentHash("a-one-old"));
        when(embeddingRepository.findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                PROJECT_ID, PROVIDER, MODEL)).thenReturn(List.of(
                stale,
                row(materialASecond, List.of(0.0, 1.0, 0.0, 0.0)),
                row(materialB, List.of(0.0, 0.0, 1.0, 0.0))
        ));
        when(provider.describe()).thenReturn(descriptor());
        when(embeddingService.embedChunks(anyList())).thenReturn(
                embedded(List.of(materialAFirst), 6), embedded(chunks, 6));
        when(integrityService.inspectMaterial(PROJECT_ID, MATERIAL_ID, PROVIDER, MODEL))
                .thenReturn(inspection(MATERIAL_ID, 2, 2, 2, true, 6));

        DenseRefreshResult result = service().refreshMaterial(PROJECT_ID, MATERIAL_ID);

        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.embeddedCount()).isEqualTo(3);
        assertThat(result.reusedCount()).isZero();
        assertThat(result.refreshedCount()).isEqualTo(2);
        assertThat(result.dimensions()).isEqualTo(6);
        assertThat(result.denseReady()).isTrue();
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService, times(2)).embedChunks(captor.capture());
        assertThat(captor.getAllValues().get(1)).extracting(KnowledgeChunk::getId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void zeroChunkProjectRefreshFailsClosedWithoutProviderOrPersistence() {
        stubProject(List.of());
        when(provider.describe()).thenReturn(descriptor());

        assertThatThrownBy(() -> service().refreshProject(PROJECT_ID))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ConflictException.class)
                .hasMessageContaining("project has no knowledge chunks");
        verify(embeddingService, never()).embedChunks(anyList());
        verify(persistenceService, never()).persist(anyList(), anyList(), any(), any());
    }

    @Test
    void zeroChunkMaterialRefreshFailsClosedWithoutProviderOrPersistence() {
        stubProject(List.of());
        when(materialRepository.findByIdAndProjectId(MATERIAL_ID, PROJECT_ID)).thenReturn(Optional.of(new UploadedMaterial()));
        when(provider.describe()).thenReturn(descriptor());

        assertThatThrownBy(() -> service().refreshMaterial(PROJECT_ID, MATERIAL_ID))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ConflictException.class)
                .hasMessageContaining("material has no knowledge chunks");
        verify(embeddingService, never()).embedChunks(anyList());
        verify(persistenceService, never()).persist(anyList(), anyList(), any(), any());
    }

    @Test
    void zeroChunkProjectStatusRemainsNotReady() {
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(provider.describe()).thenReturn(descriptor());
        when(integrityService.inspectProject(PROJECT_ID, PROVIDER, MODEL))
                .thenReturn(inspection(null, 0, 0, 0, false, null));

        DenseIndexInspection result = service().inspectProject(PROJECT_ID);

        assertThat(result.chunkCount()).isZero();
        assertThat(result.denseReady()).isFalse();
    }

    private KnowledgeEmbeddingIndexService service() {
        return new KnowledgeEmbeddingIndexService(
                chunkRepository, embeddingRepository, projectRepository, materialRepository,
                projectAccessService, provider, embeddingService,
                persistenceService, integrityService, vectorCodec
        );
    }

    private void stubProject(List<KnowledgeChunk> chunks) {
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(chunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(PROJECT_ID)).thenReturn(chunks);
    }

    private EmbeddingProviderDescriptor descriptor() {
        return new EmbeddingProviderDescriptor(PROVIDER, MODEL, true, true);
    }

    private DenseIndexInspection inspection(Long materialId, long chunks, long rows, long ready, boolean denseReady, Integer dimensions) {
        return new DenseIndexInspection(
                PROJECT_ID, materialId, PROVIDER, MODEL, chunks, rows, ready,
                0, 0, 0, 0, 0, 0, 0, dimensions, denseReady
        );
    }

    private List<EmbeddedKnowledgeChunk> embedded(List<KnowledgeChunk> chunks, int dimensions) {
        return embedded(chunks, PROVIDER, MODEL, dimensions);
    }

    private List<EmbeddedKnowledgeChunk> embedded(
            List<KnowledgeChunk> chunks,
            String provider,
            String model,
            int dimensions
    ) {
        return chunks.stream().map(chunk -> new EmbeddedKnowledgeChunk(
                chunk.getId(), chunk.getProjectId(), chunk.getMaterialId(), chunk.getChunkNo(),
                chunk.getSourceFilename(), provider, model, dimensions,
                vector(chunk.getId(), dimensions)
        )).toList();
    }

    private KnowledgeChunk chunk(long id, long materialId, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setProjectId(PROJECT_ID);
        chunk.setMaterialId(materialId);
        chunk.setChunkNo((int) id);
        chunk.setContent(content);
        chunk.setSourceFilename("source.pdf");
        return chunk;
    }

    private KnowledgeChunkEmbedding row(KnowledgeChunk chunk, List<Double> vector) {
        KnowledgeChunkEmbedding row = new KnowledgeChunkEmbedding();
        row.setProjectId(chunk.getProjectId());
        row.setMaterialId(chunk.getMaterialId());
        row.setKnowledgeChunkId(chunk.getId());
        row.setProvider(PROVIDER);
        row.setModel(MODEL);
        row.setDimensions(vector.size());
        row.setVector(vectorCodec.encode(vector));
        row.setContentHash(KnowledgeEmbeddingStore.contentHash(chunk.getContent()));
        return row;
    }

    private List<Double> vector(Long id, int dimensions) {
        java.util.ArrayList<Double> vector = new java.util.ArrayList<>();
        vector.add(1.0);
        while (vector.size() < dimensions) {
            vector.add(0.0);
        }
        return List.copyOf(vector);
    }
}
