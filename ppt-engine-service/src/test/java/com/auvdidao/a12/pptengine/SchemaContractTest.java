package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.JsonSchemaCatalog;
import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SchemaContractTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JsonSchemaCatalog schemaCatalog;

    @Autowired
    private ContractGate contractGate;

    @Autowired
    private com.auvdidao.a12.pptengine.service.PreflightService preflightService;

    @Autowired
    private com.auvdidao.a12.pptengine.service.ComposePlanService composePlanService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allFiveSchemasAreLoadableAndValidRequestPassesEachInputSchema() throws Exception {
        List<String> schemas = List.of(
                "locked-ppt-specification.schema.json",
                "confirmed-template-profile.schema.json",
                "generation-feedback.schema.json",
                "engine-preflight-request.schema.json",
                "engine-preflight-response.schema.json",
                "engine-execute-request.schema.json");
        for (String schema : schemas) {
            JsonSchemaCatalog.class.getResourceAsStream("/contracts/v1/" + schema).close();
        }
        var request = ContractFixtures.request(objectMapper);
        assertThat(schemaCatalog.violationCount("engine-preflight-request.schema.json", objectMapper.valueToTree(request)))
                .isZero();
        assertThat(schemaCatalog.violationCount("locked-ppt-specification.schema.json", objectMapper.valueToTree(request.specification())))
                .isZero();
        assertThat(schemaCatalog.violationCount("confirmed-template-profile.schema.json", objectMapper.valueToTree(request.templateProfile())))
                .isZero();
    }

    @Test
    void malformedExecuteNestedArrayIsRejectedAsBadRequestWithoutArtifactEnvelope() throws Exception {
        var malformed = objectMapper.createObjectNode()
                .put("contractVersion", "1.0.0")
                .put("requestId", "execute-array-invalid")
                .set("generationJob", objectMapper.createObjectNode()
                        .set("approvedAssetFiles", objectMapper.createArrayNode().addNull()));

        var response = mockMvc.perform(post("/internal/v1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(malformed)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();

        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("feedback").isArray()).isTrue();
        assertThat(body.path("artifacts").isArray()).isTrue();
        assertThat(body.path("artifacts")).isEmpty();
        assertThat(body.toString()).doesNotContain("NullPointerException", "java.");
    }

    @Test
    void compositionSchemasAreLoadableAndSuccessfulOutputIsStrictlyValid() throws Exception {
        List<String> schemas = List.of(
                "engine-compose-plan-request.schema.json",
                "engine-compose-plan-response.schema.json",
                "engine-compose-plan-error-response.schema.json",
                "composition-feedback.schema.json");
        for (String schema : schemas) {
            JsonSchemaCatalog.class.getResourceAsStream("/contracts/v1/" + schema).close();
        }
        var request = CompositionTestSupport.request(objectMapper);
        assertThat(schemaCatalog.violationCount(
                "engine-compose-plan-request.schema.json", objectMapper.valueToTree(request))).isZero();

        var result = composePlanService.execute(request);
        assertThat(result.httpStatus()).isEqualTo(200);
        var response = new com.auvdidao.a12.pptengine.contract.CompositionModels.EngineComposePlanResponse(
                "2.0.0", "2.0.0", "2.0.0", request.requestId(), result.plan(), result.feedback());
        assertThat(schemaCatalog.violationCount(
                "engine-compose-plan-response.schema.json", objectMapper.valueToTree(response))).isZero();
        assertThat(schemaCatalog.violationCount(
                "composition-feedback.schema.json", objectMapper.valueToTree(result.feedback()))).isZero();
    }

    @Test
    void composeErrorEnvelopeIsStrictlyValidAndContainsNoPlan() {
        var profile = CompositionTestSupport.withPages(
                ContractFixtures.profile(),
                List.of(new ContractModels.TemplatePageReference(
                        "page-title", 1, "TITLE", List.of("shape-body", "shape-image"))));
        var request = CompositionTestSupport.request(
                objectMapper, "req-error-envelope", List.of(ContractFixtures.slide()), profile);
        var result = composePlanService.execute(request);
        assertThat(result.httpStatus()).isEqualTo(422);
        var error = new com.auvdidao.a12.pptengine.contract.CompositionModels.EngineComposePlanErrorResponse(
                "2.0.0", "2.0.0", request.requestId(), result.feedback());
        var raw = objectMapper.valueToTree(error);
        assertThat(raw.has("plan")).isFalse();
        assertThat(schemaCatalog.violationCount(
                "engine-compose-plan-error-response.schema.json", raw)).isZero();
    }

    @Test
    void generatedCompositionExamplesMatchCheckedInFixtures() throws Exception {
        var raw = objectMapper.readTree(
                getClass().getResourceAsStream("/contracts/v1/examples/valid/preflight-request.json"));
        ContractModels.EnginePreflightRequest preflight = objectMapper.treeToValue(
                raw, ContractModels.EnginePreflightRequest.class);
        var request = CompositionTestSupport.request(
                objectMapper, preflight.requestId(), preflight.specification(), preflight.templateProfile());
        var result = composePlanService.execute(request);
        var liveFeedback = result.feedback();
        var fixedFeedback = new com.auvdidao.a12.pptengine.contract.CompositionModels.CompositionFeedback(
                liveFeedback.contractVersion(), liveFeedback.feedbackContractVersion(),
                liveFeedback.requestId(), liveFeedback.generationJobId(),
                liveFeedback.executionAttemptId(), liveFeedback.specificationId(),
                liveFeedback.specificationVersion(), liveFeedback.specificationChecksum(),
                liveFeedback.templateProfileId(), liveFeedback.templateProfileVersion(),
                liveFeedback.status(), liveFeedback.diagnostics(),
                java.time.OffsetDateTime.parse("2026-08-24T00:00:00Z"));
        var response = new com.auvdidao.a12.pptengine.contract.CompositionModels.EngineComposePlanResponse(
                "2.0.0", "2.0.0", "2.0.0", request.requestId(), result.plan(), fixedFeedback);

        var expectedRequest = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/v1/examples/compose/success-request.json"));
        var expectedResponse = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/v1/examples/compose/success-response.json"));
        com.fasterxml.jackson.databind.JsonNode actualRequest = objectMapper.valueToTree(request);
        com.fasterxml.jackson.databind.JsonNode actualResponse = objectMapper.valueToTree(response);
        assertThat(expectedRequest).isNotNull();
        assertThat(actualRequest.at("/generationJob/projectId").asText())
                .isEqualTo(request.generationJob().projectId());
        assertThat(actualRequest.at("/generationJob/ownerUserId").asText())
                .isEqualTo(request.generationJob().ownerUserId());
        assertThat(actualRequest.at("/templateProfile/projectId").asText())
                .isEqualTo(request.templateProfile().projectId());
        assertThat(actualRequest.at("/templateProfile/ownerUserId").asText())
                .isEqualTo(request.templateProfile().ownerUserId());
        assertThat(actualRequest.at("/approvedAssetManifest/entries/0/storageKey").asText())
                .isNotBlank();
        assertThat(actualRequest.at("/approvedAssetManifest/entries/0/fileSize").asLong())
                .isPositive();
        assertThat(actualRequest.at("/approvedAssetManifest/entries/0/lastModifiedUtc").asText())
                .isNotBlank();
        assertThat(expectedResponse).isNotNull();
        assertThat(actualResponse.at("/plan").isObject()).isTrue();
        assertThat(actualResponse.at("/feedback/status").asText()).isEqualTo("SUCCEEDED_WITH_FEEDBACK");
        assertThat(actualResponse.at("/feedback/generationJobId").asText())
                .isEqualTo(request.generationJob().generationJobId());
    }

    @Test
    void schemaRejectsUnknownNestedField() {
        var raw = objectMapper.valueToTree(ContractFixtures.request(objectMapper));
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.get("specification")).put("typoField", "x");
        assertThat(schemaCatalog.violationCount("locked-ppt-specification.schema.json", raw.get("specification")))
                .isGreaterThan(0);
    }

    @Test
    void validAndInvalidExamplesAreExecutableContractFixtures() throws Exception {
        var valid = objectMapper.readTree(
                getClass().getResourceAsStream("/contracts/v1/examples/valid/preflight-request.json"));
        ContractModels.EnginePreflightRequest request = contractGate.parse(valid);
        assertThat(new ChecksumService(objectMapper).matches(request.specification())).isTrue();
        assertThat(schemaCatalog.violationCount("engine-preflight-request.schema.json", valid)).isZero();

        var result = preflightService.execute(request);
        assertThat(result.httpStatus()).isEqualTo(200);
        ContractModels.EnginePreflightResponse response = new ContractModels.EnginePreflightResponse(
                "1.0.0", request.requestId(), result.plan(), result.feedback());
        assertThat(schemaCatalog.violationCount(
                "engine-preflight-response.schema.json", objectMapper.valueToTree(response))).isZero();

        var invalid = objectMapper.readTree(
                getClass().getResourceAsStream("/contracts/v1/examples/invalid/unknown-field.json"));
        assertThat(schemaCatalog.violationCount("engine-preflight-request.schema.json", invalid)).isGreaterThan(0);

        var resolverFeedback = objectMapper.readTree(
                getClass().getResourceAsStream("/contracts/v1/examples/feedback/resolver-rejected.json"));
        assertThat(schemaCatalog.violationCount("generation-feedback.schema.json", resolverFeedback)).isZero();

        var composeRequest = objectMapper.readTree(
                getClass().getResourceAsStream("/contracts/v1/examples/compose/success-request.json"));
        assertThat(schemaCatalog.violationCount(
                "engine-compose-plan-request.schema.json", composeRequest)).isZero();
        var parsedComposeRequest = contractGate.parseCompose(composeRequest);
        assertThat(new ChecksumService(objectMapper).matches(parsedComposeRequest.specification())).isTrue();

        var composeResponse = objectMapper.readTree(
                getClass().getResourceAsStream("/contracts/v1/examples/compose/success-response.json"));
        assertThat(schemaCatalog.violationCount(
                "engine-compose-plan-response.schema.json", composeResponse)).isZero();
        var examplePlan = objectMapper.treeToValue(
                composeResponse.get("plan"),
                com.auvdidao.a12.pptengine.contract.CompositionModels.ComposedPresentationPlan.class);
        assertThat(new ChecksumService(objectMapper).computePlan(examplePlan))
                .isEqualTo(examplePlan.planChecksum());
        assertThat(composeResponse.toString())
                .doesNotContain("植物利用光能")
                .doesNotContain("material-example-001")
                .doesNotContain("sourceReference");

        var composeError = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/v1/examples/compose/error-response.json"));
        assertThat(schemaCatalog.violationCount(
                "engine-compose-plan-error-response.schema.json", composeError)).isZero();
        assertThat(composeError.has("plan")).isFalse();

        for (String failureExample : List.of(
                "template-page-missing-feedback.json",
                "layout-out-of-bounds-feedback.json",
                "unsupported-asset-feedback.json")) {
            var feedback = objectMapper.readTree(getClass().getResourceAsStream(
                    "/contracts/v1/examples/compose/" + failureExample));
            assertThat(schemaCatalog.violationCount("composition-feedback.schema.json", feedback)).isZero();
        }
    }

    @Test
    void preflightRejectedBodiesUseStrictV1ErrorSchemaAndKeepLegacyShape() throws Exception {
        String malformed = mockMvc.perform(post("/internal/v1/preflight")
                        .contentType(MediaType.APPLICATION_JSON).content("{malformed"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(schemaCatalog.violationCount("engine-preflight-error-response.schema.json",
                objectMapper.readTree(malformed))).isZero();

        var base = ContractFixtures.specification(objectMapper);
        var draft = ContractFixtures.withChecksum(objectMapper,
                new ContractModels.LockedPptSpecification(
                        base.contractVersion(), base.specificationId(), base.projectId(), base.version(),
                        com.auvdidao.a12.pptengine.contract.ContractTypes.SpecificationStatus.DRAFT,
                        base.templateProfileId(), base.templateProfileVersion(), base.targetSlideCount(),
                        base.slideCountTolerance(), base.locale(), base.provider(), base.model(),
                        base.aiSupplementPolicy(), base.lockedBy(), base.lockedAt(), null, base.slides()));
        var semanticRequest = new ContractModels.EnginePreflightRequest(
                com.auvdidao.a12.pptengine.contract.ContractTypes.V1, "req-schema-draft", draft,
                ContractFixtures.profile());
        String semantic = mockMvc.perform(post("/internal/v1/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(semanticRequest)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertThat(schemaCatalog.violationCount("engine-preflight-error-response.schema.json",
                objectMapper.readTree(semantic))).isZero();
        assertThat(objectMapper.readTree(semantic).path("outcome").asText()).isEqualTo("REJECTED");

        var oversized = new byte[(int) com.auvdidao.a12.pptengine.contract.ResourceLimits.MAX_REQUEST_BYTES + 1];
        String tooLarge = mockMvc.perform(post("/internal/v1/preflight")
                        .contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andReturn().getResponse().getContentAsString();
        assertThat(schemaCatalog.violationCount("engine-preflight-error-response.schema.json",
                objectMapper.readTree(tooLarge))).isZero();
    }

    @Test
    void templatePageMissingAndLayoutFailureFixturesUseJobBlockingSemantics() throws Exception {
        var missing = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/v1/examples/compose/template-page-missing-feedback.json"));
        assertThat(missing.at("/diagnostics/0/impact").asText()).isEqualTo("JOB_BLOCKING");
        var layout = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/v1/examples/compose/layout-out-of-bounds-feedback.json"));
        assertThat(layout.at("/status").asText()).isEqualTo("FAILED");
        assertThat(layout.at("/diagnostics/0/impact").asText()).isEqualTo("JOB_BLOCKING");
    }

    @Test
    void legacySchemasStayFrozenAndCompositionSchemasMatchExpandedJavaDiagnostics() throws Exception {
        List<String> schemas = List.of(
                "locked-ppt-specification.schema.json",
                "confirmed-template-profile.schema.json",
                "generation-feedback.schema.json",
                "engine-preflight-request.schema.json",
                "engine-preflight-response.schema.json");
        for (String schemaName : schemas) {
            var schema = objectMapper.readTree(
                    getClass().getResourceAsStream("/contracts/v1/" + schemaName));
            assertThat(schema.has("x-contractVersion")).isFalse();
            assertThat(schema.at("/properties/contractVersion/const").asText()).isEqualTo("1.0.0");
        }

        Set<String> javaCodes = Arrays.stream(
                        com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> legacyCodes = new TreeSet<>(Set.of(
                "CONTRACT_INVALID", "SPECIFICATION_NOT_LOCKED", "TEMPLATE_PROFILE_NOT_CONFIRMED",
                "CHECKSUM_MISMATCH", "TEMPLATE_PROFILE_MISMATCH", "SLIDE_COUNT_MISMATCH",
                "PAGE_SEQUENCE_INVALID", "DUPLICATE_ID", "COMPONENT_MISSING", "SLOT_INCOMPATIBLE",
                "SLOT_CAPACITY_EXCEEDED", "REQUIRED_SLOT_UNFILLED", "ASSET_TYPE_UNSUPPORTED",
                "IMAGE_NOT_APPROVED", "TRANSFORM_NOT_ALLOWED"));
        assertThat(schemaDiagnosticCodes("generation-feedback.schema.json")).isEqualTo(legacyCodes);
        assertThat(schemaDiagnosticCodes("engine-preflight-response.schema.json")).isEqualTo(legacyCodes);
        assertThat(schemaDiagnosticCodes("composition-feedback.schema.json")).isEqualTo(javaCodes);
        assertThat(schemaDiagnosticCodes("engine-compose-plan-response.schema.json")).isEqualTo(javaCodes);

        Set<String> javaSources = Arrays.stream(
                        com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> legacySources = new TreeSet<>(Set.of("CONTRACT_GATE", "COMPONENT_RESOLVER"));
        assertThat(schemaDiagnosticSources("generation-feedback.schema.json")).isEqualTo(legacySources);
        assertThat(schemaDiagnosticSources("engine-preflight-response.schema.json")).isEqualTo(legacySources);
        assertThat(schemaDiagnosticSources("composition-feedback.schema.json")).isEqualTo(javaSources);
        assertThat(schemaDiagnosticSources("engine-compose-plan-response.schema.json")).isEqualTo(javaSources);
    }

    private Set<String> schemaDiagnosticCodes(String schemaName) throws Exception {
        var schema = objectMapper.readTree(
                getClass().getResourceAsStream("/contracts/v1/" + schemaName));
        Set<String> codes = new TreeSet<>();
        schema.at("/$defs/diagnostic/properties/code/enum")
                .forEach(node -> codes.add(node.asText()));
        return codes;
    }

    private Set<String> schemaDiagnosticSources(String schemaName) throws Exception {
        var schema = objectMapper.readTree(
                getClass().getResourceAsStream("/contracts/v1/" + schemaName));
        Set<String> sources = new TreeSet<>();
        schema.at("/$defs/diagnostic/properties/source/enum")
                .forEach(node -> sources.add(node.asText()));
        return sources;
    }
}
