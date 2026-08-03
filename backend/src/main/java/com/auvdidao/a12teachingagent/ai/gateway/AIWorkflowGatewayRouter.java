package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.auvdidao.a12teachingagent.ai.credential.AiApiCredentialService;
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
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.StructuredContentRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.StructuredContentResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentResponse;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class AIWorkflowGatewayRouter implements AIWorkflowGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(AIWorkflowGatewayRouter.class);

    private final AiWorkflowProperties properties;
    private final KimiAssistantProperties kimiProperties;
    private final MockAIWorkflowGateway mockGateway;
    private final KimiAIWorkflowGateway kimiGateway;
    private final AiApiCredentialService credentialService;
    private final AtomicReference<String> lastActiveProvider = new AtomicReference<>();
    private final AtomicReference<String> lastFallbackReason = new AtomicReference<>();

    public AIWorkflowGatewayRouter(
            AiWorkflowProperties properties,
            KimiAssistantProperties kimiProperties,
            MockAIWorkflowGateway mockGateway,
            KimiAIWorkflowGateway kimiGateway
    ) {
        this(properties, kimiProperties, mockGateway, kimiGateway, null);
    }

    @Autowired
    public AIWorkflowGatewayRouter(
            AiWorkflowProperties properties,
            KimiAssistantProperties kimiProperties,
            MockAIWorkflowGateway mockGateway,
            KimiAIWorkflowGateway kimiGateway,
            AiApiCredentialService credentialService
    ) {
        this.properties = properties;
        this.kimiProperties = kimiProperties;
        this.mockGateway = mockGateway;
        this.kimiGateway = kimiGateway;
        this.credentialService = credentialService;
    }

    @Override
    public AiGatewayStatus status() {
        AiProvider requestedProvider = provider();
        String activeProvider = requestedProvider == AiProvider.MOCK
                ? AiProvider.MOCK.name()
                : lastActiveProvider.get();
        if (!StringUtils.hasText(activeProvider)) {
            activeProvider = initialProviderStatus();
        }
        return new AiGatewayStatus(
                requestedProvider.name(),
                activeProvider,
                requestedProvider == AiProvider.MOCK || properties.isFallbackToMock(),
                kimiConfigured(),
                properties.isFallbackToMock(),
                statusMessage(requestedProvider)
        );
    }

    @Override
    public ClarificationResponse clarifyRequirement(ClarificationRequest request) {
        return route(WorkflowCode.CLARIFICATION, "clarification",
                () -> kimiGateway.clarifyRequirement(request),
                () -> mockGateway.clarifyRequirement(request));
    }

    @Override
    public RequirementSummaryResponse summarizeRequirement(RequirementSummaryRequest request) {
        return route(WorkflowCode.REQUIREMENT_SUMMARY, "requirement-summary",
                () -> kimiGateway.summarizeRequirement(request),
                () -> mockGateway.summarizeRequirement(request));
    }

    @Override
    public MaterialAnalysisResponse analyzeMaterial(MaterialAnalysisRequest request) {
        return route(WorkflowCode.MATERIAL_ANALYSIS, "material-analysis",
                () -> kimiGateway.analyzeMaterial(request),
                () -> mockGateway.analyzeMaterial(request));
    }

    @Override
    public KnowledgeRetrievalResponse retrieveKnowledge(KnowledgeRetrievalRequest request) {
        return route(WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT, "knowledge-retrieval",
                () -> kimiGateway.retrieveKnowledge(request),
                () -> mockGateway.retrieveKnowledge(request));
    }

    @Override
    public TeachingIntentResponse buildTeachingIntent(TeachingIntentRequest request) {
        return route(WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT, "teaching-intent",
                () -> kimiGateway.buildTeachingIntent(request),
                () -> mockGateway.buildTeachingIntent(request));
    }

    @Override
    public GenerationPlanResponse createGenerationPlan(GenerationPlanRequest request) {
        return route(WorkflowCode.GENERATION_PLAN, "generation-plan",
                () -> kimiGateway.createGenerationPlan(request),
                () -> mockGateway.createGenerationPlan(request));
    }

    @Override
    public StructuredContentResponse generateStructuredContent(StructuredContentRequest request) {
        return route(WorkflowCode.CONTENT_DRAFT, "structured-content",
                () -> kimiGateway.generateStructuredContent(request),
                () -> mockGateway.generateStructuredContent(request));
    }

    @Override
    public RevisionResponse reviseArtifact(RevisionRequest request) {
        return route(WorkflowCode.REVISION, "revision",
                () -> kimiGateway.reviseArtifact(request),
                () -> mockGateway.reviseArtifact(request));
    }

    private <T> T route(
            WorkflowCode workflowCode,
            String operation,
            Supplier<T> kimiCall,
            Supplier<T> mockCall
    ) {
        if (provider() == AiProvider.MOCK) {
            lastActiveProvider.set(AiProvider.MOCK.name());
            return mockCall.get();
        }

        if (!kimiConfigured()) {
            return fallbackOrThrow(
                    workflowCode,
                    operation,
                    new AiWorkflowUnavailableException(
                            workflowCode.code() + ": Kimi workflow provider is not configured."
                    ),
                    mockCall
            );
        }

        try {
            T response = kimiCall.get();
            lastActiveProvider.set(AiProvider.KIMI.name());
            lastFallbackReason.set(null);
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
                "Kimi workflow {} operation {} fell back to Mock: {}",
                workflowCode.code(),
                operation,
                reason
        );
        lastActiveProvider.set(AiProvider.MOCK.name());
        return mockCall.get();
    }

    private AiProvider provider() {
        return properties.getProvider() == null ? AiProvider.KIMI : properties.getProvider();
    }

    private String initialProviderStatus() {
        if (kimiConfigured()) {
            return AiProvider.KIMI.name();
        }
        return properties.isFallbackToMock() ? AiProvider.MOCK.name() : "UNAVAILABLE";
    }

    private String statusMessage(AiProvider requestedProvider) {
        StringBuilder message = new StringBuilder();
        if (requestedProvider == AiProvider.MOCK) {
            message.append("Mock provider is explicitly active. ");
        } else {
            message.append("Kimi structured workflow routing is requested. ");
        }
        message.append("Kimi workflow configuration: ")
                .append(kimiConfigured() ? "ready" : "missing")
                .append("; model: ")
                .append(StringUtils.hasText(kimiProperties.getWorkflowModel())
                        ? kimiProperties.getWorkflowModel().strip()
                        : "not configured")
                .append("; fallback to Mock: ")
                .append(properties.isFallbackToMock())
                .append('.');

        String fallbackReason = lastFallbackReason.get();
        if (StringUtils.hasText(fallbackReason)) {
            message.append(" Last fallback reason: ").append(fallbackReason);
        }
        return message.toString();
    }

    private boolean kimiConfigured() {
        return kimiProperties.isWorkflowConfigured()
                || (credentialService != null && credentialService.hasActiveCredential());
    }

    private String sanitizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "provider request failed";
        }
        String sanitized = reason.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        return sanitized.length() <= 200 ? sanitized : sanitized.substring(0, 200);
    }
}
