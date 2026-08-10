package com.auvdidao.a12teachingagent.ai.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "a12.kimi")
public class KimiAssistantProperties {

    private String baseUrl = "https://api.moonshot.ai/v1";
    private String apiKey;
    private int connectTimeoutSeconds = 15;
    private int requestAttempts = 2;
    private long retryDelayMillis = 1200;

    private String assistantModel = "kimi-k2.6";
    private int assistantTimeoutSeconds = 90;
    private int assistantMaxCompletionTokens = 1600;

    private String workflowModel = "kimi-k2.6";
    private int workflowTimeoutSeconds = 180;
    private int workflowMaxCompletionTokens = 8000;
    private int workflowMaxInputCharacters = 60000;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
    public int getRequestAttempts() { return requestAttempts; }
    public void setRequestAttempts(int requestAttempts) { this.requestAttempts = requestAttempts; }
    public long getRetryDelayMillis() { return retryDelayMillis; }
    public void setRetryDelayMillis(long retryDelayMillis) { this.retryDelayMillis = retryDelayMillis; }
    public String getAssistantModel() { return assistantModel; }
    public void setAssistantModel(String assistantModel) { this.assistantModel = assistantModel; }
    public int getAssistantTimeoutSeconds() { return assistantTimeoutSeconds; }
    public void setAssistantTimeoutSeconds(int assistantTimeoutSeconds) { this.assistantTimeoutSeconds = assistantTimeoutSeconds; }
    public int getAssistantMaxCompletionTokens() { return assistantMaxCompletionTokens; }
    public void setAssistantMaxCompletionTokens(int assistantMaxCompletionTokens) { this.assistantMaxCompletionTokens = assistantMaxCompletionTokens; }
    public String getWorkflowModel() { return workflowModel; }
    public void setWorkflowModel(String workflowModel) { this.workflowModel = workflowModel; }
    public int getWorkflowTimeoutSeconds() { return workflowTimeoutSeconds; }
    public void setWorkflowTimeoutSeconds(int workflowTimeoutSeconds) { this.workflowTimeoutSeconds = workflowTimeoutSeconds; }
    public int getWorkflowMaxCompletionTokens() { return workflowMaxCompletionTokens; }
    public void setWorkflowMaxCompletionTokens(int workflowMaxCompletionTokens) { this.workflowMaxCompletionTokens = workflowMaxCompletionTokens; }
    public int getWorkflowMaxInputCharacters() { return workflowMaxInputCharacters; }
    public void setWorkflowMaxInputCharacters(int workflowMaxInputCharacters) { this.workflowMaxInputCharacters = workflowMaxInputCharacters; }

    public boolean isAssistantConfigured() {
        return StringUtils.hasText(baseUrl)
                && StringUtils.hasText(apiKey)
                && StringUtils.hasText(assistantModel);
    }

    public boolean isWorkflowConfigured() {
        return StringUtils.hasText(baseUrl)
                && StringUtils.hasText(apiKey)
                && StringUtils.hasText(workflowModel);
    }
}
