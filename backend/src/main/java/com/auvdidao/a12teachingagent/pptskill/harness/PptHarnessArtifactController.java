package com.auvdidao.a12teachingagent.pptskill.harness;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/ppt-harness")
public class PptHarnessArtifactController {
    private final PptHarnessGenerationService service;
    private final PptHarnessEventForwarder eventForwarder;
    public PptHarnessArtifactController(PptHarnessGenerationService service, PptHarnessEventForwarder eventForwarder) {
        this.service = service;
        this.eventForwarder = eventForwarder;
    }

    @PostMapping("/jobs")
    public ApiResponse<PptHarnessDtos.JobResponse> start(@PathVariable @Positive Long projectId) {
        return ApiResponse.success(service.start(projectId));
    }

    @GetMapping("/jobs/{taskId}")
    public ApiResponse<PptHarnessDtos.JobResponse> status(@PathVariable @Positive Long projectId, @PathVariable @NotBlank String taskId) {
        return ApiResponse.success(service.status(projectId, taskId));
    }

    @GetMapping(value = "/jobs/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable @Positive Long projectId, @PathVariable @NotBlank String taskId) {
        service.requireTaskAccess(projectId, taskId);
        return eventForwarder.forward(taskId);
    }
}
