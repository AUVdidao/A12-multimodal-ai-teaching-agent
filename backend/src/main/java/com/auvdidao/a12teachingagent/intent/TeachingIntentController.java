package com.auvdidao.a12teachingagent.intent;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.intent.dto.TeachingIntentDtos.TeachingIntentResponse;
import com.auvdidao.a12teachingagent.intent.dto.TeachingIntentDtos.TeachingIntentUpdateRequest;
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
@RequestMapping("/api/projects/{projectId}/teaching-intents")
public class TeachingIntentController {

    private final TeachingIntentService teachingIntentService;

    public TeachingIntentController(TeachingIntentService teachingIntentService) {
        this.teachingIntentService = teachingIntentService;
    }

    @PostMapping("/generate")
    public ApiResponse<TeachingIntentResponse> generate(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(teachingIntentService.generate(projectId));
    }

    @GetMapping("/latest")
    public ApiResponse<TeachingIntentResponse> latest(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(teachingIntentService.latest(projectId));
    }

    @PutMapping("/{intentId}")
    public ApiResponse<TeachingIntentResponse> update(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "intentId must be greater than 0") Long intentId,
            @Valid @RequestBody TeachingIntentUpdateRequest request
    ) {
        return ApiResponse.success(teachingIntentService.update(projectId, intentId, request));
    }

    @PostMapping("/{intentId}/confirm")
    public ApiResponse<TeachingIntentResponse> confirm(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "intentId must be greater than 0") Long intentId
    ) {
        return ApiResponse.success(teachingIntentService.confirm(projectId, intentId));
    }

    @PostMapping("/{intentId}/revisions")
    public ApiResponse<TeachingIntentResponse> revise(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "intentId must be greater than 0") Long intentId
    ) {
        return ApiResponse.success(teachingIntentService.revise(projectId, intentId));
    }
}
