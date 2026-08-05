package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeChunkResponse;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.ParseResultResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/materials/{materialId}")
public class MaterialParseController {

    private final MaterialParseService parseService;

    public MaterialParseController(MaterialParseService parseService) {
        this.parseService = parseService;
    }

    @PostMapping("/parse")
    public ApiResponse<ParseResultResponse> parse(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "materialId must be greater than 0") Long materialId,
            @RequestParam(defaultValue = "false") boolean forceReparse
    ) {
        return ApiResponse.success(parseService.parse(projectId, materialId, forceReparse));
    }

    @GetMapping("/parse-result")
    public ApiResponse<ParseResultResponse> getResult(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "materialId must be greater than 0") Long materialId
    ) {
        return ApiResponse.success(parseService.getResult(projectId, materialId));
    }

    @PostMapping("/parse/retry")
    public ApiResponse<ParseResultResponse> retry(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "materialId must be greater than 0") Long materialId
    ) {
        return ApiResponse.success(parseService.retry(projectId, materialId));
    }

    @PostMapping("/index")
    public ApiResponse<List<KnowledgeChunkResponse>> index(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "materialId must be greater than 0") Long materialId
    ) {
        return ApiResponse.success(parseService.index(projectId, materialId));
    }
}
