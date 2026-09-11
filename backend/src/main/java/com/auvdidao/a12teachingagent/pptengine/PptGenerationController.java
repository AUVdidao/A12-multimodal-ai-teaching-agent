package com.auvdidao.a12teachingagent.pptengine;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.pptengine.PptGenerationDtos.CreateGenerationJobRequest;
import com.auvdidao.a12teachingagent.pptengine.PptGenerationDtos.GenerationJobResponse;
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
@RequestMapping("/api/projects/{projectId}/ppt-generation/jobs")
public class PptGenerationController {
    private final PptGenerationService service;

    public PptGenerationController(PptGenerationService service) { this.service = service; }

    @PostMapping
    public ApiResponse<GenerationJobResponse> start(
            @PathVariable @Positive Long projectId,
            @Valid @RequestBody CreateGenerationJobRequest request
    ) {
        return ApiResponse.success(service.start(projectId, request));
    }

    @GetMapping("/{jobId}")
    public ApiResponse<GenerationJobResponse> get(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long jobId
    ) {
        return ApiResponse.success(service.get(projectId, jobId));
    }
}
