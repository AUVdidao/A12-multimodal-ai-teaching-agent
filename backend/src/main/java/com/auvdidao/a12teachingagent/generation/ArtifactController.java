package com.auvdidao.a12teachingagent.generation;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.ArtifactGenerationRequest;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.ArtifactResponse;
import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.pptskill.harness.PptHarnessGenerationService;
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
    private final PptGeneratorProperties pptGeneratorProperties;
    private final PptHarnessGenerationService pptHarnessGenerationService;

    public ArtifactController(
            GenerationService generationService,
            PptGeneratorProperties pptGeneratorProperties,
            PptHarnessGenerationService pptHarnessGenerationService
    ) {
        this.generationService = generationService;
        this.pptGeneratorProperties = pptGeneratorProperties;
        this.pptHarnessGenerationService = pptHarnessGenerationService;
    }

    @PostMapping("/generate")
    public ApiResponse<?> generate(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @RequestBody(required = false) ArtifactGenerationRequest request
    ) {
        if (pptGeneratorProperties.isHarnessEnabled()) {
            return ApiResponse.success(pptHarnessGenerationService.start(projectId));
        }
        if ("PRESENTATION_SKILL".equalsIgnoreCase(pptGeneratorProperties.getProvider())) {
            return ApiResponse.success(generationService.generatePresentationSkillPpt(projectId));
        }
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
