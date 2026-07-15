package com.auvdidao.a12teachingagent.revision;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.revision.dto.RevisionDtos.EditRecordResponse;
import com.auvdidao.a12teachingagent.revision.dto.RevisionDtos.RevisionRequest;
import com.auvdidao.a12teachingagent.revision.dto.RevisionDtos.RevisionResponse;
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
@RequestMapping("/api/v1/projects/{projectId}")
public class RevisionController {

    private final RevisionService revisionService;

    public RevisionController(RevisionService revisionService) {
        this.revisionService = revisionService;
    }

    @PostMapping("/artifacts/{artifactId}/revisions")
    public ApiResponse<RevisionResponse> revise(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long artifactId,
            @Valid @RequestBody RevisionRequest request
    ) {
        return ApiResponse.success(revisionService.revise(projectId, artifactId, request));
    }

    @GetMapping("/edit-records")
    public ApiResponse<List<EditRecordResponse>> editRecords(@PathVariable @Positive Long projectId) {
        return ApiResponse.success(revisionService.listEditRecords(projectId));
    }
}
