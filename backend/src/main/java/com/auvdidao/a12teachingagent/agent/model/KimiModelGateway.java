package com.auvdidao.a12teachingagent.agent.model;

import com.auvdidao.a12teachingagent.ai.kimi.KimiStructuredExecutor;
import com.auvdidao.a12teachingagent.ai.connection.ResolvedModelConnection;
import com.auvdidao.a12teachingagent.ai.connection.ModelConnectionService;
import com.auvdidao.a12teachingagent.ai.transport.OpenAiCompatibleTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Real-provider structured adapter. It exposes only provider-neutral results and never persists credentials. */
@Service("kimiModelGateway")
public class KimiModelGateway implements ModelGateway {
    private final KimiStructuredExecutor executor;
    private final ModelCredentialResolver credentialResolver;
    private final OpenAiCompatibleTransport transport;
    private final ObjectMapper objectMapper;
    private final ModelConnectionService modelConnectionService;

    public KimiModelGateway(KimiStructuredExecutor executor, ModelCredentialResolver credentialResolver) {
        this(executor, credentialResolver, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KimiModelGateway(KimiStructuredExecutor executor, ModelCredentialResolver credentialResolver,
                            OpenAiCompatibleTransport transport, ObjectMapper objectMapper,
                            ModelConnectionService modelConnectionService) {
        this.executor = executor;
        this.credentialResolver = credentialResolver;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.modelConnectionService = modelConnectionService;
    }

    @Override
    public ModelResult complete(ModelExecutionContext context, ModelRequest request) {
        throw new ModelFailureException(ModelFailureKind.INVALID_REQUEST, "KIMI_STRUCTURED_ONLY", 501,
                "The Planning Agent provider supports structured completion only");
    }

    @Override
    public <T> StructuredModelResult<T> completeStructured(ModelExecutionContext context, ModelRequest request,
                                                              StructuredOutputContract<T> contract) {
        return completeStructuredWithCredential(context, request, contract, credentialResolver.resolve(context, ModelProvider.KIMI));
    }

    /** Planning supplies the already-resolved credential so selection and transport share one request binding. */
    public <T> StructuredModelResult<T> completeStructuredWithCredential(ModelExecutionContext context, ModelRequest request,
                                                                           StructuredOutputContract<T> contract,
                                                                           ResolvedModelCredential credential) {
        if (credential == null || credential.provider() != ModelProvider.KIMI) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_CREDENTIAL_NOT_BOUND", 503,
                    "A resolved Kimi credential is required");
        }
        long started = System.nanoTime();
        JsonNode responseFormat = objectMapperSchema(contract);
        List<Map<String, String>> messages = request.messages().stream()
                .map(message -> Map.of("role", message.role().name().toLowerCase(), "content", message.content()))
                .toList();
        T value = executor.execute(messages, request.modelHint(), request.maxCompletionTokens(),
                Math.max(1, (int) Math.ceil(request.timeoutMs() / 1000.0)), responseFormat, credential, contract.targetType());
        long duration = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        return new StructuredModelResult<>(value, ModelProvider.KIMI, request.modelHint(), "kimi-request",
                ModelUsage.empty(), duration, false);
    }

    /** Provider-neutral Planning path. The connection is selected by actor + connection id and owns URL/model/key. */
    public <T> StructuredModelResult<T> completeStructuredWithConnection(ModelExecutionContext context, ModelRequest request,
                                                                           StructuredOutputContract<T> contract) {
        if (transport == null || objectMapper == null) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "OPENAI_COMPATIBLE_TRANSPORT_UNAVAILABLE", 503,
                    "OpenAI-compatible transport is unavailable");
        }
        ResolvedModelConnection connection = credentialResolver.resolveConnection(context);
        long started = System.nanoTime();
        OpenAiCompatibleTransport.TransportResponse response;
        try {
            response = transport.complete(connection, request);
            if (modelConnectionService != null) modelConnectionService.markUsed(connection.ownerUserId(), connection.id());
        } catch (com.auvdidao.a12teachingagent.ai.transport.TransportFailureException failure) {
            throw new ModelFailureException(failure.kind(), failure.safeCode(), failure.statusCode(), failure.getMessage());
        }
        try {
            JsonNode payload = objectMapper.readTree(response.content());
            var errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(contract.schemaDefinition()).validate(payload);
            if (!errors.isEmpty()) {
                throw new ModelFailureException(ModelFailureKind.INVALID_OUTPUT, "UPSTREAM_STRUCTURED_SCHEMA_MISMATCH", 502,
                        "Model structured output failed schema validation");
            }
            T value = objectMapper.treeToValue(payload, contract.targetType());
            return new StructuredModelResult<>(value, ModelProvider.OPENAI_COMPATIBLE, connection.modelId(),
                    response.requestId(), response.usage(), Math.max(0, (System.nanoTime() - started) / 1_000_000), false);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ModelFailureException(ModelFailureKind.INVALID_OUTPUT, "UPSTREAM_STRUCTURED_OUTPUT_INVALID", 502,
                    "Model structured output is invalid");
        }
    }

    @Override
    public MultimodalModelResult completeMultimodal(ModelExecutionContext context, ModelRequest request) {
        throw new ModelFailureException(ModelFailureKind.INVALID_REQUEST, "KIMI_MULTIMODAL_NOT_SUPPORTED", 501,
                "Kimi multimodal planning is not enabled");
    }

    private JsonNode objectMapperSchema(StructuredOutputContract<?> contract) {
        if (contract == null || contract.schemaDefinition() == null) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "KIMI_SCHEMA_MISSING", 500,
                    "A structured output schema is required");
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return mapper.createObjectNode().set("json_schema", mapper.createObjectNode()
                .put("name", contract.schemaName()).put("strict", contract.strict()).set("schema", contract.schemaDefinition()));
    }
}
