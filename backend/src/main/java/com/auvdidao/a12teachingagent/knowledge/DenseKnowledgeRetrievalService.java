package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.embedding.EmbeddedQuery;
import com.auvdidao.a12teachingagent.embedding.EmbeddingException;
import com.auvdidao.a12teachingagent.embedding.EmbeddingFailureKind;
import com.auvdidao.a12teachingagent.embedding.EmbeddingProvider;
import com.auvdidao.a12teachingagent.embedding.EmbeddingProviderDescriptor;
import com.auvdidao.a12teachingagent.embedding.KnowledgeEmbeddingService;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class DenseKnowledgeRetrievalService {

    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;
    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeEmbeddingService embeddingService;
    private final DenseIndexIntegrityService integrityService;
    private final DenseKnowledgeSearchService denseSearchService;

    public DenseKnowledgeRetrievalService(
            ProjectRepository projectRepository,
            ProjectAccessService projectAccessService,
            EmbeddingProvider embeddingProvider,
            KnowledgeEmbeddingService embeddingService,
            DenseIndexIntegrityService integrityService,
            DenseKnowledgeSearchService denseSearchService
    ) {
        this.projectRepository = projectRepository;
        this.projectAccessService = projectAccessService;
        this.embeddingProvider = embeddingProvider;
        this.embeddingService = embeddingService;
        this.integrityService = integrityService;
        this.denseSearchService = denseSearchService;
    }

    public List<DenseKnowledgeHit> search(Long projectId, String query, Integer limit) {
        requireProject(projectId);
        EmbeddingProviderDescriptor descriptor = requireConfiguredProvider();
        DenseIndexInspection inspection = integrityService.inspectProject(
                projectId, descriptor.provider(), descriptor.model()
        );
        if (!inspection.denseReady()) {
            throw new ConflictException("Dense index not ready");
        }

        EmbeddedQuery embedded = embeddingService.embedQuery(query);
        if (!Objects.equals(descriptor.provider(), embedded.provider())
                || !Objects.equals(descriptor.model(), embedded.model())
                || embedded.dimensions() <= 0
                || embedded.vector() == null
                || embedded.vector().size() != embedded.dimensions()) {
            throw new EmbeddingException(
                    EmbeddingFailureKind.INVALID_RESPONSE,
                    "Query embedding does not match the configured dense index"
            );
        }
        return denseSearchService.search(new DenseSearchQuery(
                projectId,
                embedded.provider(),
                embedded.model(),
                embedded.vector(),
                limit
        ));
    }

    private EmbeddingProviderDescriptor requireConfiguredProvider() {
        EmbeddingProviderDescriptor descriptor = embeddingProvider.describe();
        if (descriptor == null || !descriptor.enabled() || !descriptor.configured()) {
            throw new EmbeddingException(
                    EmbeddingFailureKind.NOT_CONFIGURED,
                    "Embedding provider is not configured"
            );
        }
        if (descriptor.provider().isBlank() || descriptor.model().isBlank()) {
            throw new EmbeddingException(
                    EmbeddingFailureKind.INVALID_RESPONSE,
                    "Embedding provider descriptor has no provider/model identity"
            );
        }
        return descriptor;
    }

    private void requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new ConflictException("projectId must be greater than 0");
        }
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
        projectAccessService.requireAccess(projectId);
    }
}
