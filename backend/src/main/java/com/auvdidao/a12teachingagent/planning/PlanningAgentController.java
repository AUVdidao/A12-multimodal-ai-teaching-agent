package com.auvdidao.a12teachingagent.planning;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningRequest;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningResponse;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.TraceResponse;
import com.auvdidao.a12teachingagent.template.TemplateDtos.CapabilityViewResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/planning")
public class PlanningAgentController {
    private final PlanningAgentService service;

    public PlanningAgentController(PlanningAgentService service) { this.service = service; }

    @GetMapping("/capability-views/{templateId}/{profileVersionId}")
    public ApiResponse<CapabilityViewResponse> capabilityView(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @PathVariable @Positive Long profileVersionId
    ) { return ApiResponse.success(service.capabilityView(projectId, templateId, profileVersionId)); }

    @GetMapping("/confirmed-context")
    public ApiResponse<ConfirmedTeachingContextService.ConfirmedContextReference> confirmedContext(
            @PathVariable @Positive Long projectId
    ) { return ApiResponse.success(service.confirmedContextReference(projectId)); }

    @PostMapping("/proposals")
    public ApiResponse<PlanningResponse> proposal(
            @PathVariable @Positive Long projectId,
            @Valid @RequestBody PlanningRequest request
    ) { return ApiResponse.success(service.createProposal(projectId, request)); }

    @GetMapping("/traces")
    public ApiResponse<List<TraceResponse>> traces(
            @PathVariable @Positive Long projectId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) { return ApiResponse.success(service.traces(projectId, limit)); }
}

