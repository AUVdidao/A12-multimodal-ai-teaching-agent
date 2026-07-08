package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.AiProvider;
import com.auvdidao.a12teachingagent.ai.config.AiWorkflowProperties;
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
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import org.springframework.stereotype.Service;

@Service
public class AIWorkflowGatewayRouter implements AIWorkflowGateway {

    private final AiWorkflowProperties properties;
    private final MockAIWorkflowGateway mockGateway;

    public AIWorkflowGatewayRouter(AiWorkflowProperties properties, MockAIWorkflowGateway mockGateway) {
        this.properties = properties;
        this.mockGateway = mockGateway;
    }

    @Override
    public AiGatewayStatus status() {
        String activeProvider = isMockActive() ? AiProvider.MOCK.name() : "UNAVAILABLE";
        return new AiGatewayStatus(
                provider().name(),
                activeProvider,
                isMockActive(),
                properties.isDifyConfigured(),
                properties.isFallbackToMock(),
                statusMessage(activeProvider)
        );
    }

    @Override
    public ClarificationResponse clarifyRequirement(ClarificationRequest request) {
        ensureMockAvailable();
        return mockGateway.clarifyRequirement(request);
    }

    @Override
    public RequirementSummaryResponse summarizeRequirement(RequirementSummaryRequest request) {
        ensureMockAvailable();
        return mockGateway.summarizeRequirement(request);
    }

    @Override
    public MaterialAnalysisResponse analyzeMaterial(MaterialAnalysisRequest request) {
        ensureMockAvailable();
        return mockGateway.analyzeMaterial(request);
    }

    @Override
    public KnowledgeRetrievalResponse retrieveKnowledge(KnowledgeRetrievalRequest request) {
        ensureMockAvailable();
        return mockGateway.retrieveKnowledge(request);
    }

    @Override
    public TeachingIntentResponse buildTeachingIntent(TeachingIntentRequest request) {
        ensureMockAvailable();
        return mockGateway.buildTeachingIntent(request);
    }

    @Override
    public GenerationPlanResponse createGenerationPlan(GenerationPlanRequest request) {
        ensureMockAvailable();
        return mockGateway.createGenerationPlan(request);
    }

    @Override
    public RevisionResponse reviseArtifact(RevisionRequest request) {
        ensureMockAvailable();
        return mockGateway.reviseArtifact(request);
    }

    private boolean isMockActive() {
        if (provider() == AiProvider.MOCK) {
            return true;
        }
        return properties.isFallbackToMock();
    }

    private void ensureMockAvailable() {
        if (!isMockActive()) {
            throw new AiWorkflowUnavailableException(
                    "Dify provider is selected, but the real Dify workflow is not implemented in TA-005."
            );
        }
    }

    private AiProvider provider() {
        return properties.getProvider() == null ? AiProvider.MOCK : properties.getProvider();
    }

    private String statusMessage(String activeProvider) {
        if (AiProvider.MOCK.name().equals(activeProvider) && provider() == AiProvider.DIFY) {
            return "Dify provider is configured as requested, but TA-005 keeps Mock fallback active.";
        }
        if (AiProvider.MOCK.name().equals(activeProvider)) {
            return "Mock AI workflow is active. No external Dify key is required.";
        }
        return "AI workflow is unavailable because Dify is selected and Mock fallback is disabled.";
    }
}
