package com.auvdidao.a12teachingagent.summary;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.summary.dto.RequirementSummaryDtos.RequirementSummaryResponse;
import com.auvdidao.a12teachingagent.summary.dto.RequirementSummaryDtos.RequirementSummaryUpdateRequest;
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
@RequestMapping("/api/projects/{projectId}/requirement-summaries")
public class RequirementSummaryController {

    private final RequirementSummaryService requirementSummaryService;

    public RequirementSummaryController(RequirementSummaryService requirementSummaryService) {
        this.requirementSummaryService = requirementSummaryService;
    }

    @PostMapping("/generate")
    public ApiResponse<RequirementSummaryResponse> generate(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(requirementSummaryService.generate(projectId));
    }

    @GetMapping("/latest")
    public ApiResponse<RequirementSummaryResponse> latest(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(requirementSummaryService.latest(projectId));
    }

    @PutMapping("/{summaryId}")
    public ApiResponse<RequirementSummaryResponse> update(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "summaryId must be greater than 0") Long summaryId,
            @Valid @RequestBody RequirementSummaryUpdateRequest request
    ) {
        return ApiResponse.success(requirementSummaryService.update(projectId, summaryId, request));
    }

    @PostMapping("/{summaryId}/confirm")
    public ApiResponse<RequirementSummaryResponse> confirm(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "summaryId must be greater than 0") Long summaryId
    ) {
        return ApiResponse.success(requirementSummaryService.confirm(projectId, summaryId));
    }
}
