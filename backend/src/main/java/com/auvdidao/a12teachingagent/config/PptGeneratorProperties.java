package com.auvdidao.a12teachingagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "a12.ppt-skill")
public class PptGeneratorProperties {

    private String provider = "LEGACY";
    /** LEGACY keeps the existing Java renderer; HARNESS delegates async orchestration to ppt-harness. */
    private String generationMode = "LEGACY";
    private String harnessBaseUrl = "http://127.0.0.1:18091";
    private String runnerBaseUrl = "http://127.0.0.1:18090";
    private long timeoutSeconds = 600;
    private long pollIntervalMillis = 1000;
    private String storageDir = "./data/generated/ppt";
    private String stylePreset = "forest-research";
    private String outlineProvider = "FIXTURE";
    private boolean fixtureEnabled = false;
    private String kimiBaseUrl = "https://api.moonshot.ai/v1";
    private String kimiApiKey;
    private String kimiModel = "kimi-k2.6";
    private long kimiTimeoutSeconds = 120;
    private int kimiMaxCompletionTokens = 6000;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getGenerationMode() { return generationMode; }
    public void setGenerationMode(String generationMode) { this.generationMode = generationMode; }
    public String getHarnessBaseUrl() { return harnessBaseUrl; }
    public void setHarnessBaseUrl(String harnessBaseUrl) { this.harnessBaseUrl = harnessBaseUrl; }
    public boolean isHarnessEnabled() {
        return "HARNESS".equalsIgnoreCase(generationMode) || "HARNESS".equalsIgnoreCase(provider);
    }
    public String getRunnerBaseUrl() { return runnerBaseUrl; }
    public void setRunnerBaseUrl(String runnerBaseUrl) { this.runnerBaseUrl = runnerBaseUrl; }
    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public long getPollIntervalMillis() { return pollIntervalMillis; }
    public void setPollIntervalMillis(long pollIntervalMillis) { this.pollIntervalMillis = pollIntervalMillis; }
    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public String getStylePreset() { return stylePreset; }
    public void setStylePreset(String stylePreset) { this.stylePreset = stylePreset; }
    public String getOutlineProvider() { return outlineProvider; }
    public void setOutlineProvider(String outlineProvider) { this.outlineProvider = outlineProvider; }
    public boolean isFixtureEnabled() { return fixtureEnabled; }
    public void setFixtureEnabled(boolean fixtureEnabled) { this.fixtureEnabled = fixtureEnabled; }
    public String getKimiBaseUrl() { return kimiBaseUrl; }
    public void setKimiBaseUrl(String kimiBaseUrl) { this.kimiBaseUrl = kimiBaseUrl; }
    public String getKimiApiKey() { return kimiApiKey; }
    public void setKimiApiKey(String kimiApiKey) { this.kimiApiKey = kimiApiKey; }
    public String getKimiModel() { return kimiModel; }
    public void setKimiModel(String kimiModel) { this.kimiModel = kimiModel; }
    public long getKimiTimeoutSeconds() { return kimiTimeoutSeconds; }
    public void setKimiTimeoutSeconds(long kimiTimeoutSeconds) { this.kimiTimeoutSeconds = kimiTimeoutSeconds; }
    public int getKimiMaxCompletionTokens() { return kimiMaxCompletionTokens; }
    public void setKimiMaxCompletionTokens(int kimiMaxCompletionTokens) { this.kimiMaxCompletionTokens = kimiMaxCompletionTokens; }
}
