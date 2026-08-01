package com.auvdidao.a12teachingagent.ai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiWorkflowPropertiesTest {

    @Test
    void defaultsToKimiWithMockFallback() {
        AiWorkflowProperties properties = new AiWorkflowProperties();

        assertThat(properties.getProvider()).isEqualTo(AiProvider.KIMI);
        assertThat(properties.isFallbackToMock()).isTrue();
    }

    @Test
    void providerAndFallbackCanBeOverridden() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.MOCK);
        properties.setFallbackToMock(false);

        assertThat(properties.getProvider()).isEqualTo(AiProvider.MOCK);
        assertThat(properties.isFallbackToMock()).isFalse();
    }
}
