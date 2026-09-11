package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeOverviewResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeSearchRequest;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeSearchResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeMaterialReadResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/knowledge")
public class KnowledgeController {

    private final KnowledgeSearchService searchService;

    public KnowledgeController(KnowledgeSearchService searchService) {
        this.searchService = searchService;
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

    @GetMapping("/materials/{materialId}/read")
    public ApiResponse<KnowledgeMaterialReadResponse> readMaterial(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "materialId must be greater than 0") Long materialId,
            @RequestParam(required = false) String locator
    ) {
        return ApiResponse.success(searchService.readMaterial(projectId, materialId, locator));
    }
}
