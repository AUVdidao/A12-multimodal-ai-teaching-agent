package com.auvdidao.a12teachingagent.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelGatewayContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelExecutionContext context = new ModelExecutionContext(
            7, "11", "TEACHER", "trace-1", "11111111-1111-1111-1111-111111111111",
            "COURSE_OUTLINE", 30_000L, Map.of("scenario", "contract")
    );

    @Test
    void mockSupportsTextStructuredAndMultimodalDeterministically() throws Exception {
        MockModelGateway gateway = new MockModelGateway(objectMapper);
        ModelRequest request = new ModelRequest(
                "system", List.of(ModelMessage.user("question")), null, 128, 0.0, 5_000L,
                Map.of("mockStructuredJson", "{\"answer\":\"ok\"}"), List.of("test")
        );

        assertEquals("mock model completion", gateway.complete(context, request).content());
        StructuredOutputContract<Answer> contract = new StructuredOutputContract<>(
                "answer", objectMapper.readTree("""
                        {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}
                        """), true, Answer.class, RepairPolicy.NONE
        );
        assertEquals("ok", gateway.completeStructured(context, request, contract).value().answer());

        ModelRequest multimodalRequest = new ModelRequest(
                null,
                List.of(new ModelMessage(ModelMessage.Role.USER, "describe", List.of(
                        new ModelImageRef("https://example.com/image.png", "image/png")
                ))),
                null, 128, 0.0, 5_000L
        );
        assertEquals("mock multimodal completion", gateway.completeMultimodal(context, multimodalRequest).content());
    }

    @Test
    void contractsRejectUnboundedOrUnsafeInputs() {
        assertThrows(IllegalArgumentException.class, () -> new ModelImageRef("data:image/png;base64,AAAA", "image/png"));
        assertThrows(IllegalArgumentException.class, () -> new ModelRequest(
                null, List.of(ModelMessage.user("x")), null, 10, 0, 1_000,
                Map.of("apiKey", "should-not-be-here"), List.of()
        ));
    }

    public record Answer(String answer) {
    }
}
