package com.auvdidao.a12teachingagent.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixture-only gateway. It never represents a real provider execution. */
@Service
@Primary
public class MockModelGateway implements ModelGateway {
    private final ObjectMapper objectMapper;

    public MockModelGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelResult complete(ModelExecutionContext context, ModelRequest request) {
        requireInput(context, request);
        return new ModelResult(ModelProvider.MOCK, model(request), "mock-request",
                request.metadata().getOrDefault("mockText", "mock model completion"), "stop", ModelUsage.empty(), 0L, List.of());
    }

    @Override
    public <T> StructuredModelResult<T> completeStructured(ModelExecutionContext context, ModelRequest request,
                                                            StructuredOutputContract<T> contract) {
        requireInput(context, request);
        if (contract == null) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "STRUCTURED_CONTRACT_MISSING", 0,
                    "Structured output contract is required");
        }
        JsonNode payload = scriptedOrSample(contract, request);
        Set<ValidationMessage> errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(contract.schemaDefinition()).validate(payload);
        if (!errors.isEmpty()) {
            throw new ModelFailureException(ModelFailureKind.INVALID_OUTPUT, "MOCK_SCHEMA_MISMATCH", 422,
                    "Mock structured output failed local schema validation");
        }
        try {
            T value = objectMapper.treeToValue(payload, contract.targetType());
            return new StructuredModelResult<>(value, ModelProvider.MOCK, model(request), "mock-request",
                    ModelUsage.empty(), 0L, false);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ModelFailureException(ModelFailureKind.INVALID_OUTPUT, "MOCK_INVALID_OUTPUT", 422,
                    "Mock structured output could not be deserialized");
        }
    }

    @Override
    public MultimodalModelResult completeMultimodal(ModelExecutionContext context, ModelRequest request) {
        requireInput(context, request);
        throw new ModelFailureException(ModelFailureKind.INVALID_REQUEST, "MOCK_MULTIMODAL_UNAVAILABLE", 501,
                "Mock multimodal execution is not available for Planning Agent");
    }

    private JsonNode scriptedOrSample(StructuredOutputContract<?> contract, ModelRequest request) {
        String scripted = request.metadata().get("mockStructuredJson");
        if (!StringUtils.hasText(scripted) && "true".equals(request.metadata().get("mockStructuredFromMessage"))) {
            String message = request.messages().get(request.messages().size() - 1).content();
            if (message.startsWith("FIXTURE_JSON:")) {
                scripted = message.substring("FIXTURE_JSON:".length());
            }
        }
        if (StringUtils.hasText(scripted)) {
            try {
                return objectMapper.readTree(scripted);
            } catch (JsonProcessingException exception) {
                throw new ModelFailureException(ModelFailureKind.INVALID_OUTPUT, "MOCK_INVALID_JSON", 422,
                        "Mock structured output is not valid JSON");
            }
        }
        return sample(contract.schemaDefinition());
    }

    private JsonNode sample(JsonNode schema) {
        return switch (schema.path("type").asText("object")) {
            case "object" -> sampleObject(schema);
            case "array" -> objectMapper.createArrayNode();
            case "string" -> objectMapper.getNodeFactory().textNode("mock");
            case "integer", "number" -> objectMapper.getNodeFactory().numberNode(0);
            case "boolean" -> objectMapper.getNodeFactory().booleanNode(false);
            default -> objectMapper.createObjectNode();
        };
    }

    private JsonNode sampleObject(JsonNode schema) {
        var object = objectMapper.createObjectNode();
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (schema.path("required").toString().contains("\"" + field.getKey() + "\"")) {
                    object.set(field.getKey(), sample(field.getValue()));
                }
            }
        }
        return object;
    }

    private static String model(ModelRequest request) {
        return StringUtils.hasText(request.modelHint()) ? request.modelHint() : "mock-v1";
    }

    private static void requireInput(ModelExecutionContext context, ModelRequest request) {
        if (context == null || request == null) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_INPUT_MISSING", 0,
                    "Model context and request are required");
        }
    }
}

