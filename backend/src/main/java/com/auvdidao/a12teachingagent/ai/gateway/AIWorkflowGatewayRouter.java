package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.AiProvider;
import com.auvdidao.a12teachingagent.ai.config.AiWorkflowProperties;
import com.auvdidao.a12teachingagent.ai.config.WorkflowCode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class AIWorkflowGatewayRouter implements AIWorkflowGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(AIWorkflowGatewayRouter.class);

    private final AiWorkflowProperties properties;
    private final MockAIWorkflowGateway mockGateway;
    private final DifyAIWorkflowGateway difyGateway;
    private final AtomicReference<String> lastActiveProvider = new AtomicReference<>();
    private final AtomicReference<String> lastFallbackReason = new AtomicReference<>();

    public AIWorkflowGatewayRouter(
            AiWorkflowProperties properties,
            MockAIWorkflowGateway mockGateway,
            DifyAIWorkflowGateway difyGateway
    ) {
        this.properties = properties;
        this.mockGateway = mockGateway;
        this.difyGateway = difyGateway;
    }

    @Override
    public AiGatewayStatus status() {
        AiProvider requestedProvider = provider();
        String activeProvider = requestedProvider == AiProvider.MOCK
                ? AiProvider.MOCK.name()
                : lastActiveProvider.get();
        if (!StringUtils.hasText(activeProvider)) {
            activeProvider = initialDifyStatus();
        }
        return new AiGatewayStatus(
                requestedProvider.name(),
                activeProvider,
                requestedProvider == AiProvider.MOCK || properties.isFallbackToMock(),
                properties.isDifyConfigured(),
                properties.isFallbackToMock(),
                statusMessage(requestedProvider)
        );
    }

    @Override
    public ClarificationResponse clarifyRequirement(ClarificationRequest request) {
        return route(
                WorkflowCode.CLARIFICATION,
                "clarification",
                () -> difyGateway.clarifyRequirement(request),
                () -> mockGateway.clarifyRequirement(request)
        );
    }

    @Override
    public RequirementSummaryResponse summarizeRequirement(RequirementSummaryRequest request) {
        return route(
                WorkflowCode.REQUIREMENT_SUMMARY,
                "requirement-summary",
                () -> difyGateway.summarizeRequirement(request),
                () -> mockGateway.summarizeRequirement(request)
        );
    }

    @Override
    public MaterialAnalysisResponse analyzeMaterial(MaterialAnalysisRequest request) {
        return route(
                WorkflowCode.MATERIAL_ANALYSIS,
                "material-analysis",
                () -> difyGateway.analyzeMaterial(request),
                () -> mockGateway.analyzeMaterial(request)
        );
    }

    @Override
    public KnowledgeRetrievalResponse retrieveKnowledge(KnowledgeRetrievalRequest request) {
        return route(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "knowledge-retrieval",
                () -> difyGateway.retrieveKnowledge(request),
                () -> mockGateway.retrieveKnowledge(request)
        );
    }

    @Override
    public TeachingIntentResponse buildTeachingIntent(TeachingIntentRequest request) {
        return route(
                WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "teaching-intent",
                () -> difyGateway.buildTeachingIntent(request),
                () -> mockGateway.buildTeachingIntent(request)
        );
    }

    @Override
    public GenerationPlanResponse createGenerationPlan(GenerationPlanRequest request) {
        return route(
                WorkflowCode.GENERATION_PLAN,
                "generation-plan",
                () -> difyGateway.createGenerationPlan(request),
                () -> mockGateway.createGenerationPlan(request)
        );
    }

    @Override
    public RevisionResponse reviseArtifact(RevisionRequest request) {
        return route(
                WorkflowCode.REVISION,
                "revision",
                () -> difyGateway.reviseArtifact(request),
                () -> mockGateway.reviseArtifact(request)
        );
    }

    private <T> T route(
            WorkflowCode workflowCode,
            String operation,
            Supplier<T> difyCall,
            Supplier<T> mockCall
    ) {
        if (provider() == AiProvider.MOCK) {
            lastActiveProvider.set(AiProvider.MOCK.name());
            return mockCall.get();
        }

        String configurationIssue = properties.difyConfigurationIssue(workflowCode);
        if (configurationIssue != null) {
            return fallbackOrThrow(
                    workflowCode,
                    operation,
                    new AiWorkflowUnavailableException(workflowCode.code() + ": " + configurationIssue + "."),
                    mockCall
            );
        }

        try {
            T response = difyCall.get();
            lastActiveProvider.set(AiProvider.DIFY.name());
            return response;
        } catch (AiWorkflowUnavailableException exception) {
            return fallbackOrThrow(workflowCode, operation, exception, mockCall);
        }
    }

    private <T> T fallbackOrThrow(
            WorkflowCode workflowCode,
            String operation,
            AiWorkflowUnavailableException exception,
            Supplier<T> mockCall
    ) {
        String reason = sanitizeReason(exception.getMessage());
        lastFallbackReason.set(reason);
        if (!properties.isFallbackToMock()) {
            lastActiveProvider.set("UNAVAILABLE");
            throw exception;
        }
        LOGGER.warn(
                "Dify workflow {} operation {} fell back to Mock: {}",
                workflowCode.code(),
                operation,
                reason
        );
        lastActiveProvider.set(AiProvider.MOCK.name());
        return mockCall.get();
    }

    private AiProvider provider() {
        return properties.getProvider() == null ? AiProvider.MOCK : properties.getProvider();
    }

    private String initialDifyStatus() {
        if (properties.isDifyConfigured()) {
            return AiProvider.DIFY.name();
        }
        if (properties.areCallableDifyWorkflowsConfigured()) {
            return "DIFY_MAPPED";
        }
        if (!properties.configuredWorkflowCodes().isEmpty()) {
            return "DIFY_PARTIAL";
        }
        return properties.isFallbackToMock() ? AiProvider.MOCK.name() : "UNAVAILABLE";
    }

    private String statusMessage(AiProvider requestedProvider) {
        String configured = listOrNone(properties.configuredWorkflowCodes());
        String missing = listOrNone(properties.missingWorkflowCodes());
        StringBuilder message = new StringBuilder();
        if (requestedProvider == AiProvider.MOCK) {
            message.append("Mock provider is active. ");
        } else {
            message.append("Dify Run Workflow by ID routing is requested. ");
        }
        message.append("Configured workflow slots: ").append(configured)
                .append("; missing or incomplete workflow slots: ").append(missing)
                .append("; Gateway-mapped callable workflows: ")
                .append(listOrNone(properties.callableWorkflowCodes())).append('.')
                .append(" WF-06 is configuration-only because AIWorkflowGateway has no content-draft method.");

        AiWorkflowProperties.Dify dify = properties.getDify();
        if (dify != null
                && StringUtils.hasText(dify.getWorkflowId())
                && !properties.missingWorkflowCodes().isEmpty()) {
            message.append(" Legacy dify.workflow-id is retained but is not used as a shared published ID.");
        }
        String fallbackReason = lastFallbackReason.get();
        if (StringUtils.hasText(fallbackReason)) {
            message.append(" Last fallback reason: ").append(fallbackReason);
        }
        return message.toString();
    }

    private String listOrNone(java.util.List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    private String sanitizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "provider request failed";
        }
        String sanitized = reason.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        return sanitized.length() <= 200 ? sanitized : sanitized.substring(0, 200);
    }
}
