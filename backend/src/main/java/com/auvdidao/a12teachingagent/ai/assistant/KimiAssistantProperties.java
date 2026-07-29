package com.auvdidao.a12teachingagent.ai.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "a12.kimi")
public class KimiAssistantProperties {

    private String baseUrl = "https://api.moonshot.ai/v1";
    private String apiKey;
    private String assistantModel = "kimi-k2.6";
    private int assistantTimeoutSeconds = 90;
    private int assistantMaxCompletionTokens = 1600;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getAssistantModel() { return assistantModel; }
    public void setAssistantModel(String assistantModel) { this.assistantModel = assistantModel; }
    public int getAssistantTimeoutSeconds() { return assistantTimeoutSeconds; }
    public void setAssistantTimeoutSeconds(int assistantTimeoutSeconds) { this.assistantTimeoutSeconds = assistantTimeoutSeconds; }
    public int getAssistantMaxCompletionTokens() { return assistantMaxCompletionTokens; }
    public void setAssistantMaxCompletionTokens(int assistantMaxCompletionTokens) { this.assistantMaxCompletionTokens = assistantMaxCompletionTokens; }
}
