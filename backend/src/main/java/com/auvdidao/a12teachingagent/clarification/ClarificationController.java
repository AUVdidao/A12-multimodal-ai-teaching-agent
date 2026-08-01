package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.requirement.RequirementInputService;
import com.auvdidao.a12teachingagent.requirement.dto.RequirementInputDtos.RequirementInputResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/clarification")
public class ClarificationController {

    private final ClarificationService clarificationService;
    private final RequirementInputService requirementInputService;

    public ClarificationController(
            ClarificationService clarificationService,
            RequirementInputService requirementInputService
    ) {
        this.clarificationService = clarificationService;
        this.requirementInputService = requirementInputService;
    }

    @PostMapping("/check")
    public ApiResponse<ClarificationResult> check(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @Valid @RequestBody ClarificationCheckRequest request
    ) {
        return ApiResponse.success(clarificationService.check(projectId, request));
    }

    @PostMapping("/questions")
    public ApiResponse<ClarificationResult> questions(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @Valid @RequestBody ClarificationCheckRequest request
    ) {
        return ApiResponse.success(clarificationService.questions(projectId, request));
    }

    @PostMapping("/answers")
    public ApiResponse<RequirementInputResponse> answer(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @Valid @RequestBody ClarificationAnswerRequest request
    ) {
        return ApiResponse.success(requirementInputService.applyClarificationAnswer(
                projectId,
                request.targetField(),
                request.answer()
        ));
    }
}
