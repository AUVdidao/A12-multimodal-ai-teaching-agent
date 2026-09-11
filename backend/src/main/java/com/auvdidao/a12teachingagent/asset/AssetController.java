package com.auvdidao.a12teachingagent.asset;

import com.auvdidao.a12teachingagent.asset.dto.AssetDtos;
import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/asset-candidates")
public class AssetController {
    private final AssetService service;
    public AssetController(AssetService service) { this.service = service; }

    @PostMapping
    public ApiResponse<AssetDtos.CandidateResponse> create(@PathVariable @Positive Long projectId, @Valid @RequestBody AssetDtos.CreateUploadCandidateRequest request) { return ApiResponse.success(service.createUpload(projectId, request)); }
    @GetMapping
    public ApiResponse<List<AssetDtos.CandidateResponse>> list(@PathVariable @Positive Long projectId) { return ApiResponse.success(service.listCandidates(projectId)); }
    @PostMapping("/generate")
    public ApiResponse<Void> generate(@PathVariable @Positive Long projectId, @Valid @RequestBody AssetDtos.GenerateCandidateRequest request) { service.generateWithProvider(projectId, request); return ApiResponse.success(null); }
    @PostMapping("/{assetId}/submit-review")
    public ApiResponse<AssetDtos.CandidateResponse> submit(@PathVariable @Positive Long projectId, @PathVariable @Positive Long assetId, @Valid @RequestBody(required = false) AssetDtos.ReviewRequest request) { return ApiResponse.success(service.submitReview(projectId, assetId, request)); }
    @PostMapping("/{assetId}/approve")
    public ApiResponse<AssetDtos.ManifestResponse> approve(@PathVariable @Positive Long projectId, @PathVariable @Positive Long assetId, @Valid @RequestBody AssetDtos.ReviewRequest request) { return ApiResponse.success(service.approve(projectId, assetId, request)); }
    @PostMapping("/{assetId}/reject")
    public ApiResponse<AssetDtos.CandidateResponse> reject(@PathVariable @Positive Long projectId, @PathVariable @Positive Long assetId, @Valid @RequestBody AssetDtos.ReviewRequest request) { return ApiResponse.success(service.reject(projectId, assetId, request)); }
    @PostMapping("/{assetId}/revoke")
    public ApiResponse<AssetDtos.ManifestResponse> revoke(@PathVariable @Positive Long projectId, @PathVariable @Positive Long assetId, @Valid @RequestBody AssetDtos.ReviewRequest request) { return ApiResponse.success(service.revoke(projectId, assetId, request)); }
    @GetMapping("/manifest")
    public ApiResponse<List<AssetDtos.ManifestResponse>> manifest(@PathVariable @Positive Long projectId) { return ApiResponse.success(service.listManifest(projectId)); }
}
