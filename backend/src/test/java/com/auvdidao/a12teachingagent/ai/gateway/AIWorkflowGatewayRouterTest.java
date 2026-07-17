package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.config.AiProvider;
import com.auvdidao.a12teachingagent.ai.config.AiWorkflowProperties;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationResponse;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AIWorkflowGatewayRouterTest {

    private final MockAIWorkflowGateway mockGateway = mock(MockAIWorkflowGateway.class);
    private final DifyAIWorkflowGateway difyGateway = mock(DifyAIWorkflowGateway.class);
    private final ClarificationRequest request = new ClarificationRequest(
            78L,
            "Create a fraction lesson",
            List.of(),
            GenerationMode.STANDARD
    );
    private final ClarificationResponse mockResponse = new ClarificationResponse(
            "mock-ai-workflow",
            List.of(),
            List.of(),
            Map.of(),
            "Continue"
    );
    private final ClarificationResponse difyResponse = new ClarificationResponse(
            "published-wf-01",
            List.of(),
            List.of(),
            Map.of(),
            "Continue"
    );

    @Test
    void mockProviderNeverCallsDifyEvenWhenDifyIsConfigured() {
        AiWorkflowProperties properties = properties(AiProvider.MOCK, true);
        configureClarification(properties);
        when(mockGateway.clarifyRequirement(request)).thenReturn(mockResponse);

        AIWorkflowGatewayRouter router = router(properties);

        assertThat(router.clarifyRequirement(request)).isSameAs(mockResponse);
        verify(mockGateway).clarifyRequirement(request);
        verifyNoInteractions(difyGateway);
        assertThat(router.status().activeProvider()).isEqualTo("MOCK");
    }

    @Test
    void missingPerWorkflowApiKeyFallsBackWithoutAttemptingHttpProvider() {
        AiWorkflowProperties properties = properties(AiProvider.DIFY, true);
        properties.getDify().setWorkflowId("legacy-shared-id");
        properties.getDify().setApiKey("legacy-shared-key");
        when(mockGateway.clarifyRequirement(request)).thenReturn(mockResponse);

        AIWorkflowGatewayRouter router = router(properties);

        assertThat(router.clarifyRequirement(request)).isSameAs(mockResponse);
        verify(mockGateway).clarifyRequirement(request);
        verifyNoInteractions(difyGateway);
        assertThat(router.status().activeProvider()).isEqualTo("MOCK");
        assertThat(router.status().message())
                .contains("API key is missing for WF-01")
                .doesNotContain("legacy-shared-id")
                .doesNotContain("legacy-shared-key");
    }

    @Test
    void missingPerWorkflowApiKeyWithoutFallbackThrowsUnavailable() {
        AiWorkflowProperties properties = properties(AiProvider.DIFY, false);

        AIWorkflowGatewayRouter router = router(properties);

        assertThatThrownBy(() -> router.clarifyRequirement(request))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("API key is missing for WF-01");
        verifyNoInteractions(mockGateway, difyGateway);
        assertThat(router.status().activeProvider()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void configuredDifyFlowUsesRealProvider() {
        AiWorkflowProperties properties = properties(AiProvider.DIFY, true);
        configureClarification(properties);
        when(difyGateway.clarifyRequirement(request)).thenReturn(difyResponse);

        AIWorkflowGatewayRouter router = router(properties);

        assertThat(router.clarifyRequirement(request)).isSameAs(difyResponse);
        verify(difyGateway).clarifyRequirement(request);
        verify(mockGateway, never()).clarifyRequirement(request);
        assertThat(router.status().activeProvider()).isEqualTo("DIFY");
    }

    @Test
    void externalFailureFallsBackToMockAndReportsSanitizedReason() {
        AiWorkflowProperties properties = properties(AiProvider.DIFY, true);
        configureClarification(properties);
        when(difyGateway.clarifyRequirement(request))
                .thenThrow(new AiWorkflowUnavailableException("WF-01: Dify returned HTTP 500."));
        when(mockGateway.clarifyRequirement(request)).thenReturn(mockResponse);

        AIWorkflowGatewayRouter router = router(properties);

        assertThat(router.clarifyRequirement(request)).isSameAs(mockResponse);
        verify(difyGateway).clarifyRequirement(request);
        verify(mockGateway).clarifyRequirement(request);
        assertThat(router.status().activeProvider()).isEqualTo("MOCK");
        assertThat(router.status().message()).contains("Last fallback reason: WF-01: Dify returned HTTP 500.");
    }

    @Test
    void externalFailureWithoutFallbackPropagatesUnavailable() {
        AiWorkflowProperties properties = properties(AiProvider.DIFY, false);
        configureClarification(properties);
        when(difyGateway.clarifyRequirement(request))
                .thenThrow(new AiWorkflowUnavailableException("WF-01: Dify returned HTTP 401."));

        AIWorkflowGatewayRouter router = router(properties);

        assertThatThrownBy(() -> router.clarifyRequirement(request))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("HTTP 401");
        verify(difyGateway).clarifyRequirement(request);
        verifyNoInteractions(mockGateway);
        assertThat(router.status().activeProvider()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void statusDistinguishesPartialDifyConfigurationWithoutExposingKeys() {
        AiWorkflowProperties properties = properties(AiProvider.DIFY, true);
        configureClarification(properties);
        properties.getDify().getWorkflows().getClarification().setApiKey("wf-01-secret-key");

        var status = router(properties).status();

        assertThat(status.requestedProvider()).isEqualTo("DIFY");
        assertThat(status.activeProvider()).isEqualTo("DIFY_PARTIAL");
        assertThat(status.difyConfigured()).isFalse();
        assertThat(status.fallbackToMock()).isTrue();
        assertThat(status.message())
                .contains("Configured workflow slots: WF-01")
                .contains("missing or incomplete workflow slots: WF-02")
                .contains("Gateway-mapped callable workflows: WF-01, WF-02, WF-03, WF-04, WF-05, WF-07")
                .contains("WF-06 is configuration-only")
                .doesNotContain("wf-01-secret-key")
                .doesNotContain("wf-01-secret-key");
    }

    @Test
    void fullConfigurationRequiresWf06WhileCallableConfigurationIsReportedSeparately() {
        AiWorkflowProperties properties = properties(AiProvider.DIFY, true);
        configureEveryCallableWorkflow(properties);

        var mappedStatus = router(properties).status();

        assertThat(properties.areCallableDifyWorkflowsConfigured()).isTrue();
        assertThat(mappedStatus.difyConfigured()).isFalse();
        assertThat(mappedStatus.activeProvider()).isEqualTo("DIFY_MAPPED");
        assertThat(mappedStatus.message())
                .contains("missing or incomplete workflow slots: WF-06")
                .contains("WF-06 is configuration-only");

        properties.getDify().getWorkflows().getContentDraft().setWorkflowId("published-wf-06");
        properties.getDify().getWorkflows().getContentDraft().setApiKey("wf-06-key");
        var fullStatus = router(properties).status();

        assertThat(properties.isDifyConfigured()).isTrue();
        assertThat(fullStatus.difyConfigured()).isTrue();
        assertThat(fullStatus.activeProvider()).isEqualTo("DIFY");
        assertThat(properties.getDify().resolveWorkflowId(
                com.auvdidao.a12teachingagent.ai.config.WorkflowCode.CONTENT_DRAFT
        )).isEqualTo("published-wf-06");
        assertThat(properties.getDify().resolveApiKey(
                com.auvdidao.a12teachingagent.ai.config.WorkflowCode.CONTENT_DRAFT
        )).isEqualTo("wf-06-key");
    }

    private AIWorkflowGatewayRouter router(AiWorkflowProperties properties) {
        return new AIWorkflowGatewayRouter(properties, mockGateway, difyGateway);
    }

    private AiWorkflowProperties properties(AiProvider provider, boolean fallbackToMock) {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(provider);
        properties.setFallbackToMock(fallbackToMock);
        return properties;
    }

    private void configureClarification(AiWorkflowProperties properties) {
        properties.getDify().getWorkflows().getClarification().setWorkflowId("published-wf-01");
        properties.getDify().getWorkflows().getClarification().setApiKey("wf-01-key");
    }

    private void configureEveryCallableWorkflow(AiWorkflowProperties properties) {
        properties.getDify().getWorkflows().getClarification().setWorkflowId("published-wf-01");
        properties.getDify().getWorkflows().getClarification().setApiKey("wf-01-key");
        properties.getDify().getWorkflows().getSummary().setWorkflowId("published-wf-02");
        properties.getDify().getWorkflows().getSummary().setApiKey("wf-02-key");
        properties.getDify().getWorkflows().getMaterial().setWorkflowId("published-wf-03");
        properties.getDify().getWorkflows().getMaterial().setApiKey("wf-03-key");
        properties.getDify().getWorkflows().getKnowledgeIntent().setWorkflowId("published-wf-04");
        properties.getDify().getWorkflows().getKnowledgeIntent().setApiKey("wf-04-key");
        properties.getDify().getWorkflows().getGenerationPlan().setWorkflowId("published-wf-05");
        properties.getDify().getWorkflows().getGenerationPlan().setApiKey("wf-05-key");
        properties.getDify().getWorkflows().getRevision().setWorkflowId("published-wf-07");
        properties.getDify().getWorkflows().getRevision().setApiKey("wf-07-key");
    }
}
