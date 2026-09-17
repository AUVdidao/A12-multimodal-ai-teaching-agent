package com.auvdidao.a12teachingagent.lessonforge.material;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeChunkResponse;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.ParseResultResponse;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.PipelineRequest;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.EmbeddingRequest;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.EmbeddingResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/internal/lessonforge/projects/{projectId}/materials/{materialId}")
public class LessonForgeMaterialPipelineController {
    private final LessonForgeMaterialPipelineService service;
    private final LessonForgeEmbeddingService embeddingService;

    public LessonForgeMaterialPipelineController(LessonForgeMaterialPipelineService service, LessonForgeEmbeddingService embeddingService) {
        this.service = service;
        this.embeddingService = embeddingService;
    }

    @PostMapping("/parse")
    public ApiResponse<ParseResultResponse> parse(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long materialId,
            @Valid @RequestBody PipelineRequest request
    ) {
        return ApiResponse.success(service.parse(projectId, materialId, request));
    }

    @PostMapping("/index")
    public ApiResponse<List<KnowledgeChunkResponse>> index(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long materialId,
            @Valid @RequestBody PipelineRequest request
    ) {
        return ApiResponse.success(service.index(projectId, materialId, request));
    }

    @PutMapping("/embeddings")
    public ApiResponse<EmbeddingResponse> embeddings(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long materialId,
            @Valid @RequestBody EmbeddingRequest request
    ) {
        return ApiResponse.success(embeddingService.persist(projectId, materialId, request));
    }
}
