package com.auvdidao.a12teachingagent.ai.api;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.AiGatewayStatus;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RevisionRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RevisionResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentResponse;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-workflow")
public class AIWorkflowController {

    private final AIWorkflowGateway aiWorkflowGateway;
    private final ProjectAccessService projectAccessService;

    public AIWorkflowController(AIWorkflowGateway aiWorkflowGateway, ProjectAccessService projectAccessService) {
        this.aiWorkflowGateway = aiWorkflowGateway;
        this.projectAccessService = projectAccessService;
    }

    @GetMapping("/status")
    public ApiResponse<AiGatewayStatus> status() {
        return ApiResponse.success(aiWorkflowGateway.status());
    }

    @PostMapping("/clarification")
    public ApiResponse<ClarificationResponse> clarifyRequirement(
            @Valid @RequestBody ClarificationRequest request
    ) {
        projectAccessService.requireAccess(request.projectId());
        return ApiResponse.success(aiWorkflowGateway.clarifyRequirement(request));
    }

    @PostMapping("/requirement-summary")
    public ApiResponse<RequirementSummaryResponse> summarizeRequirement(
            @Valid @RequestBody RequirementSummaryRequest request
    ) {
        projectAccessService.requireAccess(request.projectId());
        return ApiResponse.success(aiWorkflowGateway.summarizeRequirement(request));
    }

    @PostMapping("/material-analysis")
    public ApiResponse<MaterialAnalysisResponse> analyzeMaterial(
            @Valid @RequestBody MaterialAnalysisRequest request
    ) {
        projectAccessService.requireAccess(request.projectId());
        return ApiResponse.success(aiWorkflowGateway.analyzeMaterial(request));
    }

    @PostMapping("/knowledge-retrieval")
    public ApiResponse<KnowledgeRetrievalResponse> retrieveKnowledge(
            @Valid @RequestBody KnowledgeRetrievalRequest request
    ) {
        projectAccessService.requireAccess(request.projectId());
        return ApiResponse.success(aiWorkflowGateway.retrieveKnowledge(request));
    }

    @PostMapping("/teaching-intent")
    public ApiResponse<TeachingIntentResponse> buildTeachingIntent(
            @Valid @RequestBody TeachingIntentRequest request
    ) {
        projectAccessService.requireAccess(request.projectId());
        return ApiResponse.success(aiWorkflowGateway.buildTeachingIntent(request));
    }

    @PostMapping("/generation-plan")
    public ApiResponse<GenerationPlanResponse> createGenerationPlan(
            @Valid @RequestBody GenerationPlanRequest request
    ) {
        projectAccessService.requireAccess(request.projectId());
        return ApiResponse.success(aiWorkflowGateway.createGenerationPlan(request));
    }

    @PostMapping("/revision")
    public ApiResponse<RevisionResponse> reviseArtifact(
            @Valid @RequestBody RevisionRequest request
    ) {
        projectAccessService.requireAccess(request.projectId());
        return ApiResponse.success(aiWorkflowGateway.reviseArtifact(request));
    }
}
