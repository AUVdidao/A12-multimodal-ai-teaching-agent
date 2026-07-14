package com.auvdidao.a12teachingagent.generation;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.ArtifactGenerationRequest;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.ArtifactResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/artifacts")
public class ArtifactController {

    private final GenerationService generationService;

    public ArtifactController(GenerationService generationService) {
        this.generationService = generationService;
    }

    @PostMapping("/generate")
    public ApiResponse<List<ArtifactResponse>> generate(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @Valid @RequestBody ArtifactGenerationRequest request
    ) {
        return ApiResponse.success(generationService.generateArtifacts(projectId, request));
    }

    @GetMapping
    public ApiResponse<List<ArtifactResponse>> list(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(generationService.listArtifacts(projectId));
    }

    @GetMapping("/{artifactId}")
    public ApiResponse<ArtifactResponse> get(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "artifactId must be greater than 0") Long artifactId
    ) {
        return ApiResponse.success(generationService.getArtifact(projectId, artifactId));
    }
}
