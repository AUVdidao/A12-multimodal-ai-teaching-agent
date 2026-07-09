package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/clarification")
public class ClarificationController {

    private final ClarificationService clarificationService;

    public ClarificationController(ClarificationService clarificationService) {
        this.clarificationService = clarificationService;
    }

    @PostMapping("/check")
    public ApiResponse<ClarificationResult> check(
            @PathVariable Long projectId,
            @RequestBody ClarificationCheckRequest request
    ) {
        return ApiResponse.success(clarificationService.check(projectId, request));
    }

    @PostMapping("/questions")
    public ApiResponse<ClarificationResult> questions(
            @PathVariable Long projectId,
            @RequestBody ClarificationCheckRequest request
    ) {
        return ApiResponse.success(clarificationService.check(projectId, request));
    }
}
