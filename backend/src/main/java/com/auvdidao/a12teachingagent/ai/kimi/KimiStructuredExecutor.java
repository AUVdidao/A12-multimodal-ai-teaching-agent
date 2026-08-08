package com.auvdidao.a12teachingagent.ai.kimi;

import com.auvdidao.a12teachingagent.ai.exception.AiFailureKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class KimiStructuredExecutor {

    private static final String INVALID_RESPONSE = "KIMI_INVALID_RESPONSE";

    private final ObjectMapper objectMapper;
    private final KimiChatClient kimiChatClient;

    public KimiStructuredExecutor(ObjectMapper objectMapper, KimiChatClient kimiChatClient) {
        this.objectMapper = objectMapper;
        this.kimiChatClient = kimiChatClient;
    }

    public <T> T execute(
            List<Map<String, String>> messages,
            String model,
            int maxCompletionTokens,
            int timeoutSeconds,
            JsonNode responseFormat,
            Class<T> responseType
    ) {
        KimiChatResponse response = kimiChatClient.complete(new KimiChatRequest(
                messages,
                model,
                maxCompletionTokens,
                timeoutSeconds,
                responseFormat
        ));

        if ("length".equalsIgnoreCase(response.finishReason())) {
            throw invalidResponse("Kimi structured output was truncated", AiFailureKind.TRUNCATED_RESPONSE);
        }

        JsonNode payload = parse(response.content());
        validate(payload, responseFormat);
        try {
            return objectMapper.treeToValue(payload, responseType);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidResponse("Kimi structured output does not match the target DTO", AiFailureKind.SCHEMA_MISMATCH);
        }
    }

    private JsonNode parse(String content) {
        try {
            JsonNode payload = objectMapper.readTree(content);
            if (payload == null || payload.isNull()) {
                throw invalidResponse("Kimi returned empty JSON", AiFailureKind.INVALID_JSON);
            }
            return payload;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidResponse("Kimi structured output is not valid JSON", AiFailureKind.INVALID_JSON);
        }
    }

    private void validate(JsonNode payload, JsonNode responseFormat) {
        JsonNode schemaNode = responseFormat == null
                ? null
                : responseFormat.path("json_schema").path("schema");
        if (schemaNode == null || schemaNode.isMissingNode() || schemaNode.isNull()) {
            throw invalidResponse("Kimi structured output schema is missing", AiFailureKind.SCHEMA_MISMATCH);
        }

        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaNode);
        Set<ValidationMessage> errors = schema.validate(payload);
        if (!errors.isEmpty()) {
            throw invalidResponse("Kimi structured output failed local schema validation", AiFailureKind.SCHEMA_MISMATCH);
        }
    }

    private static KimiClientException invalidResponse(String message, AiFailureKind failureKind) {
        return new KimiClientException(INVALID_RESPONSE, message, 502, failureKind);
    }
}
