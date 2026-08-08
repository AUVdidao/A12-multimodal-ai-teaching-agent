package com.auvdidao.a12teachingagent.ai.kimi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KimiStructuredExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KimiChatClient client = mock(KimiChatClient.class);
    private final KimiStructuredExecutor executor = new KimiStructuredExecutor(objectMapper, client);
    private final JsonNode responseFormat = MaterialAnalysisStructuredContract.responseFormat(objectMapper);
    private final List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", "Analyze"));

    @Test
    void mapsValidStructuredOutput() {
        when(client.complete(any(KimiChatRequest.class))).thenReturn(response("""
                {"summary":"A summary","keywords":["light"],"teachingUses":["discussion"]}
                """, "stop"));

        MaterialAnalysisModelOutput output = execute();

        assertThat(output.summary()).isEqualTo("A summary");
        assertThat(output.keywords()).containsExactly("light");
        assertThat(output.teachingUses()).containsExactly("discussion");
        verify(client).complete(any(KimiChatRequest.class));
    }

    @Test
    void rejectsObjectEntriesInKeywords() {
        when(client.complete(any(KimiChatRequest.class))).thenReturn(response("""
                {"summary":"A summary","keywords":[{"value":"light"}],"teachingUses":[]}
                """, "stop"));

        assertInvalidResponse();
    }

    @Test
    void rejectsObjectEntriesInTeachingUses() {
        when(client.complete(any(KimiChatRequest.class))).thenReturn(response("""
                {"summary":"A summary","keywords":[],"teachingUses":[{"value":"discussion"}]}
                """, "stop"));

        assertInvalidResponse();
    }

    @Test
    void rejectsSchemaFieldsOutsideTheContract() {
        when(client.complete(any(KimiChatRequest.class))).thenReturn(response("""
                {"summary":"A summary","keywords":[],"teachingUses":[],"suggestedChunks":[]}
                """, "stop"));

        assertInvalidResponse();
    }

    @Test
    void rejectsInvalidJson() {
        when(client.complete(any(KimiChatRequest.class))).thenReturn(response("not-json", "stop"));

        assertInvalidResponse();
    }

    @Test
    void rejectsLengthFinishReasonBeforeMapping() {
        when(client.complete(any(KimiChatRequest.class))).thenReturn(response("not-json", "length"));

        assertThatThrownBy(this::execute)
                .isInstanceOf(KimiClientException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void acceptsMissingFinishReasonForCompatibility() {
        when(client.complete(any(KimiChatRequest.class))).thenReturn(response("""
                {"summary":"A summary","keywords":[],"teachingUses":[]}
                """, null));

        assertThat(execute().summary()).isEqualTo("A summary");
    }

    @Test
    void acceptsUnknownFinishReasonForCompatibility() {
        when(client.complete(any(KimiChatRequest.class))).thenReturn(response("""
                {"summary":"A summary","keywords":[],"teachingUses":[]}
                """, "future_reason"));

        assertThat(execute().summary()).isEqualTo("A summary");
    }

    private MaterialAnalysisModelOutput execute() {
        return executor.execute(
                messages,
                "kimi-k2.6",
                1000,
                30,
                responseFormat,
                MaterialAnalysisModelOutput.class
        );
    }

    private void assertInvalidResponse() {
        assertThatThrownBy(this::execute)
                .isInstanceOf(KimiClientException.class)
                .satisfies(exception -> {
                    KimiClientException clientException = (KimiClientException) exception;
                    assertThat(clientException.getCode()).isEqualTo("KIMI_INVALID_RESPONSE");
                    assertThat(clientException.getStatusCode()).isEqualTo(502);
                });
    }

    private KimiChatResponse response(String content, String finishReason) {
        return new KimiChatResponse(content, finishReason);
    }
}
