package com.auvdidao.a12teachingagent.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

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
        return Arrays.stream(WorkflowCode.values()).allMatch(this::isDifyConfigured);
    }

    public boolean isDifyConfigured(WorkflowCode workflowCode) {
        return difyConfigurationIssue(workflowCode) == null;
    }

    public String difyConfigurationIssue(WorkflowCode workflowCode) {
        if (dify == null || !StringUtils.hasText(dify.getBaseUrl())) {
            return "Dify base URL is missing";
        }
        if (!StringUtils.hasText(dify.resolveWorkflowId(workflowCode))) {
            return "published workflow ID is missing for " + workflowCode.code();
        }
        if (!StringUtils.hasText(dify.resolveApiKey(workflowCode))) {
            return "API key is missing for " + workflowCode.code();
        }
        return null;
    }

    public List<String> configuredWorkflowCodes() {
        return Arrays.stream(WorkflowCode.values())
                .filter(this::isDifyConfigured)
                .map(WorkflowCode::code)
                .toList();
    }

    public List<String> missingWorkflowCodes() {
        return Arrays.stream(WorkflowCode.values())
                .filter(workflowCode -> !isDifyConfigured(workflowCode))
                .map(WorkflowCode::code)
                .toList();
    }

    public boolean areCallableDifyWorkflowsConfigured() {
        return Arrays.stream(WorkflowCode.values())
                .filter(WorkflowCode::isCallable)
                .allMatch(this::isDifyConfigured);
    }

    public List<String> callableWorkflowCodes() {
        return Arrays.stream(WorkflowCode.values())
                .filter(WorkflowCode::isCallable)
                .map(WorkflowCode::code)
                .toList();
    }

    public static class Dify {

        private String baseUrl = "https://api.dify.ai/v1";
        // Retained for configuration compatibility. Real routing requires one published ID per mapped workflow.
        private String workflowId;
        private String apiKey;
        private String userPrefix = "a12-project-";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(60);
        private Workflows workflows = new Workflows();

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

        public String getUserPrefix() {
            return userPrefix;
        }

        public void setUserPrefix(String userPrefix) {
            this.userPrefix = userPrefix;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Workflows getWorkflows() {
            return workflows;
        }

        public void setWorkflows(Workflows workflows) {
            this.workflows = workflows;
        }

        public String resolveWorkflowId(WorkflowCode workflowCode) {
            Workflow workflow = workflow(workflowCode);
            return workflow == null ? null : workflow.resolvedWorkflowId();
        }

        public String resolveApiKey(WorkflowCode workflowCode) {
            Workflow workflow = workflow(workflowCode);
            if (workflow != null && StringUtils.hasText(workflow.getApiKey())) {
                return workflow.getApiKey().strip();
            }
            return StringUtils.hasText(apiKey) ? apiKey.strip() : null;
        }

        private Workflow workflow(WorkflowCode workflowCode) {
            if (workflows == null || workflowCode == null) {
                return null;
            }
            return switch (workflowCode) {
                case CLARIFICATION -> workflows.getClarification();
                case REQUIREMENT_SUMMARY -> workflows.getSummary();
                case MATERIAL_ANALYSIS -> workflows.getMaterial();
                case KNOWLEDGE_AND_TEACHING_INTENT -> workflows.getKnowledgeIntent();
                case GENERATION_PLAN -> workflows.getGenerationPlan();
                case CONTENT_DRAFT -> workflows.getContentDraft();
                case REVISION -> workflows.getRevision();
            };
        }
    }

    public static class Workflows {

        private Workflow clarification = new Workflow();
        private Workflow summary = new Workflow();
        private Workflow material = new Workflow();
        private Workflow knowledgeIntent = new Workflow();
        private Workflow generationPlan = new Workflow();
        private Workflow contentDraft = new Workflow();
        private Workflow revision = new Workflow();

        public Workflow getClarification() {
            return clarification;
        }

        public void setClarification(Workflow clarification) {
            this.clarification = clarification;
        }

        public Workflow getSummary() {
            return summary;
        }

        public void setSummary(Workflow summary) {
            this.summary = summary;
        }

        public Workflow getMaterial() {
            return material;
        }

        public void setMaterial(Workflow material) {
            this.material = material;
        }

        public Workflow getKnowledgeIntent() {
            return knowledgeIntent;
        }

        public void setKnowledgeIntent(Workflow knowledgeIntent) {
            this.knowledgeIntent = knowledgeIntent;
        }

        public Workflow getGenerationPlan() {
            return generationPlan;
        }

        public void setGenerationPlan(Workflow generationPlan) {
            this.generationPlan = generationPlan;
        }

        public Workflow getContentDraft() {
            return contentDraft;
        }

        public void setContentDraft(Workflow contentDraft) {
            this.contentDraft = contentDraft;
        }

        public Workflow getRevision() {
            return revision;
        }

        public void setRevision(Workflow revision) {
            this.revision = revision;
        }
    }

    public static class Workflow {

        private String id;
        private String workflowId;
        private String apiKey;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
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

        private String resolvedWorkflowId() {
            if (StringUtils.hasText(workflowId)) {
                return workflowId.strip();
            }
            return StringUtils.hasText(id) ? id.strip() : null;
        }
    }
}
