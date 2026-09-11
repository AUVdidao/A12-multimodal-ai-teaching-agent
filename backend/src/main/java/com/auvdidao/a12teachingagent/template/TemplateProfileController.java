package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.auvdidao.a12teachingagent.template.TemplateDtos.*;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/templates/{templateId}/profiles")
public class TemplateProfileController {

    private final TemplateProfileService profileService;

    public TemplateProfileController(TemplateProfileService profileService) {
        this.profileService = profileService;
    }

    @PostMapping
    public ApiResponse<ProfileResponse> createCandidate(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @Valid @RequestBody RevisionRequest request
    ) {
        return ApiResponse.success(profileService.createCandidate(projectId, templateId, request));
    }

    @GetMapping
    public ApiResponse<List<ProfileSummary>> history(@PathVariable @Positive Long projectId, @PathVariable @Positive Long templateId) {
        return ApiResponse.success(profileService.history(projectId, templateId));
    }

    @GetMapping("/{profileId}")
    public ApiResponse<ProfileResponse> detail(@PathVariable @Positive Long projectId, @PathVariable @Positive Long templateId, @PathVariable @Positive Long profileId) {
        return ApiResponse.success(profileService.detail(projectId, templateId, profileId));
    }

    @PutMapping("/{profileId}")
    public ApiResponse<ProfileResponse> edit(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @PathVariable @Positive Long profileId,
            @Valid @RequestBody CandidateProfileRequest request
    ) {
        return ApiResponse.success(profileService.editCandidate(projectId, templateId, profileId, request));
    }

    @PostMapping("/{profileId}/review")
    public ApiResponse<ProfileResponse> submitReview(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @PathVariable @Positive Long profileId,
            @RequestParam(required = false) String note
    ) {
        return ApiResponse.success(profileService.submitReview(projectId, templateId, profileId, note));
    }

    @PostMapping("/{profileId}/confirm")
    public ApiResponse<ProfileResponse> confirm(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @PathVariable @Positive Long profileId,
            @Valid @RequestBody ConfirmProfileRequest request
    ) {
        return ApiResponse.success(profileService.confirm(projectId, templateId, profileId, request));
    }

    @PostMapping("/{profileId}/revisions")
    public ApiResponse<ProfileResponse> revise(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @PathVariable @Positive Long profileId,
            @Valid @RequestBody RevisionRequest request
    ) {
        return ApiResponse.success(profileService.createRevision(projectId, templateId, profileId, request));
    }

    @PostMapping("/{profileId}/rollback")
    public ApiResponse<ProfileResponse> rollback(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @PathVariable @Positive Long profileId,
            @Valid @RequestBody RollbackRequest request
    ) {
        return ApiResponse.success(profileService.rollback(projectId, templateId, profileId, request));
    }

    @GetMapping("/{profileId}/capability-view")
    public ApiResponse<CapabilityViewResponse> capabilityView(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @PathVariable @Positive Long profileId
    ) {
        return ApiResponse.success(profileService.capabilityView(projectId, templateId, profileId));
    }
}


