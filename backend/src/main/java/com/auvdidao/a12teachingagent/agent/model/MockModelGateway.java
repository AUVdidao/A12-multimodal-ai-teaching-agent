package com.auvdidao.a12teachingagent.agent.model;

import com.auvdidao.a12teachingagent.agent.lifecycle.AgentTraceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic provider adapter for contract and future agent tests. */
@Service("mockModelGateway")
public class MockModelGateway implements ModelGateway {
    private final ObjectMapper objectMapper;
    private final AgentTraceService traceService;

    public MockModelGateway(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    @Autowired
    public MockModelGateway(ObjectMapper objectMapper, AgentTraceService traceService) {
        this.objectMapper = objectMapper;
        this.traceService = traceService;
    }

    @Override
    public ModelResult complete(ModelExecutionContext context, ModelRequest request) {
        requireInput(context, request);
        String content = request.metadata().getOrDefault("mockText", "mock model completion");
        ModelResult result = new ModelResult(
                ModelProvider.MOCK, request.modelHint() == null ? "mock-v1" : request.modelHint(),
                "mock-request", content, "stop", ModelUsage.empty(), 0L, List.of()
        );
        recordTrace(context, ModelTraceObservation.success(ModelProvider.MOCK, result.model(), result.requestId(), 0L));
        return result;
    }

    @Override
    public <T> StructuredModelResult<T> completeStructured(
            ModelExecutionContext context,
            ModelRequest request,
            StructuredOutputContract<T> contract
    ) {
        requireInput(context, request);
        if (contract == null) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "STRUCTURED_CONTRACT_MISSING", 0,
                    "Structured output contract is required");
        }
        JsonNode payload = scriptedOrSample(contract, request);
        Set<ValidationMessage> errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(contract.schemaDefinition())
                .validate(payload);
        if (!errors.isEmpty()) {
            throw new ModelFailureException(ModelFailureKind.INVALID_OUTPUT, "MOCK_SCHEMA_MISMATCH", 422,
                    "Mock structured output failed local schema validation");
        }
        try {
            T value = objectMapper.treeToValue(payload, contract.targetType());
            String model = request.modelHint() == null ? "mock-v1" : request.modelHint();
            recordTrace(context, ModelTraceObservation.success(ModelProvider.MOCK, model, "mock-request", 0L));
            return new StructuredModelResult<>(value, ModelProvider.MOCK, model, "mock-request",
                    ModelUsage.empty(), 0L, false);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ModelFailureException(ModelFailureKind.INVALID_OUTPUT, "MOCK_INVALID_OUTPUT", 422,
                    "Mock structured output could not be deserialized");
        }
    }

    @Override
    public MultimodalModelResult completeMultimodal(ModelExecutionContext context, ModelRequest request) {
        requireInput(context, request);
        if (request.messages().stream().allMatch(message -> message.images().isEmpty())) {
            throw new ModelFailureException(ModelFailureKind.INVALID_REQUEST, "MOCK_IMAGE_MISSING", 400,
                    "Multimodal request must contain an image reference");
        }
        String model = request.modelHint() == null ? "mock-v1" : request.modelHint();
        recordTrace(context, ModelTraceObservation.success(ModelProvider.MOCK, model, "mock-request", 0L));
        return new MultimodalModelResult(ModelProvider.MOCK, model, "mock-request",
                "mock multimodal completion", "stop", ModelUsage.empty(), 0L);
    }

    private JsonNode scriptedOrSample(StructuredOutputContract<?> contract, ModelRequest request) {
        String scripted = request.metadata().get("mockStructuredJson");
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
        String type = schema.path("type").asText("object");
        return switch (type) {
            case "object" -> sampleObject(schema);
            case "array" -> objectMapper.createArrayNode();
            case "string" -> objectMapper.getNodeFactory().textNode("mock");
            case "integer", "number" -> objectMapper.getNodeFactory().numberNode(0);
            case "boolean" -> objectMapper.getNodeFactory().booleanNode(false);
            default -> objectMapper.createObjectNode();
        };
    }

    private ObjectNode sampleObject(JsonNode schema) {
        ObjectNode object = objectMapper.createObjectNode();
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (schema.path("required").isArray() && contains(schema.path("required"), field.getKey())) {
                    object.set(field.getKey(), sample(field.getValue()));
                }
            }
        }
        return object;
    }

    private boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private void requireInput(ModelExecutionContext context, ModelRequest request) {
        if (context == null || request == null) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_INPUT_MISSING", 0,
                    "Model context and request are required");
        }
    }

    private void recordTrace(ModelExecutionContext context, ModelTraceObservation observation) {
        if (traceService != null) {
            traceService.recordModelCall(context, observation);
        }
    }
}
