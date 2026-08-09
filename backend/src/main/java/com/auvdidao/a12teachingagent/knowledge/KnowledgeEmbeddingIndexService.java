package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunkEmbedding;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkEmbeddingRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.embedding.EmbeddedKnowledgeChunk;
import com.auvdidao.a12teachingagent.embedding.EmbeddingException;
import com.auvdidao.a12teachingagent.embedding.EmbeddingFailureKind;
import com.auvdidao.a12teachingagent.embedding.EmbeddingProvider;
import com.auvdidao.a12teachingagent.embedding.EmbeddingProviderDescriptor;
import com.auvdidao.a12teachingagent.embedding.KnowledgeEmbeddingService;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class KnowledgeEmbeddingIndexService {

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeChunkEmbeddingRepository embeddingRepository;
    private final ProjectRepository projectRepository;
    private final UploadedMaterialRepository materialRepository;
    private final ProjectAccessService projectAccessService;
    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeEmbeddingService embeddingService;
    private final DenseEmbeddingPersistenceService persistenceService;
    private final DenseIndexIntegrityService integrityService;
    private final VectorCodec vectorCodec;

    public KnowledgeEmbeddingIndexService(
            KnowledgeChunkRepository chunkRepository,
            KnowledgeChunkEmbeddingRepository embeddingRepository,
            ProjectRepository projectRepository,
            UploadedMaterialRepository materialRepository,
            ProjectAccessService projectAccessService,
            EmbeddingProvider embeddingProvider,
            KnowledgeEmbeddingService embeddingService,
            DenseEmbeddingPersistenceService persistenceService,
            DenseIndexIntegrityService integrityService,
            VectorCodec vectorCodec
    ) {
        this.chunkRepository = chunkRepository;
        this.embeddingRepository = embeddingRepository;
        this.projectRepository = projectRepository;
        this.materialRepository = materialRepository;
        this.projectAccessService = projectAccessService;
        this.embeddingProvider = embeddingProvider;
        this.embeddingService = embeddingService;
        this.persistenceService = persistenceService;
        this.integrityService = integrityService;
        this.vectorCodec = vectorCodec;
    }

    public DenseRefreshResult refreshProject(Long projectId) {
        EmbeddingProviderDescriptor descriptor = requireConfiguredProvider();
        requireProject(projectId);
        return refresh(projectId, null, descriptor);
    }

    public DenseRefreshResult refreshMaterial(Long projectId, Long materialId) {
        EmbeddingProviderDescriptor descriptor = requireConfiguredProvider();
        requireProject(projectId);
        if (materialId == null || materialId <= 0) {
            throw new ConflictException("materialId must be greater than 0");
        }
        materialRepository.findByIdAndProjectId(materialId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Material not found in project: " + materialId));
        return refresh(projectId, materialId, descriptor);
    }

    public DenseIndexInspection inspectProject(Long projectId) {
        requireProject(projectId);
        EmbeddingProviderDescriptor descriptor = describeProvider();
        if (!hasIdentity(descriptor.provider()) || !hasIdentity(descriptor.model())) {
            long chunkCount = chunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(projectId).size();
            return notReadyInspection(projectId, descriptor, chunkCount);
        }
        return integrityService.inspectProject(projectId, descriptor.provider(), descriptor.model());
    }

    private DenseRefreshResult refresh(
            Long projectId,
            Long materialId,
            EmbeddingProviderDescriptor descriptor
    ) {
        List<KnowledgeChunk> projectChunks = orderedProjectChunks(projectId);
        List<DenseChunkSnapshot> projectSnapshot = snapshot(projectChunks);
        List<DenseChunkSnapshot> scopeSnapshot = materialId == null
                ? projectSnapshot
                : projectSnapshot.stream()
                .filter(value -> Objects.equals(materialId, value.chunk().getMaterialId()))
                .toList();

        if (scopeSnapshot.isEmpty()) {
            String scope = materialId == null ? "project" : "material";
            throw new ConflictException(
                    "Dense index cannot be refreshed because the " + scope + " has no knowledge chunks"
            );
        }

        Map<Long, KnowledgeChunkEmbedding> currentRows = currentRows(projectId, descriptor);
        Set<Integer> reusableDimensions = new HashSet<>();
        Map<Long, Boolean> reusable = new HashMap<>();
        for (DenseChunkSnapshot value : projectSnapshot) {
            KnowledgeChunkEmbedding row = currentRows.get(value.chunk().getId());
            boolean isReusable = reusable(row, value.chunk(), descriptor);
            reusable.put(value.chunk().getId(), isReusable);
            if (isReusable) {
                reusableDimensions.add(row.getDimensions());
            }
        }

        boolean dimensionDrift = reusableDimensions.size() > 1;
        boolean fullProjectRefresh = dimensionDrift;
        List<DenseChunkSnapshot> pendingSnapshot = dimensionDrift
                ? projectSnapshot
                : scopeSnapshot.stream()
                .filter(value -> !Boolean.TRUE.equals(reusable.get(value.chunk().getId())))
                .toList();

        if (pendingSnapshot.isEmpty()) {
            cleanup(projectId, materialId, descriptor, false);
            DenseIndexInspection inspection = inspect(projectId, materialId, descriptor);
            if (!inspection.denseReady()) {
                throw notReady(inspection);
            }
            return result(
                    projectId,
                    materialId,
                    descriptor,
                    inspection,
                    0,
                    scopeSnapshot.size(),
                    0
            );
        }

        List<EmbeddedKnowledgeChunk> embedded = embed(pendingSnapshot);
        validateProviderResult(embedded, descriptor, pendingSnapshot);
        Integer expectedDimension = reusableDimensions.size() == 1
                ? reusableDimensions.iterator().next()
                : null;
        if (expectedDimension != null && embedded.stream().anyMatch(value -> value.dimensions() != expectedDimension)) {
            fullProjectRefresh = true;
            pendingSnapshot = projectSnapshot;
            embedded = embed(pendingSnapshot);
            validateProviderResult(embedded, descriptor, pendingSnapshot);
        }

        persistenceService.persist(projectSnapshot, embedded, descriptor.provider(), descriptor.model());
        cleanup(projectId, materialId, descriptor, fullProjectRefresh);
        DenseIndexInspection inspection = inspect(projectId, materialId, descriptor);
        if (!inspection.denseReady()) {
            throw notReady(inspection);
        }
        long reusedCount = fullProjectRefresh
                ? 0
                : scopeSnapshot.stream()
                .filter(value -> Boolean.TRUE.equals(reusable.get(value.chunk().getId())))
                .count();
        long refreshedCount = fullProjectRefresh ? scopeSnapshot.size() : embedded.size();
        return result(projectId, materialId, descriptor, inspection, embedded.size(), reusedCount, refreshedCount);
    }

    private List<KnowledgeChunk> orderedProjectChunks(Long projectId) {
        List<KnowledgeChunk> chunks = chunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(projectId);
        return (chunks == null ? List.<KnowledgeChunk>of() : chunks).stream()
                .sorted(Comparator.comparing(KnowledgeChunk::getMaterialId, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(KnowledgeChunk::getChunkNo, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(KnowledgeChunk::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private List<DenseChunkSnapshot> snapshot(List<KnowledgeChunk> chunks) {
        return chunks.stream().map(chunk -> {
            if (chunk == null || chunk.getId() == null || chunk.getProjectId() == null
                    || chunk.getMaterialId() == null || chunk.getChunkNo() == null
                    || chunk.getContent() == null || chunk.getContent().isBlank()) {
                throw new ConflictException("Knowledge chunk identity/content is invalid for dense refresh");
            }
            return new DenseChunkSnapshot(chunk, KnowledgeEmbeddingStore.contentHash(chunk.getContent()));
        }).toList();
    }

    private Map<Long, KnowledgeChunkEmbedding> currentRows(
            Long projectId,
            EmbeddingProviderDescriptor descriptor
    ) {
        List<KnowledgeChunkEmbedding> rows = embeddingRepository
                .findAllByProjectIdAndProviderAndModelOrderByKnowledgeChunkIdAsc(
                        projectId, descriptor.provider(), descriptor.model()
                );
        Map<Long, KnowledgeChunkEmbedding> result = new HashMap<>();
        if (rows != null) {
            for (KnowledgeChunkEmbedding row : rows) {
                if (row != null && row.getKnowledgeChunkId() != null) {
                    result.putIfAbsent(row.getKnowledgeChunkId(), row);
                }
            }
        }
        return result;
    }

    private boolean reusable(
            KnowledgeChunkEmbedding row,
            KnowledgeChunk chunk,
            EmbeddingProviderDescriptor descriptor
    ) {
        if (row == null
                || !Objects.equals(chunk.getId(), row.getKnowledgeChunkId())
                || !Objects.equals(chunk.getProjectId(), row.getProjectId())
                || !Objects.equals(chunk.getMaterialId(), row.getMaterialId())
                || !Objects.equals(descriptor.provider(), row.getProvider())
                || !Objects.equals(descriptor.model(), row.getModel())
                || !Objects.equals(KnowledgeEmbeddingStore.contentHash(chunk.getContent()), row.getContentHash())
                || row.getDimensions() == null
                || row.getDimensions() <= 0) {
            return false;
        }
        try {
            List<Double> vector = vectorCodec.decode(row.getVector());
            return vector.size() == row.getDimensions()
                    && vector.stream().allMatch(value -> value != null && Double.isFinite(value))
                    && norm(vector) != 0;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private List<EmbeddedKnowledgeChunk> embed(List<DenseChunkSnapshot> pending) {
        return embeddingService.embedChunks(pending.stream().map(DenseChunkSnapshot::chunk).toList());
    }

    private void validateProviderResult(
            List<EmbeddedKnowledgeChunk> embedded,
            EmbeddingProviderDescriptor descriptor,
            List<DenseChunkSnapshot> expected
    ) {
        if (embedded == null || embedded.size() != expected.size()) {
            throw invalidResponse("Embedding provider result count does not match refresh input");
        }
        Set<Long> expectedIds = expected.stream().map(value -> value.chunk().getId()).collect(java.util.stream.Collectors.toSet());
        Set<Long> actualIds = new HashSet<>();
        for (EmbeddedKnowledgeChunk value : embedded) {
            if (value == null || !Objects.equals(value.provider(), descriptor.provider())
                    || !Objects.equals(value.model(), descriptor.model())
                    || !expectedIds.contains(value.chunkId()) || !actualIds.add(value.chunkId())
                    || value.dimensions() <= 0 || value.vector() == null
                    || value.vector().size() != value.dimensions()
                    || value.vector().stream().anyMatch(number -> number == null || !Double.isFinite(number))
                    || norm(value.vector()) == 0) {
                throw invalidResponse("Embedding provider result does not match the configured space");
            }
        }
    }

    private DenseIndexInspection inspect(
            Long projectId,
            Long materialId,
            EmbeddingProviderDescriptor descriptor
    ) {
        return materialId == null
                ? integrityService.inspectProject(projectId, descriptor.provider(), descriptor.model())
                : integrityService.inspectMaterial(projectId, materialId, descriptor.provider(), descriptor.model());
    }

    private void cleanup(
            Long projectId,
            Long materialId,
            EmbeddingProviderDescriptor descriptor,
            boolean fullProjectRefresh
    ) {
        if (fullProjectRefresh || materialId == null) {
            integrityService.cleanupOrphansForProject(projectId, descriptor.provider(), descriptor.model());
        } else {
            integrityService.cleanupOrphansForMaterial(
                    projectId, materialId, descriptor.provider(), descriptor.model()
            );
        }
    }

    private DenseRefreshResult result(
            Long projectId,
            Long materialId,
            EmbeddingProviderDescriptor descriptor,
            DenseIndexInspection inspection,
            long embeddedCount,
            long reusedCount,
            long refreshedCount
    ) {
        return new DenseRefreshResult(
                projectId,
                materialId,
                descriptor.provider(),
                descriptor.model(),
                inspection.chunkCount(),
                embeddedCount,
                reusedCount,
                refreshedCount,
                inspection.dimensions(),
                inspection.denseReady()
        );
    }

    private EmbeddingProviderDescriptor requireConfiguredProvider() {
        EmbeddingProviderDescriptor descriptor = describeProvider();
        if (!descriptor.enabled() || !descriptor.configured()) {
            throw new EmbeddingException(
                    EmbeddingFailureKind.NOT_CONFIGURED,
                    "Embedding provider is not configured: provider=" + descriptor.provider()
                            + ", model=" + descriptor.model()
            );
        }
        if (!hasIdentity(descriptor.provider()) || !hasIdentity(descriptor.model())) {
            throw invalidResponse("Embedding provider descriptor has no provider/model identity");
        }
        return descriptor;
    }

    private EmbeddingProviderDescriptor describeProvider() {
        EmbeddingProviderDescriptor descriptor = embeddingProvider.describe();
        if (descriptor == null) {
            throw invalidResponse("Embedding provider descriptor is missing");
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

    private DenseIndexInspection notReadyInspection(
            Long projectId,
            EmbeddingProviderDescriptor descriptor,
            long chunkCount
    ) {
        return new DenseIndexInspection(
                projectId, null, descriptor.provider(), descriptor.model(), chunkCount, 0,
                0, chunkCount, 0, 0, 0, 0, 0, 0, null, false
        );
    }

    private ConflictException notReady(DenseIndexInspection inspection) {
        return new ConflictException(
                "Dense index not ready: missing=" + inspection.missingEmbeddingCount()
                        + ", stale=" + inspection.staleEmbeddingCount()
                        + ", invalid=" + inspection.invalidVectorCount()
                        + ", dimensions=" + inspection.dimensionMismatchCount()
        );
    }

    private static EmbeddingException invalidResponse(String message) {
        return new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE, message);
    }

    private static boolean hasIdentity(String value) {
        return value != null && !value.isBlank();
    }

    private static double norm(List<Double> vector) {
        double squared = 0;
        for (double value : vector) {
            squared += value * value;
        }
        double norm = Math.sqrt(squared);
        if (!Double.isFinite(norm)) {
            throw invalidResponse("Embedding provider returned a non-finite vector");
        }
        return norm;
    }
}
