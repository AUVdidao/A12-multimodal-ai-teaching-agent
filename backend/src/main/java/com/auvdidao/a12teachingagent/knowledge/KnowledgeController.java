package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.DenseSearchRequest;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.DenseSearchResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeOverviewResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeSearchRequest;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeSearchResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/knowledge")
public class KnowledgeController {

    private final KnowledgeSearchService searchService;
    private final KnowledgeEmbeddingIndexService embeddingIndexService;
    private final DenseKnowledgeRetrievalService denseRetrievalService;

    public KnowledgeController(
            KnowledgeSearchService searchService,
            KnowledgeEmbeddingIndexService embeddingIndexService,
            DenseKnowledgeRetrievalService denseRetrievalService
    ) {
        this.searchService = searchService;
        this.embeddingIndexService = embeddingIndexService;
        this.denseRetrievalService = denseRetrievalService;
    }

    @GetMapping("/overview")
    public ApiResponse<KnowledgeOverviewResponse> overview(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(searchService.overview(projectId));
    }

    @PostMapping("/search")
    public ApiResponse<KnowledgeSearchResponse> search(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @Valid @RequestBody KnowledgeSearchRequest request
    ) {
        return ApiResponse.success(searchService.search(projectId, request.query(), request.limit()));
    }

    @PostMapping("/dense-index/refresh")
    public ApiResponse<DenseRefreshResult> refreshDenseIndex(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(embeddingIndexService.refreshProject(projectId));
    }

    @PostMapping("/materials/{materialId}/dense-index/refresh")
    public ApiResponse<DenseRefreshResult> refreshMaterialDenseIndex(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "materialId must be greater than 0") Long materialId
    ) {
        return ApiResponse.success(embeddingIndexService.refreshMaterial(projectId, materialId));
    }

    @GetMapping("/dense-index/status")
    public ApiResponse<DenseIndexInspection> denseIndexStatus(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(embeddingIndexService.inspectProject(projectId));
    }

    @PostMapping("/dense-search")
    public ApiResponse<DenseSearchResponse> denseSearch(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @Valid @RequestBody DenseSearchRequest request
    ) {
        return ApiResponse.success(new DenseSearchResponse(
                request.query(),
                denseRetrievalService.search(projectId, request.query(), request.limit()),
                "DENSE_COSINE"
        ));
    }
}
