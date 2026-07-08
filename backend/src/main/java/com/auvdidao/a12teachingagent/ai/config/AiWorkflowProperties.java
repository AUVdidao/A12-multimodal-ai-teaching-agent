package com.auvdidao.a12teachingagent.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "a12.ai")
public class AiWorkflowProperties {

    private AiProvider provider = AiProvider.MOCK;
    private boolean fallbackToMock = true;
    private Dify dify = new Dify();

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

    public Dify getDify() {
        return dify;
    }

    public void setDify(Dify dify) {
        this.dify = dify;
    }

    public boolean isDifyConfigured() {
        return dify != null
                && StringUtils.hasText(dify.getBaseUrl())
                && StringUtils.hasText(dify.getWorkflowId())
                && StringUtils.hasText(dify.getApiKey());
    }

    public static class Dify {

        private String baseUrl = "https://api.dify.ai/v1";
        private String workflowId;
        private String apiKey;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getWorkflowId() {
            return workflowId;
        }

        public void setWorkflowId(String workflowId) {
            this.workflowId = workflowId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
