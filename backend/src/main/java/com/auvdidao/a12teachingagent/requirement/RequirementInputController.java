package com.auvdidao.a12teachingagent.requirement;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputDtos.RequirementInputRequest;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputDtos.RequirementInputResponse;
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
@RequestMapping("/api/projects/{projectId}/requirements")
public class RequirementInputController {

    private final RequirementInputService requirementInputService;

    public RequirementInputController(RequirementInputService requirementInputService) {
        this.requirementInputService = requirementInputService;
    }

    @PostMapping
    public ApiResponse<RequirementInputResponse> save(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @Valid @RequestBody RequirementInputRequest request
    ) {
        return ApiResponse.success(requirementInputService.save(projectId, request));
    }

    @GetMapping("/latest")
    public ApiResponse<RequirementInputResponse> latest(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(requirementInputService.latest(projectId));
    }
}
