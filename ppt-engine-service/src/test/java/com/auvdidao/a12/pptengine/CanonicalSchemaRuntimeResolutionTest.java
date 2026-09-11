package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.JsonSchemaCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalSchemaRuntimeResolutionTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonSchemaCatalog schemas = new JsonSchemaCatalog(objectMapper);

    @Test
    void composeAndExecuteWrappersResolveTheSameLocalCanonicalProfile() throws Exception {
        ObjectNode compose = objectMapper.valueToTree(CompositionTestSupport.request(objectMapper));
        ObjectNode execute = compose.deepCopy();
        execute.put("contractVersion", "2.0.0");
        execute.set("plan", objectMapper.createObjectNode());
        execute.set("templateSource", objectMapper.createObjectNode());
        execute.set("approvedAssetFiles", objectMapper.createArrayNode());

        var canonical = schemas.canonicalExecutionReadyProfile();
        var composeSchema = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/v1/engine-compose-plan-request.schema.json"));
        var executeSchema = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/v2/engine-execute-request.schema.json"));
        assertThat(composeSchema.at("/properties/templateProfile/$ref").asText())
                .isEqualTo(canonical.id());
        assertThat(executeSchema.at("/properties/templateProfile/$ref").asText())
                .isEqualTo(canonical.id());
        assertThat(canonical.sha256()).hasSize(64);

        // These calls exercise NetworkNT's resolver, not just the JSON text comparison above.
        assertThat(schemas.violations("engine-compose-plan-request.schema.json", compose).toString())
                .as("compose schema violations")
                .isEqualTo("[]");
        assertThat(schemas.violationCount("v2/engine-execute-request.schema.json", execute)).isGreaterThan(0);

        ((ObjectNode) compose.get("templateProfile")).put("unexpectedProfileField", "reject");
        ((ObjectNode) execute.get("templateProfile")).put("unexpectedProfileField", "reject");
        assertThat(schemas.violationCount("engine-compose-plan-request.schema.json", compose)).isGreaterThan(0);
        assertThat(schemas.violationCount("v2/engine-execute-request.schema.json", execute)).isGreaterThan(0);
    }
}
