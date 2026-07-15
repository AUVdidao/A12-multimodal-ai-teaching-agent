package com.auvdidao.a12teachingagent.versioning;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.versioning.dto.ArtifactVersionDtos.ArtifactVersionResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/artifact-versions")
public class ArtifactVersionController {

    private final ArtifactVersionService artifactVersionService;

    public ArtifactVersionController(ArtifactVersionService artifactVersionService) {
        this.artifactVersionService = artifactVersionService;
    }

    @GetMapping
    public ApiResponse<List<ArtifactVersionResponse>> list(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(artifactVersionService.list(projectId));
    }

    @PutMapping("/{versionId}/finalize")
    public ApiResponse<ArtifactVersionResponse> finalizeVersion(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "versionId must be greater than 0") Long versionId
    ) {
        return ApiResponse.success(artifactVersionService.finalizeVersion(projectId, versionId));
    }
}
