package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.composition.CompositionPlanValidator;
import com.auvdidao.a12.pptengine.composition.SlideComposer;
import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.executor.ExecutorModels;
import com.auvdidao.a12.pptengine.executor.SamePackagePowerPointExecutor;
import com.auvdidao.a12.pptengine.layout.LayoutResolver;
import com.auvdidao.a12.pptengine.layout.TemplatePageResolver;
import com.auvdidao.a12.pptengine.resolver.ComponentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.w3c.dom.Element;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetResolution.APPROVED_ASSET;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetType.IMAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SamePackagePowerPointExecutorTest {

    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final Path MOCK_MVC_OUTPUT_ROOT = createTemporaryDirectory();

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ChecksumService checksumService;
    @Autowired
    private ContractGate contractGate;
    @Autowired
    private CompositionPlanValidator compositionPlanValidator;
    @Autowired
    private ComponentResolver componentResolver;
    @Autowired
    private TemplatePageResolver templatePageResolver;
    @Autowired
    private LayoutResolver layoutResolver;
    @Autowired
    private SlideComposer slideComposer;
    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path temp;

    private SamePackagePowerPointExecutor executor;

    @DynamicPropertySource
    static void registerMockMvcOutputRoot(DynamicPropertyRegistry registry) {
        registry.add("ppt.engine.executor.output-root", () -> MOCK_MVC_OUTPUT_ROOT.toString());
        registry.add("ppt.engine.executor.allow-legacy-absolute-paths", () -> "true");
    }

    @BeforeEach
    void setUp() {
        executor = new SamePackagePowerPointExecutor(
                checksumService, contractGate, compositionPlanValidator,
                temp.resolve("executor-output").toString());
    }

    @AfterEach
    void assertAndCleanMockMvcOutputRoot() throws Exception {
        deleteTree(MOCK_MVC_OUTPUT_ROOT);
        assertThat(Files.notExists(MOCK_MVC_OUTPUT_ROOT)).isTrue();
    }

    @Test
    void executesSamePackageTextAndApprovedPictureAndLeavesSourceUntouched() throws Exception {
        Path source = temp.resolve("clean-template.pptx");
        writeFixture(source);
        Path asset = temp.resolve("approved.png");
        Files.write(asset, ONE_PIXEL_PNG);
        String assetHash = sha256(asset);

        CompositionModels.EngineComposePlanRequest base = CompositionTestSupport.request(objectMapper);
        CompositionModels.ApprovedAssetManifestEntry manifestEntry =
                new CompositionModels.ApprovedAssetManifestEntry(
                        "asset-001", APPROVED_ASSET, "approved-asset-001", IMAGE, assetHash,
                        asset.toAbsolutePath().normalize().toString(), Files.size(asset),
                        java.time.OffsetDateTime.parse(Files.getLastModifiedTime(asset).toInstant().toString()));
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.withManifestEntries(
                objectMapper, base, "job-executor-smoke", 2, List.of(manifestEntry));
        CompositionModels.ValidatedExecutionPackage executionPackage =
                contractGate.validateCompose(request).executionPackage();
        assertThat(executionPackage).isNotNull();

        ComponentResolver.ResolverResult resolved = componentResolver.resolveForComposition(
                request.specification().slides().get(0), request.templateProfile(),
                executionPackage.manifestEntriesByRequirementId());
        TemplatePageResolver.SelectionResult page = templatePageResolver.resolve(
                request.specification().slides().get(0), request.templateProfile());
        LayoutResolver.LayoutResult layout = layoutResolver.resolveForComposition(
                request.specification().slides().get(0), request.templateProfile(), resolved, page.selection());
        CompositionModels.ComposedPresentationPlan plan = slideComposer.compose(
                executionPackage, List.of(layout.plan())).plan();
        assertThat(plan).isNotNull();

        long sourceSize = Files.size(source);
        FileTime sourceTime = Files.getLastModifiedTime(source);
        String sourceHash = sha256(source);
        ExecutorModels.ExecuteRequest executeRequest = new ExecutorModels.ExecuteRequest(
                ContractTypes.EXECUTOR_CONTRACT_V1,
                request.requestId(), request.generationJob(), request.specification(), request.templateProfile(),
                request.approvedAssetManifest(), plan,
                new ExecutorModels.TemplateSourceBinding(
                        source.toAbsolutePath().normalize().toString(), sourceHash, sourceSize,
                        sourceTime.toInstant().toString()),
                List.of(new ExecutorModels.ApprovedAssetFile(
                        "asset-001", "approved-asset-001", asset.toAbsolutePath().normalize().toString(), assetHash)));

        SamePackagePowerPointExecutor.ExecutionResult result = executor.execute(executeRequest);

        assertThat(result.status()).as(result.diagnostics().toString())
                .isEqualTo(ContractTypes.GenerationJobStatus.SUCCEEDED);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.artifact()).isNotNull();
        assertThat(Files.exists(Path.of(result.artifact().absolutePath()))).isTrue();
        assertThat(Files.size(source)).isEqualTo(sourceSize);
        assertThat(Files.getLastModifiedTime(source)).isEqualTo(sourceTime);
        assertThat(sha256(source)).isEqualTo(sourceHash);
        ExecutorModels.ApprovedAssetFile originalAsset = executeRequest.approvedAssetFiles().get(0);
        ExecutorModels.ExecuteRequest tamperedAssetRequest = new ExecutorModels.ExecuteRequest(
                executeRequest.contractVersion(), executeRequest.requestId(), executeRequest.generationJob(),
                executeRequest.specification(), executeRequest.templateProfile(), executeRequest.approvedAssetManifest(),
                executeRequest.plan(), executeRequest.templateSource(), List.of(new ExecutorModels.ApprovedAssetFile(
                        originalAsset.assetRequirementId(), originalAsset.approvedAssetId(), originalAsset.absolutePath(),
                        originalAsset.sha256(), originalAsset.size() + 1, originalAsset.lastModifiedUtc())));
        SamePackagePowerPointExecutor.ExecutionResult tamperedAsset = executor.execute(tamperedAssetRequest);
        assertThat(tamperedAsset.status()).isEqualTo(ContractTypes.GenerationJobStatus.FAILED);
        assertThat(tamperedAsset.diagnostics()).extracting(ExecutorModels.ExecutorDiagnostic::messageKey)
                .contains("executor.assetFileMetadataMismatch");
        try (ZipFile zip = new ZipFile(result.artifact().absolutePath())) {
            assertThat(zip.getEntry("ppt/media/a12-approved-approved-asset-001-" + assetHash.substring(0, 12) + ".png")).isNotNull();
            String slide = new String(zip.getInputStream(zip.getEntry("ppt/slides/slide1.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(slide).contains("植物利用光能将二氧化碳和水转化为有机物。");
        }
    }

    @Test
    void rejectsSourceHashDriftBeforeCreatingWorkingCopy() throws Exception {
        Path source = temp.resolve("clean-template.pptx");
        writeFixture(source);
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ValidatedExecutionPackage executionPackage =
                contractGate.validateCompose(request).executionPackage();
        ComponentResolver.ResolverResult resolved = componentResolver.resolveForComposition(
                request.specification().slides().get(0), request.templateProfile(),
                executionPackage.manifestEntriesByRequirementId());
        TemplatePageResolver.SelectionResult page = templatePageResolver.resolve(
                request.specification().slides().get(0), request.templateProfile());
        LayoutResolver.LayoutResult layout = layoutResolver.resolveForComposition(
                request.specification().slides().get(0), request.templateProfile(), resolved, page.selection());
        CompositionModels.ComposedPresentationPlan plan = slideComposer.compose(
                executionPackage, List.of(layout.plan())).plan();
        ExecutorModels.ExecuteRequest executeRequest = new ExecutorModels.ExecuteRequest(
                ContractTypes.EXECUTOR_CONTRACT_V1, request.requestId(), request.generationJob(),
                request.specification(), request.templateProfile(), request.approvedAssetManifest(), plan,
                new ExecutorModels.TemplateSourceBinding(
                        source.toAbsolutePath().normalize().toString(), "0".repeat(64), Files.size(source),
                        Files.getLastModifiedTime(source).toInstant().toString()), List.of());

        SamePackagePowerPointExecutor.ExecutionResult result = executor.execute(executeRequest);

        assertThat(result.status()).isEqualTo(ContractTypes.GenerationJobStatus.FAILED);
        assertThat(result.diagnostics()).extracting(ExecutorModels.ExecutorDiagnostic::code)
                .contains(ContractTypes.DiagnosticCode.TEMPLATE_SOURCE_HASH_MISMATCH.name());
        assertThat(Files.exists(temp.resolve("executor-output"))).isFalse();
    }

    @Test
    void repeatedAttemptIsIdempotentAndConflictingBindingCannotReplaceArtifact() throws Exception {
        Path source = temp.resolve("idempotent-template.pptx");
        Path asset = temp.resolve("idempotent.png");
        ExecutorModels.ExecuteRequest request = buildExecuteRequest(source, asset, "job-idempotent", "attempt-idempotent");

        SamePackagePowerPointExecutor.ExecutionResult first = executor.execute(request);
        SamePackagePowerPointExecutor.ExecutionResult second = executor.execute(request);
        Path alternateAsset = temp.resolve("idempotent-copy.png");
        Files.copy(asset, alternateAsset);
        ExecutorModels.ExecuteRequest conflictingRequest = new ExecutorModels.ExecuteRequest(
                request.contractVersion(), request.requestId(), request.generationJob(), request.specification(),
                request.templateProfile(), request.approvedAssetManifest(), request.plan(), request.templateSource(),
                List.of(new ExecutorModels.ApprovedAssetFile(
                        "asset-001", "approved-asset-001", alternateAsset.toAbsolutePath().normalize().toString(),
                        request.approvedAssetFiles().get(0).sha256(),
                        Files.size(alternateAsset),
                        java.time.OffsetDateTime.parse(Files.getLastModifiedTime(alternateAsset).toInstant().toString()).toString())));
        SamePackagePowerPointExecutor.ExecutionResult conflict = executor.execute(conflictingRequest);

        assertThat(first.status()).isEqualTo(ContractTypes.GenerationJobStatus.SUCCEEDED);
        assertThat(second.status()).isEqualTo(ContractTypes.GenerationJobStatus.SUCCEEDED);
        assertThat(second.preview().reason()).isEqualTo("IDEMPOTENT_EXISTING_RESULT");
        assertThat(second.artifact()).isEqualTo(first.artifact());
        assertThat(conflict.status()).isEqualTo(ContractTypes.GenerationJobStatus.FAILED);
        assertThat(conflict.diagnostics()).extracting(ExecutorModels.ExecutorDiagnostic::messageKey)
                .contains("executor.assetFileBindingMismatch");
        assertThat(Files.size(Path.of(first.artifact().absolutePath()))).isEqualTo(first.artifact().size());

        ExecutorModels.ExecuteRequest secondAttempt = buildExecuteRequest(
                temp.resolve("idempotent-template-2.pptx"), temp.resolve("idempotent-2.png"),
                "job-idempotent", "attempt-idempotent-2");
        SamePackagePowerPointExecutor.ExecutionResult isolated = executor.execute(secondAttempt);
        assertThat(isolated.status()).isEqualTo(ContractTypes.GenerationJobStatus.SUCCEEDED);
        assertThat(isolated.artifact().absolutePath()).isNotEqualTo(first.artifact().absolutePath());
    }

    @Test
    void detectsSourceMutationAtCommitBoundaryAndCleansOnlyNewAttempt() throws Exception {
        Path source = temp.resolve("mutating-template.pptx");
        Path asset = temp.resolve("mutating.png");
        ExecutorModels.ExecuteRequest request = buildExecuteRequest(source, asset, "job-mutating", "attempt-mutating");
        SamePackagePowerPointExecutor mutatingExecutor = new SamePackagePowerPointExecutor(
                checksumService, contractGate, compositionPlanValidator,
                temp.resolve("mutation-output").toString(),
                () -> {
                    try {
                        Files.writeString(source, "replacement");
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });

        SamePackagePowerPointExecutor.ExecutionResult result = mutatingExecutor.execute(request);

        assertThat(result.status()).isEqualTo(ContractTypes.GenerationJobStatus.FAILED);
        assertThat(result.diagnostics()).extracting(ExecutorModels.ExecutorDiagnostic::messageKey)
                .contains("executor.sourceChangedDuringExecution");
        assertThat(Files.exists(temp.resolve("mutation-output").resolve("attempt-mutating"))).isFalse();
    }

    @Test
    void invalidWindowsPathIsStructuredFailureInsteadOfPathException() throws Exception {
        Path source = temp.resolve("path-template.pptx");
        Path asset = temp.resolve("path.png");
        ExecutorModels.ExecuteRequest request = buildExecuteRequest(source, asset, "job-path", "attempt-path");
        ExecutorModels.ExecuteRequest invalidPath = new ExecutorModels.ExecuteRequest(
                request.contractVersion(), request.requestId(), request.generationJob(), request.specification(),
                request.templateProfile(), request.approvedAssetManifest(), request.plan(),
                new ExecutorModels.TemplateSourceBinding("bad\u0000path.pptx", request.templateSource().sha256(),
                        request.templateSource().size(), request.templateSource().lastModifiedUtc()),
                request.approvedAssetFiles());

        SamePackagePowerPointExecutor.ExecutionResult result = executor.execute(invalidPath);

        assertThat(result.status()).isEqualTo(ContractTypes.GenerationJobStatus.FAILED);
        assertThat(result.diagnostics()).extracting(ExecutorModels.ExecutorDiagnostic::code)
                .contains(ContractTypes.DiagnosticCode.TEMPLATE_SOURCE_INVALID.name());
    }

    @Test
    void transformViolationIsRejectedByExecutorAndProducesPartialArtifact() throws Exception {
        Path source = temp.resolve("transform-template.pptx");
        Path asset = temp.resolve("transform.png");
        ExecutorModels.ExecuteRequest request = buildExecuteRequest(source, asset, "job-transform", "attempt-transform");
        replaceSlideTextWidth(source, "4000000");
        ExecutorModels.ExecuteRequest refreshed = withSourceBinding(request, source);

        SamePackagePowerPointExecutor.ExecutionResult result = executor.execute(refreshed);

        assertThat(result.status()).isEqualTo(ContractTypes.GenerationJobStatus.PARTIAL);
        assertThat(result.diagnostics()).extracting(ExecutorModels.ExecutorDiagnostic::code)
                .contains(ContractTypes.DiagnosticCode.TRANSFORM_NOT_ALLOWED.name());
        assertThat(result.artifact()).isNotNull();
    }

    @Test
    void duplicateContentTypesBlocksArtifactCommit() throws Exception {
        Path source = temp.resolve("duplicate-types-template.pptx");
        Path asset = temp.resolve("duplicate-types.png");
        ExecutorModels.ExecuteRequest request = buildExecuteRequest(source, asset, "job-types", "attempt-types");
        duplicatePngDefault(source);

        SamePackagePowerPointExecutor.ExecutionResult result = executor.execute(withSourceBinding(request, source));

        assertThat(result.status()).isEqualTo(ContractTypes.GenerationJobStatus.FAILED);
        assertThat(result.diagnostics()).extracting(ExecutorModels.ExecutorDiagnostic::code)
                .contains(ContractTypes.DiagnosticCode.PPTX_CONTENT_TYPES_INVALID.name());
        assertThat(Files.exists(temp.resolve("executor-output").resolve("attempt-types")
                .resolve("generation-result.pptx"))).isFalse();
    }

    @Test
    void completeExecutorTransformMatrixReachesWorkingCopyAndFinalPackage() throws Exception {
        for (ContractTypes.TransformConstraint strategy : ContractTypes.TransformConstraint.values()) {
            for (MatrixTarget target : MatrixTarget.values()) {
                for (MatrixOutcome outcome : MatrixOutcome.values()) {
                    for (boolean required : List.of(true, false)) {
                        String suffix = strategy.name().toLowerCase() + "-" + target.name().toLowerCase()
                                + "-" + outcome.name().toLowerCase() + "-" + (required ? "required" : "optional");
                        Path source = temp.resolve("matrix-" + suffix + ".pptx");
                        Path asset = temp.resolve("matrix-" + suffix + ".png");
                        ExecutorModels.ExecuteRequest request = buildMatrixRequest(
                                source, asset, "job-matrix-" + suffix, "attempt-matrix-" + suffix,
                                strategy, target, outcome, required);

                        SamePackagePowerPointExecutor.ExecutionResult result = executor.execute(request);
                        boolean responsive = strategy == ContractTypes.TransformConstraint.RESPONSIVE;
                        assertMatrixObjectIdentity(request, target, suffix);
                        if (outcome == MatrixOutcome.LEGAL
                                || (responsive && outcome == MatrixOutcome.OUT_OF_RANGE)) {
                            assertThat(result.status()).as(suffix + " " + result.diagnostics())
                                    .isEqualTo(ContractTypes.GenerationJobStatus.SUCCEEDED);
                            assertThat(result.diagnostics()).as(suffix).isEmpty();
                            assertArtifactIdentity(request, result, suffix);
                            assertAttemptCommitted(request, result, suffix);
                            assertThat(readGeometry(Path.of(result.artifact().absolutePath()), target))
                                    .as(suffix).isEqualTo(expectedGeometry(strategy, targetBounds(strategy, target)));
                            assertThat(readXmlObjectIdentity(Path.of(result.artifact().absolutePath()), target))
                                    .as(suffix).isEqualTo(expectedXmlObject(target));
                        } else if (outcome == MatrixOutcome.MISSING || outcome == MatrixOutcome.INVALID) {
                            assertThat(result.status()).as(suffix + " " + result.diagnostics())
                                    .isEqualTo(ContractTypes.GenerationJobStatus.FAILED);
                            assertThat(result.artifact()).as(suffix).isNull();
                            assertBlockingDiagnostics(result, suffix);
                            assertAttemptCleaned(request, suffix);
                        } else {
                            ContractTypes.GenerationJobStatus expectedStatus = required
                                    ? ContractTypes.GenerationJobStatus.PARTIAL
                                    : ContractTypes.GenerationJobStatus.SUCCEEDED_WITH_FEEDBACK;
                            assertThat(result.status()).as(suffix + " " + result.diagnostics())
                                    .isEqualTo(expectedStatus);
                            assertThat(result.diagnostics()).as(suffix)
                                    .extracting(ExecutorModels.ExecutorDiagnostic::code)
                                    .contains(ContractTypes.DiagnosticCode.TRANSFORM_NOT_ALLOWED.name());
                            assertThat(result.diagnostics()).as(suffix)
                                    .allSatisfy(diagnostic -> {
                                        if (required) {
                                            assertThat(diagnostic.impact()).as(suffix)
                                                    .isEqualTo(ContractTypes.DiagnosticImpact.ARTIFACT_INCOMPLETE);
                                        } else {
                                            assertThat(diagnostic.impact()).as(suffix)
                                                    .isEqualTo(ContractTypes.DiagnosticImpact.NON_BLOCKING);
                                        }
                                    });
                            assertArtifactIdentity(request, result, suffix);
                            assertAttemptCommitted(request, result, suffix);
                            assertThat(readGeometry(Path.of(result.artifact().absolutePath()), target))
                                    .as(suffix).isEqualTo(currentBounds(target));
                            assertThat(readXmlObjectIdentity(Path.of(result.artifact().absolutePath()), target))
                                    .as(suffix).isEqualTo(expectedXmlObject(target));
                        }
                    }
                }
            }
        }
    }

    @Test
    void completeExecuteApiReturnsArtifactHashSizeAndRejectsArrayMutationsAsStructured4xx() throws Exception {
        Path source = temp.resolve("api-valid-template.pptx");
        Path asset = temp.resolve("api-valid.png");
        String runId = Long.toUnsignedString(System.nanoTime());
        ExecutorModels.ExecuteRequest valid = buildExecuteRequest(
                source, asset, "job-api-valid-" + runId, "attempt-api-valid-" + runId);
        var validResponse = mockMvc.perform(post("/internal/v1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(valid)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        var validBody = objectMapper.readTree(validResponse.getContentAsString());
        assertThat(validBody.path("status").asText()).isEqualTo("SUCCEEDED");
        String artifactKey = validBody.path("artifacts").get(0).path("storageKey").asText();
        assertThat(artifactKey).isEqualTo(valid.generationJob().executionAttemptId() + "/generation-result.pptx");
        Path artifact = MOCK_MVC_OUTPUT_ROOT.resolve(artifactKey).toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(artifact)).isTrue();
        assertApiArtifactCommitted(valid, validBody);
        assertThat(validBody.path("artifacts").get(0).path("sha256").asText()).isEqualTo(sha256(artifact));
        assertThat(validBody.path("artifacts").get(0).path("fileSize").asLong()).isEqualTo(Files.size(artifact));
        assertThat(validBody.path("feedback")).isEmpty();
        deleteTree(MOCK_MVC_OUTPUT_ROOT);

        List<Mutation> mutations = List.of(
                tree -> ((ObjectNode) tree.path("approvedAssetFiles").get(0)).remove("assetRequirementId"),
                tree -> ((ObjectNode) tree).set("approvedAssetFiles", objectMapper.createObjectNode()),
                tree -> ((ArrayNode) tree.path("approvedAssetFiles")).set(0, objectMapper.nullNode()),
                tree -> ((ArrayNode) tree.path("approvedAssetFiles")).set(0, objectMapper.createObjectNode()),
                tree -> ((ObjectNode) tree.path("approvedAssetFiles").get(0)).put("unknown", "secret"),
                tree -> ((ObjectNode) tree.path("approvedAssetFiles").get(0)).put("sha256", "not-a-hash"),
                tree -> ((ObjectNode) tree.path("approvedAssetFiles").get(0)).put("assetRequirementId", "wrong-id"),
                tree -> ((ObjectNode) tree.path("templateSource")).put("sha256", "0".repeat(64)),
                tree -> ((ObjectNode) tree).set("approvedAssetFiles", objectMapper.createArrayNode()),
                tree -> ((ObjectNode) tree.path("plan")).set("slides", objectMapper.createArrayNode()),
                tree -> ((ObjectNode) tree.path("plan").path("slides").get(0))
                        .set("operations", objectMapper.createArrayNode()),
                tree -> ((ObjectNode) tree.path("plan").path("slides").get(0).path("operations").get(0))
                        .put("operationType", "NOT_A_REAL_OPERATION"),
                tree -> ((ObjectNode) tree.path("templateProfile").path("components").get(0))
                        .put("transformConstraint", "NOT_A_REAL_TRANSFORM"));
        for (int index = 0; index < mutations.size(); index++) {
            JsonNode raw = objectMapper.valueToTree(valid);
            mutations.get(index).apply((ObjectNode) raw);
            var request = post("/internal/v1/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(raw));
            var response = mockMvc.perform(request)
                    .andReturn().getResponse();
            var body = objectMapper.readTree(response.getContentAsString());
            if (index == 8) {
                assertThat(response.getStatus()).as("mutation " + index + " empty approvedAssetFiles").isEqualTo(400);
                assertStructuredExecuteError(body, "mutation " + index + " empty approvedAssetFiles");
                assertIllegalExecuteResponse(body, valid, "mutation " + index + " empty approvedAssetFiles");
            } else {
                assertThat(response.getStatus()).as("mutation " + index).isBetween(400, 499);
                assertIllegalExecuteResponse(body, valid, "mutation " + index);
            }
        }
    }

    @Test
    void completeExecuteV2ApiUsesCanonicalProfileAndEchoesV2ContractVersion() throws Exception {
        Path source = temp.resolve("api-v2-template.pptx");
        Path asset = temp.resolve("api-v2.png");
        String runId = Long.toUnsignedString(System.nanoTime());
        ExecutorModels.ExecuteRequest valid = buildExecuteRequest(
                source, asset, "job-api-v2-" + runId, "attempt-api-v2-" + runId);
        ObjectNode raw = (ObjectNode) objectMapper.valueToTree(valid);
        raw.put("contractVersion", ContractTypes.EXECUTOR_CONTRACT_V2);

        var response = mockMvc.perform(post("/internal/v2/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(raw)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("contractVersion").asText())
                .isEqualTo(ContractTypes.EXECUTOR_CONTRACT_V2);
        assertThat(body.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(body.path("artifacts")).isNotEmpty();
        assertThat(body.path("artifacts").get(0).path("sha256").asText()).hasSize(64);
        assertThat(body.path("artifacts").get(0).path("fileSize").asLong()).isPositive();
    }

    @Test
    void executeHttpGateRejectsForgedOwnerAndApprovedAssetMtimeWithoutCreatingOutput() throws Exception {
        Path source = temp.resolve("binding-template.pptx");
        Path asset = temp.resolve("binding.png");
        ExecutorModels.ExecuteRequest valid = buildExecuteRequest(
                source, asset, "job-binding-http", "attempt-binding-http");

        ObjectNode forgedOwner = (ObjectNode) objectMapper.valueToTree(valid);
        ((ObjectNode) forgedOwner.get("generationJob")).put("ownerUserId", "forged-owner");
        var ownerResponse = mockMvc.perform(post("/internal/v1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(forgedOwner)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();
        JsonNode ownerBody = objectMapper.readTree(ownerResponse.getContentAsString());
        assertThat(ownerBody.path("status").asText()).isEqualTo("FAILED");
        assertThat(ownerBody.path("artifacts")).isEmpty();
        assertThat(ownerBody.at("/feedback/0/code").asText())
                .isEqualTo(ContractTypes.DiagnosticCode.GENERATION_JOB_OWNER_BINDING_MISMATCH.name());

        ObjectNode forgedMtime = (ObjectNode) objectMapper.valueToTree(valid);
        ((ObjectNode) forgedMtime.withArray("approvedAssetFiles").get(0))
                .put("lastModifiedUtc", "2026-08-24T00:00:00Z");
        var assetResponse = mockMvc.perform(post("/internal/v1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(forgedMtime)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();
        JsonNode assetBody = objectMapper.readTree(assetResponse.getContentAsString());
        assertThat(assetBody.path("status").asText()).isEqualTo("FAILED");
        assertThat(assetBody.path("artifacts")).isEmpty();
        assertThat(assetBody.at("/feedback/0/code").asText())
                .isEqualTo(ContractTypes.DiagnosticCode.ASSET_MANIFEST_FILE_IDENTITY_INVALID.name());
        assertThat(Files.notExists(MOCK_MVC_OUTPUT_ROOT.resolve("attempt-binding-http"))).isTrue();
    }

    @Test
    void completeExecuteApiCoversEveryDeclaredNestedArrayWithStableMutationResponses() throws Exception {
        int executedCases = 0;
        for (ArraySpec spec : declaredExecuteArrays()) {
            for (ArrayMutation mutation : spec.mutations()) {
                String suffix = spec.label() + "-" + mutation.name().toLowerCase() + "-" + executedCases;
                Path source = temp.resolve("array-" + suffix + ".pptx");
                Path asset = temp.resolve("array-" + suffix + ".png");
                ExecutorModels.ExecuteRequest valid = buildExecuteRequest(
                        source, asset, "job-array-" + suffix, "attempt-array-" + suffix);
                JsonNode raw = objectMapper.valueToTree(valid);
                applyArrayMutation((ObjectNode) raw, spec, mutation);

                var response = mockMvc.perform(post("/internal/v1/execute")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(raw)))
                        .andReturn().getResponse();
                var body = objectMapper.readTree(response.getContentAsString());
                if (mutation == ArrayMutation.EMPTY_ARRAY && !spec.minItemsOne()) {
                    switch (spec.emptyArrayContract()) {
                        case LEGAL_PARTIAL_ARTIFACT -> {
                            assertThat(response.getStatus())
                                    .as(spec.label() + " empty array status: " + body)
                                    .isEqualTo(400);
                            assertStructuredExecuteError(body, spec.label() + " empty array");
                            assertThat(body.has("artifact")).isFalse();
                            assertIllegalExecuteResponse(body, valid, spec.label() + " empty array");
                        }
                        case ILLEGAL_400, ILLEGAL_422 -> {
                            assertThat(response.getStatus())
                                    .as(spec.label() + " empty array status: " + body)
                                    .isEqualTo(spec.emptyArrayStatus());
                            assertStructuredExecuteError(body, spec.label() + " empty array");
                            assertThat(body.has("artifact"))
                                    .as(spec.label() + " empty array artifact").isFalse();
                            assertIllegalExecuteResponse(body, valid, spec.label() + " empty array");
                        }
                    }
                } else {
                    assertThat(response.getStatus())
                            .as(spec.label() + " " + mutation + ": " + body)
                            .isBetween(400, 499);
                    assertStructuredExecuteError(body, spec.label() + " " + mutation);
                    assertThat(body.has("artifact"))
                            .as(spec.label() + " " + mutation).isFalse();
                    assertIllegalExecuteResponse(body, valid, spec.label() + " " + mutation);
                }
                executedCases++;
            }
        }
        assertThat(executedCases).as("declared nested array mutation cases").isEqualTo(131);
    }

    private ExecutorModels.ExecuteRequest buildMatrixRequest(
            Path source, Path asset, String generationJobId, String attemptId,
            ContractTypes.TransformConstraint strategy, MatrixTarget target,
            MatrixOutcome outcome, boolean required) throws Exception {
        writeFixture(source);
        Files.write(asset, ONE_PIXEL_PNG);
        String assetHash = sha256(asset);
        ContractModels.LockedPptSlide slide = matrixSlide(strategy, target, required);
        ContractModels.ConfirmedTemplateProfile profile = matrixProfile(strategy, target, outcome, required);
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(
                objectMapper, "request-" + attemptId, List.of(slide), profile);
        request = CompositionTestSupport.withManifestEntries(objectMapper, request, generationJobId, 2,
                List.of(new CompositionModels.ApprovedAssetManifestEntry(
                        "asset-001", APPROVED_ASSET, "approved-asset-001", IMAGE, assetHash,
                        asset.toAbsolutePath().normalize().toString(), Files.size(asset),
                        java.time.OffsetDateTime.parse(Files.getLastModifiedTime(asset).toInstant().toString()))));
        request = CompositionTestSupport.withGenerationJob(objectMapper, request, generationJobId, attemptId,
                "engine-build-test", "executor-adapter-none", "font-env-test");
        CompositionModels.ValidatedExecutionPackage executionPackage = contractGate.validateCompose(request).executionPackage();
        assertThat(executionPackage).isNotNull();
        ComponentResolver.ResolverResult resolved = componentResolver.resolveForComposition(
                slide, profile, executionPackage.manifestEntriesByRequirementId());
        TemplatePageResolver.SelectionResult page = templatePageResolver.resolve(slide, profile);
        LayoutResolver.LayoutResult layout = layoutResolver.resolveForComposition(
                slide, profile, resolved, page.selection());
        CompositionModels.ComposedPresentationPlan plan = slideComposer.compose(
                executionPackage, List.of(layout.plan())).plan();
        assertThat(plan).isNotNull();
        plan = mutateMatrixOperation(plan, strategy, target, outcome);
        return new ExecutorModels.ExecuteRequest(
                ContractTypes.EXECUTOR_CONTRACT_V1, request.requestId(), request.generationJob(),
                request.specification(), request.templateProfile(), request.approvedAssetManifest(), plan,
                new ExecutorModels.TemplateSourceBinding(
                        source.toAbsolutePath().normalize().toString(), sha256(source), Files.size(source),
                        Files.getLastModifiedTime(source).toInstant().toString()),
                List.of(new ExecutorModels.ApprovedAssetFile(
                        "asset-001", "approved-asset-001", asset.toAbsolutePath().normalize().toString(), assetHash)));
    }

    private ContractModels.LockedPptSlide matrixSlide(
            ContractTypes.TransformConstraint strategy, MatrixTarget target, boolean required) {
        ContractModels.LockedPptSlide base = ContractFixtures.slide();
        return new ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(), base.contentBlocks(),
                new ContractModels.SemanticLayout(base.semanticLayout().primaryRole(),
                        base.semanticLayout().regions(), strategy), base.assetRequirements(), base.provenance(), base.notes());
    }

    private ContractModels.ConfirmedTemplateProfile matrixProfile(
            ContractTypes.TransformConstraint strategy, MatrixTarget target, MatrixOutcome outcome, boolean required) {
        ContractModels.ConfirmedTemplateProfile base = ContractFixtures.profile();
        ContractModels.Bounds requested = outcome == MatrixOutcome.OUT_OF_RANGE
                ? outOfRangeBounds(strategy, target) : targetBounds(strategy, target);
        if (outcome == MatrixOutcome.MISSING || outcome == MatrixOutcome.INVALID) {
            requested = legalBaseBounds(target);
        }
        List<ContractModels.TemplateComponent> components = new ArrayList<>();
        for (ContractModels.TemplateComponent component : base.components()) {
            List<ContractModels.ComponentSlot> slots = new ArrayList<>();
            for (ContractModels.ComponentSlot slot : component.slots()) {
                ContractModels.Bounds bounds = slot.bounds();
                boolean matrixSlot = "component-body".equals(component.componentId())
                        || "component-image".equals(component.componentId());
                boolean selectedSlot = (target == MatrixTarget.PICTURE && "component-image".equals(component.componentId()))
                        || (target != MatrixTarget.PICTURE && "component-body".equals(component.componentId()));
                if (selectedSlot) {
                    bounds = requested;
                }
                slots.add(new ContractModels.ComponentSlot(slot.slotId(), slot.semanticRole(),
                        slot.acceptedContentTypes(), bounds,
                        matrixSlot ? required : slot.required(),
                        slot.capacityConstraint()));
            }
            List<ContractModels.StableObjectReference> refs = component.shapeRefs();
            if (target == MatrixTarget.SHAPE && "component-body".equals(component.componentId())) {
                refs = List.of(new ContractModels.StableObjectReference(
                        ContractTypes.ObjectType.SHAPE, "shape-body"));
            }
            components.add(new ContractModels.TemplateComponent(
                    component.componentId(), component.name(), component.semanticRole(), component.sourceSlide(),
                    refs, component.childComponentIds(), slots, strategy, component.fixedStyle(),
                    component.reusable(), component.confidence(), component.teacherConfirmed()));
        }
        return CompositionTestSupport.withComponents(base, components);
    }

    private CompositionModels.ComposedPresentationPlan mutateMatrixOperation(
            CompositionModels.ComposedPresentationPlan plan, ContractTypes.TransformConstraint strategy,
            MatrixTarget target, MatrixOutcome outcome) {
        List<CompositionModels.CompositionOperation> operations = new ArrayList<>(plan.slides().get(0).operations());
        int targetIndex = operationIndex(operations, target);
        CompositionModels.CompositionOperation operation = operations.get(targetIndex);
        ContractModels.Bounds bounds = outcome == MatrixOutcome.OUT_OF_RANGE
                ? outOfRangeBounds(strategy, target) : targetBounds(strategy, target);
        ContractTypes.TransformConstraint requested = strategy;
        ContractTypes.TransformConstraint allowed = strategy;
        if (outcome == MatrixOutcome.MISSING) {
            bounds = null;
        } else if (outcome == MatrixOutcome.INVALID) {
            requested = strategy == ContractTypes.TransformConstraint.FIXED
                    ? ContractTypes.TransformConstraint.TRANSLATE_ONLY : ContractTypes.TransformConstraint.FIXED;
        }
        operations.set(targetIndex, copyOperation(operation, bounds, requested, allowed));
        CompositionModels.ComposedSlidePlan slide = new CompositionModels.ComposedSlidePlan(
                plan.slides().get(0).slideId(), plan.slides().get(0).pageNumber(),
                plan.slides().get(0).layout(), operations);
        CompositionModels.ComposedPresentationPlan withoutChecksum = new CompositionModels.ComposedPresentationPlan(
                plan.planContractVersion(), plan.requestReference(), plan.generationJobReference(),
                plan.specificationReference(), plan.templateProfileReference(), plan.approvedAssetManifestReference(),
                plan.textFitBoundary(), plan.originalSlideCount(), List.of(slide), null);
        return new CompositionModels.ComposedPresentationPlan(
                withoutChecksum.planContractVersion(), withoutChecksum.requestReference(),
                withoutChecksum.generationJobReference(), withoutChecksum.specificationReference(),
                withoutChecksum.templateProfileReference(), withoutChecksum.approvedAssetManifestReference(),
                withoutChecksum.textFitBoundary(), withoutChecksum.originalSlideCount(), withoutChecksum.slides(),
                checksumService.computePlan(withoutChecksum));
    }

    private CompositionModels.CompositionOperation copyOperation(
            CompositionModels.CompositionOperation operation, ContractModels.Bounds bounds,
            ContractTypes.TransformConstraint requested, ContractTypes.TransformConstraint allowed) {
        return new CompositionModels.CompositionOperation(
                operation.operationId(), operation.operationType(), operation.pageReferenceId(),
                operation.nativeObjectReference(), operation.componentId(), operation.componentObjectAction(),
                operation.slotId(), operation.blockId(), operation.contentSha256(), operation.assetRequirementId(),
                operation.approvedAssetId(), operation.assetType(), bounds, requested, allowed);
    }

    private int operationIndex(List<CompositionModels.CompositionOperation> operations, MatrixTarget target) {
        for (int index = 0; index < operations.size(); index++) {
            CompositionModels.CompositionOperation operation = operations.get(index);
            if (target == MatrixTarget.TEXT && operation.operationType()
                    == ContractTypes.CompositionOperationType.FILL_TEXT_SLOT) {
                return index;
            }
            if (target == MatrixTarget.PICTURE && operation.operationType()
                    == ContractTypes.CompositionOperationType.FILL_ASSET_SLOT) {
                return index;
            }
            if (target == MatrixTarget.SHAPE && operation.operationType()
                    == ContractTypes.CompositionOperationType.USE_OR_CLONE_COMPONENT_OBJECT
                    && operation.nativeObjectReference().objectType() == ContractTypes.ObjectType.SHAPE) {
                return index;
            }
            if (target == MatrixTarget.COMPONENT && operation.operationType()
                    == ContractTypes.CompositionOperationType.USE_OR_CLONE_COMPONENT_OBJECT
                    && operation.nativeObjectReference().objectType() == ContractTypes.ObjectType.TEXT) {
                return index;
            }
        }
        throw new AssertionError("matrix operation missing for " + target);
    }

    private ContractModels.Bounds currentBounds(MatrixTarget target) {
        return target == MatrixTarget.PICTURE
                ? new ContractModels.Bounds(6000000, 500000, 4000000, 4000000)
                : new ContractModels.Bounds(500000, 500000, 5000000, 2500000);
    }

    private ContractModels.Bounds legalBaseBounds(MatrixTarget target) {
        return currentBounds(target);
    }

    private ContractModels.Bounds targetBounds(
            ContractTypes.TransformConstraint strategy, MatrixTarget target) {
        ContractModels.Bounds current = currentBounds(target);
        return switch (strategy) {
            case FIXED, RESPONSIVE -> current;
            case TRANSLATE_ONLY -> new ContractModels.Bounds(
                    current.leftEmu() + 100000, current.topEmu() + 100000,
                    current.widthEmu(), current.heightEmu());
            case UNIFORM_SCALE -> new ContractModels.Bounds(
                    current.leftEmu(), current.topEmu(), current.widthEmu() / 2, current.heightEmu() / 2);
            case STRETCH_X -> new ContractModels.Bounds(
                    current.leftEmu(), current.topEmu(), current.widthEmu() / 2, current.heightEmu());
            case STRETCH_Y -> new ContractModels.Bounds(
                    current.leftEmu(), current.topEmu(), current.widthEmu(), current.heightEmu() / 2);
        };
    }

    private ContractModels.Bounds outOfRangeBounds(
            ContractTypes.TransformConstraint strategy, MatrixTarget target) {
        ContractModels.Bounds current = currentBounds(target);
        return switch (strategy) {
            case FIXED -> new ContractModels.Bounds(
                    current.leftEmu() + 1, current.topEmu(), current.widthEmu(), current.heightEmu());
            case TRANSLATE_ONLY -> new ContractModels.Bounds(
                    current.leftEmu(), current.topEmu(), current.widthEmu() + 1, current.heightEmu());
            case UNIFORM_SCALE -> new ContractModels.Bounds(
                    current.leftEmu(), current.topEmu(), current.widthEmu() / 2, current.heightEmu() / 2 + 1);
            case STRETCH_X -> new ContractModels.Bounds(
                    current.leftEmu(), current.topEmu() + 1, current.widthEmu() / 2, current.heightEmu());
            case STRETCH_Y -> new ContractModels.Bounds(
                    current.leftEmu() + 1, current.topEmu(), current.widthEmu(), current.heightEmu() / 2);
            case RESPONSIVE -> current;
        };
    }

    private ContractModels.Bounds expectedGeometry(
            ContractTypes.TransformConstraint strategy, ContractModels.Bounds requested) {
        ContractModels.Bounds current = requested;
        return switch (strategy) {
            case FIXED, RESPONSIVE -> current;
            case TRANSLATE_ONLY -> new ContractModels.Bounds(
                    requested.leftEmu(), requested.topEmu(),
                    requested.widthEmu(), requested.heightEmu());
            case UNIFORM_SCALE, STRETCH_X, STRETCH_Y -> requested;
        };
    }

    private ContractModels.Bounds readGeometry(Path artifact, MatrixTarget target) throws Exception {
        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            byte[] bytes = zip.getInputStream(zip.getEntry("ppt/slides/slide1.xml")).readAllBytes();
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
            String objectId = target == MatrixTarget.PICTURE ? "shape-image" : "shape-body";
            Element object = null;
            var names = document.getElementsByTagNameNS("*", "cNvPr");
            for (int index = 0; index < names.getLength(); index++) {
                Element cNvPr = (Element) names.item(index);
                if (!objectId.equals(cNvPr.getAttribute("id"))) {
                    continue;
                }
                org.w3c.dom.Node node = cNvPr;
                while (node != null && node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    if ("sp".equals(node.getLocalName()) || "pic".equals(node.getLocalName())) {
                        object = (Element) node;
                        break;
                    }
                    node = node.getParentNode();
                }
            }
            assertThat(object).isNotNull();
            Element xfrm = (Element) object.getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/drawingml/2006/main", "xfrm").item(0);
            Element off = directChild(xfrm, "off");
            Element ext = directChild(xfrm, "ext");
            return new ContractModels.Bounds(Integer.parseInt(off.getAttribute("x")),
                    Integer.parseInt(off.getAttribute("y")), Integer.parseInt(ext.getAttribute("cx")),
                    Integer.parseInt(ext.getAttribute("cy")));
        }
    }

    private Element directChild(Element parent, String localName) {
        for (org.w3c.dom.Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        throw new AssertionError("missing XML child " + localName);
    }

    private void assertArtifactIdentity(SamePackagePowerPointExecutor.ExecutionResult result) throws Exception {
        assertThat(result.artifact()).isNotNull();
        Path artifact = Path.of(result.artifact().absolutePath());
        assertThat(Files.isRegularFile(artifact)).isTrue();
        assertThat(result.artifact().sha256()).isEqualTo(sha256(artifact));
        assertThat(result.artifact().size()).isEqualTo(Files.size(artifact));
    }

    private void assertApiArtifactCommitted(ExecutorModels.ExecuteRequest request, JsonNode body) throws Exception {
        Path attempt = MOCK_MVC_OUTPUT_ROOT.resolve(request.generationJob().executionAttemptId())
                .toAbsolutePath().normalize();
        Path artifact = MOCK_MVC_OUTPUT_ROOT.resolve(body.path("artifacts").get(0).path("storageKey").asText())
                .toAbsolutePath().normalize();
        assertThat(artifact).isEqualTo(attempt.resolve("generation-result.pptx"));
        assertThat(Files.isDirectory(attempt)).isTrue();
        assertThat(Files.isRegularFile(attempt.resolve("attempt-binding.txt"))).isTrue();
        assertThat(Files.isRegularFile(attempt.resolve("artifact-manifest.txt"))).isTrue();
        assertThat(Files.isRegularFile(attempt.resolve("generation-result.pptx"))).isTrue();
        assertThat(Files.notExists(attempt.resolve("working-copy.pptx"))).isTrue();
        assertThat(Files.notExists(attempt.resolve("artifact.tmp"))).isTrue();
    }

    private void assertNoApiAttemptOrArtifact(ExecutorModels.ExecuteRequest request, String caseId)
            throws Exception {
        Path attempt = MOCK_MVC_OUTPUT_ROOT.resolve(request.generationJob().executionAttemptId());
        assertThat(Files.notExists(attempt)).as(caseId + " attempt").isTrue();
        assertThat(findGeneratedArtifacts(MOCK_MVC_OUTPUT_ROOT)).as(caseId + " artifacts").isEmpty();
        assertThat(findOutputFilesNamed(MOCK_MVC_OUTPUT_ROOT, "generation-result.pptx"))
                .as(caseId + " generation result").isEmpty();
        assertThat(findOutputFilesNamed(MOCK_MVC_OUTPUT_ROOT, "artifact-manifest.txt"))
                .as(caseId + " artifact manifests").isEmpty();
        assertThat(findOutputFilesNamed(MOCK_MVC_OUTPUT_ROOT, "working-copy.pptx", "artifact.tmp"))
                .as(caseId + " temporary files").isEmpty();
        assertThat(findOutputDirectories(MOCK_MVC_OUTPUT_ROOT))
                .as(caseId + " attempt/temporary directories").isEmpty();
    }

    private void assertIllegalExecuteResponse(
            JsonNode body, ExecutorModels.ExecuteRequest request, String caseId) throws Exception {
        assertStructuredExecuteError(body, caseId);
        assertThat(body.has("artifact")).as(caseId + " artifact field").isFalse();
        assertThat(body.path("artifacts")).as(caseId + " artifacts field").isEmpty();
        assertThat(body.toString())
                .as(caseId + " internal error leakage")
                .doesNotContain("NullPointerException", "java.", "secret", "password", "token");
        assertNoApiAttemptOrArtifact(request, caseId);
    }

    private List<Path> findGeneratedArtifacts(Path root) throws Exception {
        if (Files.notExists(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".pptx") || name.endsWith(".zip");
                    })
                    .toList();
        }
    }

    private List<Path> findOutputFilesNamed(Path root, String... names) throws Exception {
        if (Files.notExists(root)) {
            return List.of();
        }
        List<String> expected = List.of(names);
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> expected.contains(path.getFileName().toString()))
                    .toList();
        }
    }

    private List<Path> findOutputDirectories(Path root) throws Exception {
        if (Files.notExists(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .toList();
        }
    }

    private static Path createTemporaryDirectory() {
        try {
            return Files.createTempDirectory("same-package-mockmvc-output-");
        } catch (Exception exception) {
            throw new IllegalStateException("cannot create MockMvc output root", exception);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void assertArtifactIdentity(
            ExecutorModels.ExecuteRequest request,
            SamePackagePowerPointExecutor.ExecutionResult result,
            String caseId) throws Exception {
        assertArtifactIdentity(result);
        Path artifact = Path.of(result.artifact().absolutePath());
        Path attempt = temp.resolve("executor-output")
                .resolve(request.generationJob().executionAttemptId()).toAbsolutePath().normalize();
        assertThat(artifact).as(caseId).isEqualTo(attempt.resolve("generation-result.pptx"));
    }

    private void assertAttemptCommitted(
            ExecutorModels.ExecuteRequest request,
            SamePackagePowerPointExecutor.ExecutionResult result,
            String caseId) {
        Path attempt = temp.resolve("executor-output")
                .resolve(request.generationJob().executionAttemptId());
        assertThat(Files.isDirectory(attempt)).as(caseId).isTrue();
        assertThat(Files.isRegularFile(attempt.resolve("attempt-binding.txt"))).as(caseId).isTrue();
        assertThat(Files.isRegularFile(attempt.resolve("artifact-manifest.txt"))).as(caseId).isTrue();
        assertThat(Files.isRegularFile(attempt.resolve("generation-result.pptx"))).as(caseId).isTrue();
        assertThat(Files.notExists(attempt.resolve("working-copy.pptx"))).as(caseId).isTrue();
        assertThat(Files.notExists(attempt.resolve("artifact.tmp"))).as(caseId).isTrue();
        assertThat(result.artifact().absolutePath()).as(caseId)
                .isEqualTo(attempt.resolve("generation-result.pptx").toAbsolutePath().toString());
    }

    private void assertAttemptCleaned(ExecutorModels.ExecuteRequest request, String caseId) {
        Path attempt = temp.resolve("executor-output")
                .resolve(request.generationJob().executionAttemptId());
        assertThat(Files.notExists(attempt)).as(caseId).isTrue();
    }

    private void assertBlockingDiagnostics(
            SamePackagePowerPointExecutor.ExecutionResult result, String caseId) {
        assertThat(result.diagnostics()).as(caseId).isNotEmpty();
        assertThat(result.diagnostics()).as(caseId)
                .anySatisfy(diagnostic -> assertThat(diagnostic.impact()).as(caseId)
                        .isEqualTo(ContractTypes.DiagnosticImpact.JOB_BLOCKING));
    }

    private void assertMatrixObjectIdentity(
            ExecutorModels.ExecuteRequest request, MatrixTarget target, String caseId) {
        CompositionModels.CompositionOperation operation = request.plan().slides().get(0).operations().stream()
                .filter(item -> target == MatrixTarget.TEXT
                        ? item.operationType() == ContractTypes.CompositionOperationType.FILL_TEXT_SLOT
                        : target == MatrixTarget.PICTURE
                        ? item.operationType() == ContractTypes.CompositionOperationType.FILL_ASSET_SLOT
                        : item.operationType() == ContractTypes.CompositionOperationType.USE_OR_CLONE_COMPONENT_OBJECT
                        && item.nativeObjectReference() != null
                        && item.nativeObjectReference().objectType() == expectedXmlObject(target).objectType())
                .findFirst().orElseThrow();
        MatrixObjectIdentity expected = expectedXmlObject(target);
        if (target == MatrixTarget.TEXT || target == MatrixTarget.PICTURE) {
            ContractModels.TemplateComponent component = request.templateProfile().components().stream()
                    .filter(item -> item.componentId().equals(
                            target == MatrixTarget.PICTURE ? "component-image" : "component-body"))
                    .findFirst().orElseThrow();
            ContractModels.StableObjectReference reference = component.shapeRefs().stream()
                    .filter(item -> item.objectId().equals(expected.objectId()))
                    .findFirst().orElse(null);
            assertThat(reference).as(caseId).isNotNull();
            assertThat(reference.objectType()).as(caseId).isEqualTo(expected.objectType());
        } else {
            assertThat(operation.nativeObjectReference().objectId()).as(caseId).isEqualTo(expected.objectId());
            assertThat(operation.nativeObjectReference().objectType()).as(caseId).isEqualTo(expected.objectType());
        }
    }

    private MatrixObjectIdentity readXmlObjectIdentity(Path artifact, MatrixTarget target) throws Exception {
        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            byte[] bytes = zip.getInputStream(zip.getEntry("ppt/slides/slide1.xml")).readAllBytes();
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
            String objectId = expectedXmlObject(target).objectId();
            var names = document.getElementsByTagNameNS("*", "cNvPr");
            for (int index = 0; index < names.getLength(); index++) {
                Element cNvPr = (Element) names.item(index);
                if (!objectId.equals(cNvPr.getAttribute("id"))) {
                    continue;
                }
                org.w3c.dom.Node node = cNvPr;
                while (node != null && node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    if ("sp".equals(node.getLocalName()) || "pic".equals(node.getLocalName())) {
                        return new MatrixObjectIdentity(objectId, expectedXmlObject(target).objectType(), node.getLocalName());
                    }
                    node = node.getParentNode();
                }
            }
        }
        throw new AssertionError("missing XML object " + target);
    }

    private MatrixObjectIdentity expectedXmlObject(MatrixTarget target) {
        return switch (target) {
            case TEXT, COMPONENT -> new MatrixObjectIdentity("shape-body", ContractTypes.ObjectType.TEXT, "sp");
            case SHAPE -> new MatrixObjectIdentity("shape-body", ContractTypes.ObjectType.SHAPE, "sp");
            case PICTURE -> new MatrixObjectIdentity("shape-image", ContractTypes.ObjectType.PICTURE, "pic");
        };
    }

    private void assertStructuredExecuteError(JsonNode body, String caseId) {
        if (body.path("diagnostics").isArray()) {
            assertThat(body.path("feedback").isMissingNode()).as(caseId).isTrue();
        } else {
            assertThat(body.path("feedback").isArray()).as(caseId + " response=" + body).isTrue();
        }
    }

    private List<ArraySpec> declaredExecuteArrays() {
        return List.of(
                objectArray("approvedAssetFiles", List.of(), "assetRequirementId", "sha256", "not-a-hash", false),
                objectArray("specification.slides", List.of("specification"), "slideId", "pageNumber", "0", true),
                objectArray("specification.slides[0].contentBlocks", List.of("specification", "slides", "[0]"),
                        "blockId", "type", "NOT_A_CONTENT_TYPE", false),
                objectArray("specification.slides[0].assetRequirements", List.of("specification", "slides", "[0]"),
                        "assetId", "approvalStatus", "NOT_APPROVED", false),
                objectArray("specification.slides[0].provenance", List.of("specification", "slides", "[0]"),
                        "sourceType", "sourceType", "NOT_A_SOURCE", false),
                objectArray("templateProfile.templatePageReferences", List.of("templateProfile"),
                        "pageReferenceId", "sourceSlide", "0", true),
                objectArray("templateProfile.components", List.of("templateProfile"),
                        "componentId", "transformConstraint", "NOT_A_TRANSFORM", true),
                objectArray("templateProfile.components[0].shapeRefs",
                        List.of("templateProfile", "components", "[0]"), "objectId", "objectType", "NOT_AN_OBJECT", true),
                primitiveArray("templateProfile.components[0].childComponentIds",
                        List.of("templateProfile", "components", "[0]"), false, "missing-child"),
                objectArray("templateProfile.components[0].slots",
                        List.of("templateProfile", "components", "[0]"), "slotId", "required", "not-a-boolean", true),
                primitiveArray("templateProfile.components[0].slots[0].acceptedContentTypes",
                        List.of("templateProfile", "components", "[0]", "slots", "[0]"), true,
                        "NOT_A_CONTENT_TYPE"),
                objectArray("approvedAssetManifest.entries", List.of("approvedAssetManifest"),
                        "assetRequirementId", "resolution", "NOT_A_RESOLUTION", false),
                objectArray("specification.slides[0].semanticLayout.regions",
                        List.of("specification", "slides", "[0]", "semanticLayout"), "regionId", "preferredPosition",
                        "NOT_A_POSITION", false),
                primitiveArray("templateProfile.templatePageReferences[0].objectIds",
                        List.of("templateProfile", "templatePageReferences", "[0]"), true, "missing-object"),
                objectArray("plan.slides", List.of("plan"), "slideId", "pageNumber", "0", true),
                objectArray("plan.slides[0].operations", List.of("plan", "slides", "[0]"),
                        "operationId", "operationType", "NOT_AN_OPERATION", true),
                objectArray("plan.slides[0].layout.componentPlacements",
                        List.of("plan", "slides", "[0]", "layout"), "componentId", "allowedTransform",
                        "NOT_A_TRANSFORM", false),
                objectArray("plan.slides[0].layout.componentPlacements[0].nativeObjectReferences",
                        List.of("plan", "slides", "[0]", "layout", "componentPlacements", "[0]"),
                        "objectId", "objectType", "NOT_AN_OBJECT", true),
                objectArray("plan.slides[0].layout.componentSelections",
                        List.of("plan", "slides", "[0]", "layout"), "bindingId", "bindingKind", "NOT_A_BINDING", false),
                objectArray("plan.slides[0].layout.slotPlacements",
                        List.of("plan", "slides", "[0]", "layout"), "placementId", "bindingKind", "NOT_A_BINDING", false));
    }

    private ArraySpec objectArray(String label, List<String> parentPath, String requiredField,
                                  String invalidField, String invalidValue, boolean minItemsOne) {
        return new ArraySpec(label, parentPath, label.substring(label.lastIndexOf('.') + 1),
                true, requiredField, invalidField, invalidValue, minItemsOne);
    }

    private ArraySpec primitiveArray(String label, List<String> parentPath,
                                    boolean minItemsOne, String invalidValue) {
        return new ArraySpec(label, parentPath, label.substring(label.lastIndexOf('.') + 1),
                false, null, null, invalidValue, minItemsOne);
    }

    private void applyArrayMutation(ObjectNode root, ArraySpec spec, ArrayMutation mutation) {
        ObjectNode parent = (ObjectNode) nodeAt(root, spec.parentPath());
        ArrayNode array = (ArrayNode) parent.path(spec.field());
        if (array.isEmpty() && mutation != ArrayMutation.WRONG_TYPE
                && mutation != ArrayMutation.EMPTY_ARRAY) {
            if (spec.objectElements()) {
                array.add(objectMapper.createObjectNode());
            } else {
                array.add(objectMapper.getNodeFactory().textNode(spec.invalidValue()));
            }
        }
        switch (mutation) {
            case MISSING_FIELD -> ((ObjectNode) array.get(0)).remove(spec.requiredField());
            case WRONG_TYPE -> parent.set(spec.field(), objectMapper.createObjectNode());
            case NULL_ELEMENT -> array.set(0, objectMapper.nullNode());
            case EMPTY_OBJECT -> array.set(0, objectMapper.createObjectNode());
            case UNKNOWN_FIELD -> ((ObjectNode) array.get(0)).put("unknown", "secret");
            case EMPTY_ARRAY -> parent.set(spec.field(), objectMapper.createArrayNode());
            case INVALID_ENUM_OR_BINDING -> {
                if (spec.objectElements()) {
                ((ObjectNode) array.get(0)).put(spec.invalidField(), spec.invalidValue());
                } else {
                    array.set(0, objectMapper.getNodeFactory().textNode(spec.invalidValue()));
                }
            }
        }
    }

    private JsonNode nodeAt(JsonNode root, List<String> path) {
        JsonNode current = root;
        for (String part : path) {
            if (part.startsWith("[")) {
                current = current.path(Integer.parseInt(part.substring(1, part.length() - 1)));
            } else {
                current = current.path(part);
            }
        }
        return current;
    }

    private record MatrixObjectIdentity(
            String objectId, ContractTypes.ObjectType objectType, String xmlElement) {
    }

    private record ArraySpec(
            String label,
            List<String> parentPath,
            String field,
            boolean objectElements,
            String requiredField,
            String invalidField,
            String invalidValue,
            boolean minItemsOne) {
        private EmptyArrayContract emptyArrayContract() {
            return switch (label) {
                case "approvedAssetFiles" -> EmptyArrayContract.ILLEGAL_400;
                case "approvedAssetManifest.entries" -> EmptyArrayContract.ILLEGAL_400;
                default -> EmptyArrayContract.ILLEGAL_400;
            };
        }

        private int emptyArrayStatus() {
            return switch (emptyArrayContract()) {
                case LEGAL_PARTIAL_ARTIFACT -> 200;
                case ILLEGAL_400 -> 400;
                case ILLEGAL_422 -> 422;
            };
        }

        private List<ArrayMutation> mutations() {
            if (objectElements) {
                return List.of(ArrayMutation.MISSING_FIELD, ArrayMutation.WRONG_TYPE,
                        ArrayMutation.NULL_ELEMENT, ArrayMutation.EMPTY_OBJECT,
                        ArrayMutation.UNKNOWN_FIELD, ArrayMutation.EMPTY_ARRAY,
                        ArrayMutation.INVALID_ENUM_OR_BINDING);
            }
            return List.of(ArrayMutation.WRONG_TYPE, ArrayMutation.NULL_ELEMENT,
                    ArrayMutation.EMPTY_ARRAY, ArrayMutation.INVALID_ENUM_OR_BINDING);
        }
    }

    private enum ArrayMutation {
        MISSING_FIELD, WRONG_TYPE, NULL_ELEMENT, EMPTY_OBJECT,
        UNKNOWN_FIELD, EMPTY_ARRAY, INVALID_ENUM_OR_BINDING
    }

    private enum EmptyArrayContract {
        LEGAL_PARTIAL_ARTIFACT, ILLEGAL_400, ILLEGAL_422
    }

    private enum MatrixTarget { TEXT, SHAPE, COMPONENT, PICTURE }

    private enum MatrixOutcome { LEGAL, OUT_OF_RANGE, MISSING, INVALID }

    @FunctionalInterface
    private interface Mutation {
        void apply(ObjectNode tree);
    }

    private ExecutorModels.ExecuteRequest buildExecuteRequest(
            Path source, Path asset, String generationJobId, String attemptId) throws Exception {
        writeFixture(source);
        Files.write(asset, ONE_PIXEL_PNG);
        String assetHash = sha256(asset);
        CompositionModels.EngineComposePlanRequest base = CompositionTestSupport.request(objectMapper);
        CompositionModels.ApprovedAssetManifestEntry manifestEntry =
                new CompositionModels.ApprovedAssetManifestEntry(
                        "asset-001", APPROVED_ASSET, "approved-asset-001", IMAGE, assetHash,
                        asset.toAbsolutePath().normalize().toString(), Files.size(asset),
                        java.time.OffsetDateTime.parse(Files.getLastModifiedTime(asset).toInstant().toString()));
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.withManifestEntries(
                objectMapper, base, generationJobId, 2, List.of(manifestEntry));
        request = CompositionTestSupport.withGenerationJob(
                objectMapper, request, generationJobId, attemptId,
                "engine-build-test", "executor-adapter-none", "font-env-test");
        CompositionModels.ValidatedExecutionPackage executionPackage = contractGate.validateCompose(request).executionPackage();
        ComponentResolver.ResolverResult resolved = componentResolver.resolveForComposition(
                request.specification().slides().get(0), request.templateProfile(),
                executionPackage.manifestEntriesByRequirementId());
        TemplatePageResolver.SelectionResult page = templatePageResolver.resolve(
                request.specification().slides().get(0), request.templateProfile());
        LayoutResolver.LayoutResult layout = layoutResolver.resolveForComposition(
                request.specification().slides().get(0), request.templateProfile(), resolved, page.selection());
        CompositionModels.ComposedPresentationPlan plan = slideComposer.compose(
                executionPackage, List.of(layout.plan())).plan();
        return new ExecutorModels.ExecuteRequest(
                ContractTypes.EXECUTOR_CONTRACT_V1, request.requestId(), request.generationJob(),
                request.specification(), request.templateProfile(), request.approvedAssetManifest(), plan,
                new ExecutorModels.TemplateSourceBinding(
                        source.toAbsolutePath().normalize().toString(), sha256(source), Files.size(source),
                        Files.getLastModifiedTime(source).toInstant().toString()),
                List.of(new ExecutorModels.ApprovedAssetFile(
                        "asset-001", "approved-asset-001", asset.toAbsolutePath().normalize().toString(), assetHash)));
    }

    private ExecutorModels.ExecuteRequest withSourceBinding(
            ExecutorModels.ExecuteRequest request, Path source) throws Exception {
        return new ExecutorModels.ExecuteRequest(
                request.contractVersion(), request.requestId(), request.generationJob(), request.specification(),
                request.templateProfile(), request.approvedAssetManifest(), request.plan(),
                new ExecutorModels.TemplateSourceBinding(
                        source.toAbsolutePath().normalize().toString(), sha256(source), Files.size(source),
                        Files.getLastModifiedTime(source).toInstant().toString()), request.approvedAssetFiles());
    }

    private void replaceSlideTextWidth(Path source, String width) throws Exception {
        Path replacement = temp.resolve("replacement.pptx");
        try (ZipFile zip = new ZipFile(source.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(replacement))) {
            zip.stream().forEach(entry -> {
                try {
                    output.putNextEntry(new ZipEntry(entry.getName()));
                    byte[] bytes = zip.getInputStream(entry).readAllBytes();
                    if ("ppt/slides/slide1.xml".equals(entry.getName())) {
                        String text = new String(bytes, StandardCharsets.UTF_8).replace(
                                "cx=\"5000000\"", "cx=\"" + width + "\"");
                        bytes = text.getBytes(StandardCharsets.UTF_8);
                    }
                    output.write(bytes);
                    output.closeEntry();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
        Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
    }

    private void duplicatePngDefault(Path source) throws Exception {
        Path replacement = temp.resolve("duplicate-types-replacement.pptx");
        try (ZipFile zip = new ZipFile(source.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(replacement))) {
            zip.stream().forEach(entry -> {
                try {
                    output.putNextEntry(new ZipEntry(entry.getName()));
                    byte[] bytes = zip.getInputStream(entry).readAllBytes();
                    if ("[Content_Types].xml".equals(entry.getName())) {
                        String text = new String(bytes, StandardCharsets.UTF_8).replace(
                                "</Types>", "<Default Extension=\"png\" ContentType=\"image/png\"/></Types>");
                        bytes = text.getBytes(StandardCharsets.UTF_8);
                    }
                    output.write(bytes);
                    output.closeEntry();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
        Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeFixture(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (OutputStream output = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Default Extension="png" ContentType="image/png"/>
                      <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                      <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                    </Types>""");
            entry(zip, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/presentation.xml", """
                    <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <p:sldIdLst><p:sldId id="1" r:id="rId1"/></p:sldIdLst>
                    </p:presentation>""");
            entry(zip, "ppt/_rels/presentation.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/slides/slide1.xml", """
                    <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <p:cSld><p:spTree>
                        <p:nvGrpSpPr><p:cNvPr id="1" name="Group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
                        <p:sp><p:nvSpPr><p:cNvPr id="shape-body" name="shape-image"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="500000" y="500000"/><a:ext cx="5000000" cy="2500000"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr/><a:t>原文</a:t></a:r></a:p></p:txBody></p:sp>
                        <p:pic><p:nvPicPr><p:cNvPr id="shape-image" name="shape-body"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="6000000" y="500000"/><a:ext cx="4000000" cy="4000000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>
                      </p:spTree></p:cSld>
                    </p:sld>""");
            entry(zip, "ppt/slides/_rels/slide1.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image1.png"/>
                      <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/slideLayouts/slideLayout1.xml", "<p:sldLayout xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>".getBytes(StandardCharsets.UTF_8));
            entry(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/slideMasters/slideMaster1.xml", "<p:sldMaster xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>".getBytes(StandardCharsets.UTF_8));
            entry(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/theme/theme1.xml", "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Default\"><a:themeElements/></a:theme>".getBytes(StandardCharsets.UTF_8));
            entry(zip, "ppt/media/image1.png", ONE_PIXEL_PNG);
        }
    }

    private void entry(ZipOutputStream zip, String name, String value) throws Exception {
        entry(zip, name, value.getBytes(StandardCharsets.UTF_8));
    }

    private void entry(ZipOutputStream zip, String name, byte[] value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value);
        zip.closeEntry();
    }

    private String sha256(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
