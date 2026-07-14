package com.auvdidao.a12teachingagent.generation;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.GenerationPlanResponse;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.GenerationPlanUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/generation-plans")
public class GenerationPlanController {

    private final GenerationService generationService;

    public GenerationPlanController(GenerationService generationService) {
        this.generationService = generationService;
    }

    @PostMapping
    public ApiResponse<GenerationPlanResponse> create(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(generationService.createPlan(projectId));
    }

    @GetMapping("/latest")
    public ApiResponse<GenerationPlanResponse> latest(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(generationService.latestPlan(projectId));
    }

    @PutMapping("/{planId}")
    public ApiResponse<GenerationPlanResponse> update(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "planId must be greater than 0") Long planId,
            @Valid @RequestBody GenerationPlanUpdateRequest request
    ) {
        return ApiResponse.success(generationService.updatePlan(projectId, planId, request));
    }

    @PostMapping("/{planId}/confirm")
    public ApiResponse<GenerationPlanResponse> confirm(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "planId must be greater than 0") Long planId
    ) {
        return ApiResponse.success(generationService.confirmPlan(projectId, planId));
    }
}
