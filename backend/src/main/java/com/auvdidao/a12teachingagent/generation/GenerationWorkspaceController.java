package com.auvdidao.a12teachingagent.generation;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.GenerationWorkspaceResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/generation")
public class GenerationWorkspaceController {

    private final GenerationService generationService;

    public GenerationWorkspaceController(GenerationService generationService) {
        this.generationService = generationService;
    }

    @GetMapping("/workspace")
    public ApiResponse<GenerationWorkspaceResponse> workspace(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(generationService.workspace(projectId));
    }
}
