package com.auvdidao.a12teachingagent.specification;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ChecksumActionRequest;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.HistoryResponse;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ProposalRequest;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ReturnToDraftRequest;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SpecificationResponse;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SpecificationWriteRequest;
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
@RequestMapping("/api/v1/projects/{projectId}/ppt-specifications")
public class PptSpecificationController {

    private final PptSpecificationService service;

    public PptSpecificationController(PptSpecificationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<HistoryResponse> history(@PathVariable @Positive Long projectId) {
        return ApiResponse.success(service.history(projectId));
    }

    @GetMapping("/latest")
    public ApiResponse<SpecificationResponse> latest(@PathVariable @Positive Long projectId) {
        return ApiResponse.success(service.latest(projectId));
    }

    @PostMapping
    public ApiResponse<SpecificationResponse> create(
            @PathVariable @Positive Long projectId,
            @Valid @RequestBody SpecificationWriteRequest request
    ) {
        return ApiResponse.success(service.createInitialDraft(projectId, request));
    }

    /** Planning boundary: a proposal can only create a new version, never mutate the current DRAFT. */
    @PostMapping("/proposals")
    public ApiResponse<SpecificationResponse> proposal(
            @PathVariable @Positive Long projectId,
            @Valid @RequestBody ProposalRequest request
    ) {
        return ApiResponse.success(service.createPlanningProposal(projectId, request));
    }

    @GetMapping("/{versionId}")
    public ApiResponse<SpecificationResponse> get(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long versionId
    ) {
        return ApiResponse.success(service.get(projectId, versionId));
    }

    @PutMapping("/{versionId}")
    public ApiResponse<SpecificationResponse> update(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long versionId,
            @Valid @RequestBody SpecificationWriteRequest request
    ) {
        return ApiResponse.success(service.updateDraft(projectId, versionId, request));
    }

    @PostMapping("/{versionId}/submit-review")
    public ApiResponse<SpecificationResponse> submitReview(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long versionId,
            @Valid @RequestBody ChecksumActionRequest request
    ) {
        return ApiResponse.success(service.submitReview(projectId, versionId, request.expectedChecksum()));
    }

    @PostMapping("/{versionId}/return-to-draft")
    public ApiResponse<SpecificationResponse> returnToDraft(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long versionId,
            @Valid @RequestBody ReturnToDraftRequest request
    ) {
        return ApiResponse.success(service.returnToDraft(projectId, versionId, request.expectedChecksum(), request.reason()));
    }

    @PostMapping("/{versionId}/lock")
    public ApiResponse<SpecificationResponse> lock(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long versionId,
            @Valid @RequestBody ChecksumActionRequest request
    ) {
        return ApiResponse.success(service.lock(projectId, versionId, request.expectedChecksum()));
    }
}


