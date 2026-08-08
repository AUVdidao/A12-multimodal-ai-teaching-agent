package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationQuestion;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisRequest;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.ai.kimi.KimiChatClient;
import com.auvdidao.a12teachingagent.ai.kimi.KimiChatRequest;
import com.auvdidao.a12teachingagent.ai.kimi.KimiChatResponse;
import com.auvdidao.a12teachingagent.ai.kimi.KimiClientException;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KimiAIWorkflowGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KimiAssistantProperties properties = configuredProperties();
    private final KimiChatClient client = mock(KimiChatClient.class);
    private final KimiAIWorkflowGateway gateway = new KimiAIWorkflowGateway(objectMapper, properties, client);

    @Test
    void preservesKimiFailureCodeAndHttpStatusInsteadOfReportingOnlyUnavailable() {
        when(client.complete(anyList(), anyString(), anyInt(), anyInt()))
                .thenThrow(new KimiClientException(
                        "KIMI_REQUEST_FAILED",
                        "Kimi returned HTTP 401: code=invalid_auth; Invalid Authentication",
                        401
                ));

        assertThatThrownBy(() -> gateway.clarifyRequirement(new ClarificationRequest(
                1L,
                "生成一节生物课",
                List.of(),
                GenerationMode.STANDARD
        )))
                .isInstanceOf(AiWorkflowUnavailableException.class)
                .hasMessageContaining("WF-01")
                .hasMessageContaining("KIMI_REQUEST_FAILED")
                .hasMessageContaining("HTTP 401")
                .extracting(exception -> ((AiWorkflowUnavailableException) exception).getProviderCode())
                .isEqualTo("KIMI_REQUEST_FAILED");
    }

    @Test
    void clarificationMapsStrictJsonAndInjectsWorkflowReference() {
        when(client.complete(anyList(), anyString(), anyInt(), anyInt())).thenReturn("""
                {
                  "missingFields": ["gradeLevel"],
                  "questions": [{"targetField":"gradeLevel","question":"这节课面向哪个年级？"}],
                  "suggestedFields": {"gradeLevel": "小学五年级"},
                  "nextAction": "请补充年级后继续。"
                }
                """);

        var response = gateway.clarifyRequirement(new ClarificationRequest(
                1L,
                "生成一节分数课",
                List.of(),
                GenerationMode.STANDARD
        ));

        assertThat(response.workflow()).isEqualTo("kimi:kimi-k2.6:WF-01");
        assertThat(response.missingFields()).containsExactly("gradeLevel");
        assertThat(response.questions()).containsExactly(
                new ClarificationQuestion("gradeLevel", "这节课面向哪个年级？"));
        assertThat(response.suggestedFields()).containsEntry("gradeLevel", "小学五年级");
    }

    @Test
    void clarificationUnwrapsResultObjectAndKeepsTheStableDtoContract() {
        when(client.complete(anyList(), anyString(), anyInt(), anyInt())).thenReturn("""
                {"result":{"missingFields":["lessonDuration"],"questions":[{"targetField":"lessonDuration","question":"本节课需要安排多少分钟？"}],"suggestedFields":{"lessonDuration":"45"},"nextAction":"请补充课时后继续。"}}
                """);

        var response = gateway.clarifyRequirement(new ClarificationRequest(
                3L, "生成光合作用课程", List.of(), GenerationMode.STANDARD));

        assertThat(response.missingFields()).containsExactly("lessonDuration");
        assertThat(response.questions()).containsExactly(
                new ClarificationQuestion("lessonDuration", "本节课需要安排多少分钟？"));
        assertThat(response.suggestedFields()).containsEntry("lessonDuration", "45");
        assertThat(response.nextAction()).isEqualTo("请补充课时后继续。");
    }

    @Test
    void clarificationUnwrapsResponseTextContainingJson() {
        when(client.complete(anyList(), anyString(), anyInt(), anyInt())).thenReturn("""
                {"response":"{\\"missingFields\\":[],\\"questions\\":[],\\"suggestedFields\\":{},\\"nextAction\\":\\"continue\\"}"}
                """);

        var response = gateway.clarifyRequirement(new ClarificationRequest(
                4L, "生成光合作用课程", List.of(), GenerationMode.STANDARD));

        assertThat(response.missingFields()).isEmpty();
        assertThat(response.questions()).isEmpty();
        assertThat(response.suggestedFields()).isEmpty();
        assertThat(response.nextAction()).isEqualTo("continue");
    }

    @Test
    void clarificationPromptStatesExactFieldTypesAndNames() {
        when(client.complete(anyList(), anyString(), anyInt(), anyInt())).thenReturn("""
                {"missingFields":[],"questions":[],"suggestedFields":{},"nextAction":"continue"}
                """);

        gateway.clarifyRequirement(new ClarificationRequest(
                5L, "生成光合作用课程", List.of(), GenerationMode.STANDARD));

        ArgumentCaptor<List> messages = ArgumentCaptor.forClass(List.class);
        verify(client).complete(messages.capture(), anyString(), anyInt(), anyInt());
        Map<?, ?> userMessage = (Map<?, ?>) messages.getValue().get(1);
        String prompt = String.valueOf(userMessage.get("content"));
        assertThat(prompt).contains("missingFields", "questions", "suggestedFields", "nextAction");
        assertThat(prompt).contains(
                "every question is an object",
                "targetField explicitly identifies",
                "Do not derive question ownership from missingFields order");
    }

    @Test
    void invalidJsonIsRepairedOnceBeforeMapping() {
        when(client.complete(anyList(), anyString(), anyInt(), anyInt()))
                .thenReturn("not-json")
                .thenReturn("""
                        {
                          "missingFields": [],
                          "questions": [],
                          "suggestedFields": {},
                          "nextAction": "需求信息已足够。"
                        }
                        """);

        var response = gateway.clarifyRequirement(new ClarificationRequest(
                2L,
                "生成一节完整数学课",
                List.of("gradeLevel", "lessonDuration", "outputTypes"),
                GenerationMode.STANDARD
        ));

        assertThat(response.nextAction()).isEqualTo("需求信息已足够。");
        verify(client, times(2)).complete(anyList(), anyString(), anyInt(), anyInt());
    }

    @Test
    void materialAnalysisUsesStrictStructuredOutputAndPreservesPublicContract() {
        when(client.complete(any(KimiChatRequest.class))).thenReturn(new KimiChatResponse("""
                {"summary":"Photosynthesis converts light energy.","keywords":["photosynthesis","light"],"teachingUses":["diagram discussion"]}
                """, "stop"));

        var response = gateway.analyzeMaterial(new MaterialAnalysisRequest(
                7L,
                "photosynthesis.txt",
                "TEXT",
                "teaching evidence",
                "Plants use light energy to synthesize organic matter.",
                List.of("TEACHING_EVIDENCE"),
                null
        ));

        assertThat(response.workflow()).isEqualTo("kimi:kimi-k2.6:WF-03");
        assertThat(response.status()).isEqualTo("PARSED");
        assertThat(response.summary()).isEqualTo("Photosynthesis converts light energy.");
        assertThat(response.keywords()).containsExactly("photosynthesis", "light");
        assertThat(response.teachingUses()).containsExactly("diagram discussion");
        assertThat(response.suggestedChunks()).isEmpty();

        ArgumentCaptor<KimiChatRequest> requestCaptor = ArgumentCaptor.forClass(KimiChatRequest.class);
        verify(client).complete(requestCaptor.capture());
        verify(client, never()).complete(anyList(), anyString(), anyInt(), anyInt());

        KimiChatRequest request = requestCaptor.getValue();
        assertThat(request.responseFormat().path("type").asText()).isEqualTo("json_schema");
        assertThat(request.responseFormat().path("json_schema").path("name").asText())
                .isEqualTo("material_analysis");
        assertThat(request.responseFormat().path("json_schema").path("strict").asBoolean()).isTrue();
        var schema = request.responseFormat().path("json_schema").path("schema");
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .containsExactly("summary", "keywords", "teachingUses");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void emptyCandidateKnowledgeDoesNotCallModelOrInventSources() {
        var response = gateway.retrieveKnowledge(new KnowledgeRetrievalRequest(
                1L,
                "数学",
                "分数的意义",
                List.of("分数"),
                List.of()
        ));

        assertThat(response.snippets()).isEmpty();
        assertThat(response.retrievalNote()).contains("未调用模型补造知识");
        verifyNoInteractions(client);
    }

    private KimiAssistantProperties configuredProperties() {
        KimiAssistantProperties value = new KimiAssistantProperties();
        value.setApiKey("test-key");
        value.setWorkflowModel("kimi-k2.6");
        value.setWorkflowMaxCompletionTokens(1000);
        value.setWorkflowTimeoutSeconds(30);
        return value;
    }
}
