package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
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
    private final KimiAIWorkflowGateway kimiGateway = mock(KimiAIWorkflowGateway.class);
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
    private final ClarificationResponse kimiResponse = new ClarificationResponse(
            "kimi:kimi-k2.6:WF-01",
            List.of(),
            List.of(),
            Map.of(),
            "Continue"
    );

    @Test
    void mockProviderNeverCallsKimi() {
        AiWorkflowProperties workflowProperties = workflowProperties(AiProvider.MOCK, true);
        KimiAssistantProperties kimiProperties = configuredKimi();
        when(mockGateway.clarifyRequirement(request)).thenReturn(mockResponse);

        AIWorkflowGatewayRouter router = router(workflowProperties, kimiProperties);

        assertThat(router.clarifyRequirement(request)).isSameAs(mockResponse);
        verify(mockGateway).clarifyRequirement(request);
        verifyNoInteractions(kimiGateway);
        assertThat(router.status().activeProvider()).isEqualTo("MOCK");
    }

    @Test
    void configuredKimiUsesRealProvider() {
        AiWorkflowProperties workflowProperties = workflowProperties(AiProvider.KIMI, true);
        KimiAssistantProperties kimiProperties = configuredKimi();
        when(kimiGateway.clarifyRequirement(request)).thenReturn(kimiResponse);

        AIWorkflowGatewayRouter router = router(workflowProperties, kimiProperties);

        assertThat(router.clarifyRequirement(request)).isSameAs(kimiResponse);
        verify(kimiGateway).clarifyRequirement(request);
        verify(mockGateway, never()).clarifyRequirement(request);
        assertThat(router.status().activeProvider()).isEqualTo("KIMI");
        assertThat(router.status().providerConfigured()).isTrue();
    }

    @Test
    void missingKimiConfigurationFallsBackToMock() {
        AiWorkflowProperties workflowProperties = workflowProperties(AiProvider.KIMI, true);
        KimiAssistantProperties kimiProperties = new KimiAssistantProperties();
        when(mockGateway.clarifyRequirement(request)).thenReturn(mockResponse);

        AIWorkflowGatewayRouter router = router(workflowProperties, kimiProperties);

        assertThat(router.clarifyRequirement(request)).isSameAs(mockResponse);
        verify(mockGateway).clarifyRequirement(request);
        verifyNoInteractions(kimiGateway);
        assertThat(router.status().activeProvider()).isEqualTo("MOCK");
        assertThat(router.status().providerConfigured()).isFalse();
        assertThat(router.status().message()).contains("configuration: missing");
    }

    @Test
    void missingKimiConfigurationWithoutFallbackThrowsUnavailable() {
        AiWorkflowProperties workflowProperties = workflowProperties(AiProvider.KIMI, false);
        KimiAssistantProperties kimiProperties = new KimiAssistantProperties();
        AIWorkflowGatewayRouter router = router(workflowProperties, kimiProperties);

        assertThatThrownBy(() -> router.clarifyRequirement(request))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("not configured");
        verifyNoInteractions(mockGateway, kimiGateway);
        assertThat(router.status().activeProvider()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void providerFailureFallsBackAndReportsSanitizedReason() {
        AiWorkflowProperties workflowProperties = workflowProperties(AiProvider.KIMI, true);
        KimiAssistantProperties kimiProperties = configuredKimi();
        when(kimiGateway.clarifyRequirement(request))
                .thenThrow(new AiWorkflowUnavailableException("WF-01: Kimi returned HTTP 500."));
        when(mockGateway.clarifyRequirement(request)).thenReturn(mockResponse);

        AIWorkflowGatewayRouter router = router(workflowProperties, kimiProperties);

        assertThat(router.clarifyRequirement(request)).isSameAs(mockResponse);
        assertThat(router.status().activeProvider()).isEqualTo("MOCK");
        assertThat(router.status().message()).contains("Last fallback reason: WF-01: Kimi returned HTTP 500.");
    }

    private AIWorkflowGatewayRouter router(
            AiWorkflowProperties workflowProperties,
            KimiAssistantProperties kimiProperties
    ) {
        return new AIWorkflowGatewayRouter(workflowProperties, kimiProperties, mockGateway, kimiGateway);
    }

    private AiWorkflowProperties workflowProperties(AiProvider provider, boolean fallbackToMock) {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(provider);
        properties.setFallbackToMock(fallbackToMock);
        return properties;
    }

    private KimiAssistantProperties configuredKimi() {
        KimiAssistantProperties properties = new KimiAssistantProperties();
        properties.setApiKey("test-key");
        properties.setWorkflowModel("kimi-k2.6");
        return properties;
    }
}
