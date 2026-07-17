package com.auvdidao.a12teachingagent.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiWorkflowPropertiesTest {

    @Test
    void bindsSevenPublishedWorkflowSlotsAndPerWorkflowKeyFallback() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("a12.ai.provider", "DIFY");
        values.put("a12.ai.fallback-to-mock", "false");
        values.put("a12.ai.dify.base-url", "https://dify.example.test/v1");
        values.put("a12.ai.dify.api-key", "default-key");
        values.put("a12.ai.dify.user-prefix", "project-user-");
        values.put("a12.ai.dify.connect-timeout", "250ms");
        values.put("a12.ai.dify.read-timeout", "12s");
        values.put("a12.ai.dify.workflows.clarification.workflow-id", "published-wf-01");
        values.put("a12.ai.dify.workflows.summary.workflow-id", "published-wf-02");
        values.put("a12.ai.dify.workflows.summary.api-key", "wf-02-key");
        values.put("a12.ai.dify.workflows.material.workflow-id", "published-wf-03");
        values.put("a12.ai.dify.workflows.knowledge-intent.workflow-id", "published-wf-04");
        values.put("a12.ai.dify.workflows.generation-plan.workflow-id", "published-wf-05");
        values.put("a12.ai.dify.workflows.content-draft.workflow-id", "published-wf-06");
        values.put("a12.ai.dify.workflows.revision.workflow-id", "published-wf-07");

        AiWorkflowProperties properties = new Binder(new MapConfigurationPropertySource(values))
                .bind("a12.ai", Bindable.of(AiWorkflowProperties.class))
                .orElseThrow(() -> new AssertionError("AI workflow properties were not bound"));

        assertThat(properties.getProvider()).isEqualTo(AiProvider.DIFY);
        assertThat(properties.isFallbackToMock()).isFalse();
        assertThat(properties.isDifyConfigured()).isTrue();
        assertThat(properties.configuredWorkflowCodes())
                .containsExactly("WF-01", "WF-02", "WF-03", "WF-04", "WF-05", "WF-06", "WF-07");
        assertThat(properties.getDify().resolveWorkflowId(WorkflowCode.CONTENT_DRAFT))
                .isEqualTo("published-wf-06");
        assertThat(properties.getDify().resolveApiKey(WorkflowCode.CLARIFICATION)).isEqualTo("default-key");
        assertThat(properties.getDify().resolveApiKey(WorkflowCode.REQUIREMENT_SUMMARY)).isEqualTo("wf-02-key");
        assertThat(properties.getDify().getConnectTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.getDify().getReadTimeout()).isEqualTo(Duration.ofSeconds(12));
        assertThat(properties.getDify().getUserPrefix()).isEqualTo("project-user-");
    }

    @Test
    void legacySharedWorkflowIdDoesNotSatisfyPerWorkflowPublishedIdRequirement() {
        Map<String, Object> values = Map.of(
                "a12.ai.dify.workflow-id", "legacy-shared-id",
                "a12.ai.dify.api-key", "default-key"
        );

        AiWorkflowProperties properties = new Binder(new MapConfigurationPropertySource(values))
                .bind("a12.ai", Bindable.of(AiWorkflowProperties.class))
                .orElseThrow(() -> new AssertionError("AI workflow properties were not bound"));

        assertThat(properties.isDifyConfigured()).isFalse();
        assertThat(properties.configuredWorkflowCodes()).isEmpty();
        assertThat(properties.missingWorkflowCodes())
                .containsExactly("WF-01", "WF-02", "WF-03", "WF-04", "WF-05", "WF-06", "WF-07");
    }
}
