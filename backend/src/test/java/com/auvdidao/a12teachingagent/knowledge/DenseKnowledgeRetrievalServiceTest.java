package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.embedding.EmbeddedQuery;
import com.auvdidao.a12teachingagent.embedding.EmbeddingProvider;
import com.auvdidao.a12teachingagent.embedding.EmbeddingProviderDescriptor;
import com.auvdidao.a12teachingagent.embedding.KnowledgeEmbeddingService;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class DenseKnowledgeRetrievalServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private EmbeddingProvider provider;
    @Mock private KnowledgeEmbeddingService embeddingService;
    @Mock private DenseIndexIntegrityService integrityService;
    @Mock private DenseKnowledgeSearchService denseSearchService;

    @Test
    void readyIndexEmbedsQueryAndPreservesDenseHitTraceability() {
        when(projectRepository.existsById(7L)).thenReturn(true);
        when(provider.describe()).thenReturn(new EmbeddingProviderDescriptor("fake-provider", "fake-model", true, true));
        when(integrityService.inspectProject(7L, "fake-provider", "fake-model"))
                .thenReturn(new DenseIndexInspection(7L, null, "fake-provider", "fake-model", 1, 1, 1,
                        0, 0, 0, 0, 0, 0, 0, 2, true));
        when(embeddingService.embedQuery("what is retrieval?"))
                .thenReturn(new EmbeddedQuery("fake-provider", "fake-model", 2, List.of(1.0, 0.0)));
        DenseKnowledgeHit hit = new DenseKnowledgeHit(11L, 7L, 10L, 1, "RAG", "retrieval content", "source.pdf", 0.98);
        when(denseSearchService.search(any(DenseSearchQuery.class))).thenReturn(List.of(hit));

        List<DenseKnowledgeHit> result = service().search(7L, "what is retrieval?", 5);

        assertThat(result).containsExactly(hit);
        assertThat(result.get(0).chunkId()).isEqualTo(11L);
        assertThat(result.get(0).materialId()).isEqualTo(10L);
        assertThat(result.get(0).chunkNo()).isEqualTo(1);
    }

    @Test
    void retrievalIsGatedWhenIndexIsNotReady() {
        when(projectRepository.existsById(7L)).thenReturn(true);
        when(provider.describe()).thenReturn(new EmbeddingProviderDescriptor("fake-provider", "fake-model", true, true));
        when(integrityService.inspectProject(7L, "fake-provider", "fake-model"))
                .thenReturn(new DenseIndexInspection(7L, null, "fake-provider", "fake-model", 1, 0, 0,
                        1, 0, 0, 0, 0, 0, 0, null, false));

        assertThatThrownBy(() -> service().search(7L, "query", 5))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Dense index not ready");
        verify(embeddingService, never()).embedQuery("query");
        verify(denseSearchService, never()).search(any(DenseSearchQuery.class));
    }

    private DenseKnowledgeRetrievalService service() {
        return new DenseKnowledgeRetrievalService(
                projectRepository, projectAccessService, provider, embeddingService,
                integrityService, denseSearchService
        );
    }
}
