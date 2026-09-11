package com.auvdidao.a12.pptengine.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.MapSchemaLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JsonSchemaCatalog {

    private static final String BASE_PATH = "contracts/";
    public static final String CANONICAL_EXECUTION_READY_PROFILE_ID =
            "https://a12.local/contracts/v1/execution-ready-template-profile.schema.json";
    private static final String CANONICAL_EXECUTION_READY_PROFILE_RESOURCE =
            "contracts/v1/execution-ready-template-profile.schema.json";

    private final ObjectMapper objectMapper;
    private final Map<String, String> localSchemaBundle;
    private final JsonSchemaFactory factory;
    private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();

    public JsonSchemaCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.localSchemaBundle = loadLocalSchemaBundle();
        this.factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaLoaders(loaders -> loaders.values(entries -> {
                    entries.clear();
                    entries.add(new MapSchemaLoader(localSchemaBundle));
                })));
    }

    /** The canonical Profile is resolved from this fixed in-process bundle, never from the network. */
    public SchemaReference canonicalExecutionReadyProfile() {
        String schema = localSchemaBundle.get(CANONICAL_EXECUTION_READY_PROFILE_ID);
        return new SchemaReference(CANONICAL_EXECUTION_READY_PROFILE_ID, sha256(schema));
    }

    public record SchemaReference(String id, String sha256) {
    }

    public int violationCount(String schemaFileName, JsonNode instance) {
        return violations(schemaFileName, instance).size();
    }

    public List<SchemaViolation> violations(String schemaFileName, JsonNode instance) {
        JsonSchema schema = schemas.computeIfAbsent(schemaFileName, this::loadSchema);
        Set<ValidationMessage> violations = schema.validate(instance);
        return violations.stream()
                .map(item -> new SchemaViolation(
                        String.valueOf(item.getInstanceLocation()),
                        item.getType(),
                        item.getMessage()))
                .toList();
    }

    public record SchemaViolation(String path, String errorType, String message) {
    }

    private JsonSchema loadSchema(String schemaFileName) {
        String resourcePath = schemaFileName.contains("/")
                ? BASE_PATH + schemaFileName
                : BASE_PATH + "v1/" + schemaFileName;
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream inputStream = resource.getInputStream()) {
            return factory.getSchema(objectMapper.readTree(inputStream));
        } catch (IOException exception) {
            throw new IllegalStateException("Contract schema is unavailable", exception);
        }
    }

    private Map<String, String> loadLocalSchemaBundle() {
        try (InputStream inputStream = new ClassPathResource(
                CANONICAL_EXECUTION_READY_PROFILE_RESOURCE).getInputStream()) {
            String schema = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode parsed = objectMapper.readTree(schema);
            String id = parsed.path("$id").asText("");
            if (!CANONICAL_EXECUTION_READY_PROFILE_ID.equals(id)) {
                throw new IllegalStateException("Canonical execution-ready Profile $id is not bound to the expected local resource");
            }
            Map<String, String> bundle = new LinkedHashMap<>();
            bundle.put(id, schema);
            return Map.copyOf(bundle);
        } catch (IOException exception) {
            throw new IllegalStateException("Canonical schema bundle is unavailable", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
