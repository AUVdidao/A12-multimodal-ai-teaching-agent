package com.auvdidao.a12teachingagent.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "a12.ai")
public class AiWorkflowProperties {

    private AiProvider provider = AiProvider.KIMI;
    private boolean fallbackToMock = true;

    public AiProvider getProvider() {
        return provider;
    }

    public void setProvider(AiProvider provider) {
        this.provider = provider;
    }

    public boolean isFallbackToMock() {
        return fallbackToMock;
    }

    public void setFallbackToMock(boolean fallbackToMock) {
        this.fallbackToMock = fallbackToMock;
    }
}
