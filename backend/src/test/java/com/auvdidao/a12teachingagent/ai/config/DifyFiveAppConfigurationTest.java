package com.auvdidao.a12teachingagent.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "DIFY_APP01_REQUIREMENT_API_KEY=app-01-key",
        "DIFY_APP01_REQUIREMENT_WORKFLOW_ID=app-01-workflow",
        "DIFY_APP02_MATERIAL_INTENT_API_KEY=app-02-key",
        "DIFY_APP02_MATERIAL_INTENT_WORKFLOW_ID=app-02-workflow",
        "DIFY_APP03_GENERATION_PLAN_API_KEY=app-03-key",
        "DIFY_APP03_GENERATION_PLAN_WORKFLOW_ID=app-03-workflow",
        "DIFY_APP04_CONTENT_DRAFT_API_KEY=app-04-key",
        "DIFY_APP04_CONTENT_DRAFT_WORKFLOW_ID=app-04-workflow",
        "DIFY_APP05_REVISION_API_KEY=app-05-key",
        "DIFY_APP05_REVISION_WORKFLOW_ID=app-05-workflow"
})
@ActiveProfiles("test")
class DifyFiveAppConfigurationTest {

    @Autowired
    private AiWorkflowProperties properties;

    @Test
    void mapsSevenLogicalWorkflowsToFivePhysicalApps() {
        AiWorkflowProperties.Dify dify = properties.getDify();

        assertSharedApp(dify, WorkflowCode.CLARIFICATION, WorkflowCode.REQUIREMENT_SUMMARY,
                "app-01-key", "app-01-workflow");
        assertSharedApp(dify, WorkflowCode.MATERIAL_ANALYSIS, WorkflowCode.KNOWLEDGE_AND_TEACHING_INTENT,
                "app-02-key", "app-02-workflow");
        assertSingleApp(dify, WorkflowCode.GENERATION_PLAN, "app-03-key", "app-03-workflow");
        assertSingleApp(dify, WorkflowCode.CONTENT_DRAFT, "app-04-key", "app-04-workflow");
        assertSingleApp(dify, WorkflowCode.REVISION, "app-05-key", "app-05-workflow");
        assertThat(properties.isDifyConfigured()).isTrue();
    }

    private static void assertSharedApp(
            AiWorkflowProperties.Dify dify,
            WorkflowCode first,
            WorkflowCode second,
            String apiKey,
            String workflowId
    ) {
        assertSingleApp(dify, first, apiKey, workflowId);
        assertSingleApp(dify, second, apiKey, workflowId);
    }

    private static void assertSingleApp(
            AiWorkflowProperties.Dify dify,
            WorkflowCode workflowCode,
            String apiKey,
            String workflowId
    ) {
        assertThat(dify.resolveApiKey(workflowCode)).isEqualTo(apiKey);
        assertThat(dify.resolveWorkflowId(workflowCode)).isEqualTo(workflowId);
    }
}
