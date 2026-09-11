package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ApprovalStatus.PENDING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetType.VIDEO;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentSourceType.MATERIAL;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.PreferredPosition.CENTER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ObjectType.TEXT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.FIXED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.TRANSLATE_ONLY;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ComposePlanApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validRequestReturnsVersionedDeterministicPlanWithoutTeacherContentOrSources() throws Exception {
        var request = CompositionTestSupport.request(objectMapper);

        String responseText = mockMvc.perform(post("/internal/v1/compose-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("2.0.0"))
                .andExpect(jsonPath("$.planContractVersion").value("2.0.0"))
                .andExpect(jsonPath("$.feedbackContractVersion").value("2.0.0"))
                .andExpect(jsonPath("$.feedback.status").value("SUCCEEDED_WITH_FEEDBACK"))
                .andExpect(jsonPath("$.feedback.generationJobId").value("job-001"))
                .andExpect(jsonPath("$.feedback.executionAttemptId").value("attempt-001"))
                .andExpect(jsonPath("$.plan.originalSlideCount").value(1))
                .andExpect(jsonPath("$.plan.slides[0].layout.templatePage.pageReferenceId")
                        .value("page-ref-001"))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(responseText);
        JsonNode operations = response.at("/plan/slides/0/operations");
        assertThat(operations).hasSize(6);
        assertThat(operationTypes(operations)).containsExactly(
                "PRESERVE_BASE_OBJECT", "PRESERVE_BASE_OBJECT",
                "USE_OR_CLONE_COMPONENT_OBJECT", "USE_OR_CLONE_COMPONENT_OBJECT",
                "FILL_TEXT_SLOT", "FILL_ASSET_SLOT");
        assertThat(operations.get(4).path("blockId").asText()).isEqualTo("block-001");
        assertThat(operations.get(4).path("contentSha256").asText())
                .isEqualTo(new ChecksumService(objectMapper).sha256Utf8(
                        request.specification().slides().get(0).contentBlocks().get(0).content()));
        assertThat(operations.get(5).path("assetRequirementId").asText()).isEqualTo("asset-001");
        assertThat(operations.get(5).path("approvedAssetId").asText())
                .isEqualTo("approved-asset-001");
        assertThat(operations.get(5).path("contentSha256").asText())
                .isEqualTo(request.approvedAssetManifest().entries().get(0).contentSha256());
        assertThat(response.at("/plan/approvedAssetManifestReference/manifestChecksum").asText())
                .isEqualTo(request.approvedAssetManifest().manifestChecksum());
        assertThat(response.at("/plan/slides/0/layout/componentSelections/0/selectedComponentId").asText())
                .isEqualTo("component-body");
        assertThat(response.at("/plan/slides/0/layout/componentSelections/0/selectionBasis").asText())
                .isEqualTo("PROFILE_V1_CONFIDENCE_FALLBACK_TOTAL_ORDER");
        assertThat(response.at("/plan/textFitBoundary/measurementStatus").asText())
                .isEqualTo("NOT_IMPLEMENTED");
        assertThat(response.at("/plan/textFitBoundary/allowPowerPointAutoFit").asBoolean()).isFalse();
        assertThat(response.at("/feedback/diagnostics/0/impact").asText()).isEqualTo("NON_BLOCKING");
        for (int index = 0; index < 4; index++) {
            JsonNode operation = operations.get(index);
            assertThat(operation.has("nativeObjectReference")).isTrue();
            assertThat(operation.has("sourceSlide")).isFalse();
            assertThat(operation.has("objectId")).isFalse();
            assertThat(operation.has("objectType")).isFalse();
        }
        assertThat(operations.get(0).at("/nativeObjectReference/objectType").asText())
                .isEqualTo("UNKNOWN");

        assertThat(responseText)
                .doesNotContain("植物利用光能")
                .doesNotContain("material-001")
                .doesNotContain("sourceReference")
                .doesNotContain("provider")
                .doesNotContain("model");
    }

    @Test
    void missingExactTemplatePageReturns422WithoutFallbackOrPlan() throws Exception {
        var profile = CompositionTestSupport.withPages(
                ContractFixtures.profile(),
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.TemplatePageReference(
                        "page-title", 1, "TITLE", List.of("shape-body", "shape-image"))));
        var request = CompositionTestSupport.request(
                objectMapper, "req-page-missing", List.of(ContractFixtures.slide()), profile);

        String response = postCompose(request, 422);

        assertThat(response).contains("TEMPLATE_PAGE_MISSING", "TEMPLATE_PAGE_RESOLVER");
        assertThat(response).doesNotContain("\"plan\"");
    }

    @Test
    void layoutErrorsOnAnyPageReturn422FailedWithoutPlan() throws Exception {
        var outOfBoundsProfile = CompositionTestSupport.withSlotBounds(
                ContractFixtures.profile(), "slot-body",
                new com.auvdidao.a12.pptengine.contract.ContractModels.Bounds(
                        12191900, 500000, 200, 1000));
        String outOfBounds = postCompose(CompositionTestSupport.request(
                objectMapper, "req-out", List.of(
                        ContractFixtures.slide(),
                        new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                                "slide-002", 2, "标题", "教学目标",
                                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptContentBlock(
                                        "block-002", BODY, "第二页锁定内容", MATERIAL, "material-002", true)),
                                ContractFixtures.slide().semanticLayout(), List.of(), List.of(), "")),
                outOfBoundsProfile), 422);
        assertThat(outOfBounds).contains("TEMPLATE_PAGE_MISSING", "TEMPLATE_PAGE_RESOLVER",
                "\"status\":\"FAILED\"", "\"impact\":\"JOB_BLOCKING\"");
        assertThat(outOfBounds).contains("\"pageNumber\":2").doesNotContain("\"plan\"");

        var unsafeProfile = CompositionTestSupport.withSlotBounds(
                ContractFixtures.profile(), "slot-body",
                new com.auvdidao.a12.pptengine.contract.ContractModels.Bounds(0, 0, 1000, 1000));
        String unsafe = postCompose(CompositionTestSupport.request(
                objectMapper, "req-unsafe", List.of(
                        ContractFixtures.slide(),
                        new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                                "slide-002", 2, "标题", "教学目标",
                                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptContentBlock(
                                        "block-002", BODY, "第二页锁定内容", MATERIAL, "material-002", true)),
                                ContractFixtures.slide().semanticLayout(), List.of(), List.of(), "")),
                unsafeProfile), 422);
        assertThat(unsafe).contains("SAFE_AREA_VIOLATION", "LAYOUT_RESOLVER",
                "\"status\":\"FAILED\"", "\"impact\":\"JOB_BLOCKING\"")
                .contains("\"pageNumber\":2")
                .doesNotContain("\"plan\"");
    }

    @Test
    void semanticRegionCapacityErrorIsJobBlockingWithoutPlan() throws Exception {
        var baseSlide = ContractFixtures.slide();
        var second = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptContentBlock(
                "block-002", BODY, "第二段原文", MATERIAL, "material-002", true);
        var slide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                baseSlide.slideId(), baseSlide.pageNumber(), baseSlide.title(), baseSlide.teachingGoal(),
                List.of(baseSlide.contentBlocks().get(0), second),
                new com.auvdidao.a12.pptengine.contract.ContractModels.SemanticLayout(
                        "BODY", List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.SemanticRegion(
                        "region-only", "BODY", CENTER, 1)), baseSlide.semanticLayout().requestedTransform()),
                baseSlide.assetRequirements(), baseSlide.provenance(), baseSlide.notes());
        var profile = CompositionTestSupport.withSlotMaxItems(
                ContractFixtures.profile(), "slot-body", 2);

        String response = postCompose(CompositionTestSupport.request(
                objectMapper, "req-region-capacity", List.of(slide), profile), 422);

        assertThat(response).contains("SEMANTIC_REGION_CAPACITY_EXCEEDED", "block-002",
                "\"status\":\"FAILED\"", "\"impact\":\"JOB_BLOCKING\"")
                .doesNotContain("\"plan\"");
    }

    @Test
    void approvedOptionalOmissionPreservesBothPagesWithNoFillOperation() throws Exception {
        var base = ContractFixtures.slide();
        var asset = base.assetRequirements().get(0);
        var optional = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptAssetReference(
                asset.assetId(), asset.assetType(), asset.source(), PENDING, false, asset.placementIntent());
        var slide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(), base.contentBlocks(),
                base.semanticLayout(), List.of(optional), base.provenance(), base.notes());
        var secondSlide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                "slide-002", 2, base.title(), base.teachingGoal(),
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptContentBlock(
                        "block-002", BODY, "第二页锁定内容", MATERIAL, "material-002", true)),
                base.semanticLayout(), List.of(), base.provenance(), base.notes());
        var approvedRequest = CompositionTestSupport.request(
                objectMapper, "req-omission", List.of(slide, secondSlide), ContractFixtures.profile());
        var omission = new com.auvdidao.a12.pptengine.contract.CompositionModels.ApprovedAssetManifestEntry(
                asset.assetId(),
                com.auvdidao.a12.pptengine.contract.ContractTypes.AssetResolution.APPROVED_OMISSION,
                null, null, null);
        var request = CompositionTestSupport.withManifestEntries(
                objectMapper, approvedRequest, "job-omission", 2, List.of(omission));

        String response = postCompose(request, 200);

        assertThat(response).contains("ASSET_APPROVED_OMISSION", "APPROVED_OMISSION");
        assertThat(response).contains("\"status\":\"SUCCEEDED_WITH_FEEDBACK\"",
                "\"impact\":\"NON_BLOCKING\"", "\"originalSlideCount\":2");
        assertThat(objectMapper.readTree(response).path("plan").path("slides")).hasSize(2);
        assertThat(response).doesNotContain("FILL_ASSET_SLOT")
                .doesNotContain("approved-asset-001");
    }

    @Test
    void unsupportedRequiredAssetFailsClosedWithoutPlan() throws Exception {
        var base = ContractFixtures.slide();
        var asset = base.assetRequirements().get(0);
        var video = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptAssetReference(
                asset.assetId(), VIDEO, asset.source(), asset.approvalStatus(), true, asset.placementIntent());
        var slide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(), base.contentBlocks(),
                base.semanticLayout(), List.of(video), base.provenance(), base.notes());

        String response = postCompose(CompositionTestSupport.request(
                objectMapper, "req-video", List.of(slide), ContractFixtures.profile()), 422);

        assertThat(response).contains("TEMPLATE_PAGE_MISSING", "TEMPLATE_PAGE_RESOLVER",
                "\"status\":\"FAILED\"", "\"impact\":\"JOB_BLOCKING\"");
        assertThat(response).doesNotContain("\"plan\"");
    }

    @Test
    void duplicateIdsAndDanglingProfileReferencesNeverProduceSuccess() throws Exception {
        var base = ContractFixtures.slide();
        var duplicate = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(),
                List.of(base.contentBlocks().get(0), base.contentBlocks().get(0)),
                base.semanticLayout(), base.assetRequirements(), base.provenance(), base.notes());
        String duplicateResponse = postCompose(CompositionTestSupport.request(
                objectMapper, "req-duplicate", List.of(duplicate), ContractFixtures.profile()), 422);
        assertThat(duplicateResponse).contains("DUPLICATE_ID").doesNotContain("\"plan\"");

        var profile = ContractFixtures.profile();
        List<com.auvdidao.a12.pptengine.contract.ContractModels.TemplateComponent> components =
                new ArrayList<>(profile.components());
        var body = components.get(0);
        components.set(0, CompositionTestSupport.copyComponent(
                body, body.sourceSlide(), List.of(
                new com.auvdidao.a12.pptengine.contract.ContractModels.StableObjectReference(
                        body.shapeRefs().get(0).objectType(), "shape-dangling")), body.slots()));
        String danglingResponse = postCompose(CompositionTestSupport.request(
                objectMapper, "req-dangling", List.of(base),
                CompositionTestSupport.withComponents(profile, components)), 422);
        assertThat(danglingResponse).contains("CONTRACT_INVALID").doesNotContain("\"plan\"");
    }

    @Test
    void malformedAndUnknownFieldsReturnSafe400RatherThan500() throws Exception {
        String malformed = mockMvc.perform(post("/internal/v1/compose-plan")
                        .contentType(MediaType.APPLICATION_JSON).content("{malformed"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(malformed).contains("CONTRACT_INVALID")
                .doesNotContain("JsonParseException").doesNotContain("at com.");

        JsonNode raw = objectMapper.valueToTree(CompositionTestSupport.request(objectMapper));
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw)
                .put("unknownField", "provider-secret-response-marker");
        String unknown = mockMvc.perform(post("/internal/v1/compose-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(raw)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(unknown).contains("CONTRACT_INVALID")
                .doesNotContain("provider-secret-response-marker");
    }

    @Test
    void missingGenerationJobOrThirdInputReturns400AndJobBindingDriftReturns422() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode missingJob =
                objectMapper.valueToTree(CompositionTestSupport.request(objectMapper));
        missingJob.remove("generationJob");
        mockMvc.perform(post("/internal/v1/compose-plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(missingJob)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.feedback.diagnostics[0].code").value("CONTRACT_INVALID"))
                .andExpect(jsonPath("$.feedback.status").value("FAILED"));

        com.fasterxml.jackson.databind.node.ObjectNode missingManifest =
                objectMapper.valueToTree(CompositionTestSupport.request(objectMapper));
        missingManifest.remove("approvedAssetManifest");
        mockMvc.perform(post("/internal/v1/compose-plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(missingManifest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.feedback.diagnostics[0].code").value("CONTRACT_INVALID"));

        com.fasterxml.jackson.databind.node.ObjectNode drift =
                objectMapper.valueToTree(CompositionTestSupport.request(objectMapper));
        ((com.fasterxml.jackson.databind.node.ObjectNode) drift.at(
                "/generationJob/specificationBinding")).put("inputId", "spec-drifted");
        mockMvc.perform(post("/internal/v1/compose-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(drift)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.feedback.diagnostics[*].code")
                        .value(org.hamcrest.Matchers.hasItem(
                                "GENERATION_JOB_SPECIFICATION_BINDING_MISMATCH")))
                .andExpect(jsonPath("$.feedback.status").value("FAILED"))
                .andExpect(jsonPath("$.feedback.diagnostics[0].impact").value("JOB_BLOCKING"));
    }

    @Test
    void emptyLockedSlideCanSucceedWithoutFallbackDiagnostics() throws Exception {
        var base = ContractFixtures.slide();
        var empty = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(), List.of(),
                base.semanticLayout(), List.of(), base.provenance(), base.notes());

        String response = postCompose(CompositionTestSupport.request(
                objectMapper, "req-succeeded", List.of(empty), ContractFixtures.profile()), 200);

        assertThat(response).contains("\"status\":\"SUCCEEDED\"", "\"diagnostics\":[]");
    }

    @Test
    void requiredTextOverflowFailsClosedWithoutShrinkingOrTextMutation() throws Exception {
        var base = ContractFixtures.slide();
        String lockedText = "锁定原文".repeat(300);
        var overflowBlock = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptContentBlock(
                "block-overflow", BODY, lockedText, MATERIAL, "material-overflow", true);
        var safeBlock = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptContentBlock(
                "block-safe", BODY, "安全页原文", MATERIAL, "material-safe", true);
        var overflowSlide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                "slide-overflow", 1, base.title(), base.teachingGoal(), List.of(overflowBlock),
                base.semanticLayout(), List.of(), base.provenance(), base.notes());
        var safeSlide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                "slide-safe", 2, base.title(), base.teachingGoal(), List.of(safeBlock),
                base.semanticLayout(), List.of(), base.provenance(), base.notes());
        var request = CompositionTestSupport.request(
                objectMapper, "req-overflow-partial", List.of(overflowSlide, safeSlide),
                ContractFixtures.profile());

        JsonNode response = objectMapper.readTree(postCompose(request, 422));

        assertThat(response.at("/feedback/status").asText()).isEqualTo("FAILED");
        assertThat(response.at("/feedback/diagnostics").toString())
                .contains("TEMPLATE_PAGE_MISSING", "JOB_BLOCKING");
        assertThat(response.has("plan")).isFalse();
        assertThat(request.specification().slides().get(0).contentBlocks().get(0).content())
                .isEqualTo(lockedText);
        assertThat(response.toString()).doesNotContain(lockedText).doesNotContain("PowerPointAutoFit\":true");
    }

    @Test
    void productionComposePathFiltersTransformBeforeStablePageRanking() throws Exception {
        var base = ContractFixtures.profile();
        var slideBase = ContractFixtures.slide();
        var slide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                slideBase.slideId(), slideBase.pageNumber(), slideBase.title(), slideBase.teachingGoal(),
                slideBase.contentBlocks(), slideBase.semanticLayout(), List.of(),
                slideBase.provenance(), slideBase.notes());
        var highSlotIncompatible = componentWithSlots(
                "component-high-incompatible", 1, "shape-high", FIXED, 5);
        var lowSlotCompatible = componentWithSlots(
                "component-low-compatible", 2, "shape-low", TRANSLATE_ONLY, 1);
        var profile = new com.auvdidao.a12.pptengine.contract.ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.projectId(), base.ownerUserId(),
                base.templateVersion(), base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                List.of(
                        new com.auvdidao.a12.pptengine.contract.ContractModels.TemplatePageReference(
                                "page-high", 1, "BODY", List.of("shape-high")),
                        new com.auvdidao.a12.pptengine.contract.ContractModels.TemplatePageReference(
                                "page-low", 2, "BODY", List.of("shape-low"))),
                List.of(highSlotIncompatible, lowSlotCompatible), base.preservedNativeObjects(),
                base.textFitPolicy(), "EXECUTION_READY", base.sourceVersionId(), base.sourceSha256(),
                base.parserSnapshotChecksum());

        var pageDecision = new com.auvdidao.a12.pptengine.layout.TemplatePageResolver()
                .resolve(slide, profile);
        assertThat(pageDecision.selection().pageReferenceId()).isEqualTo("page-low");
        assertThat(pageDecision.rankedFeasiblePageReferenceIds()).containsExactly("page-low");
        assertThat(pageDecision.decisions()).filteredOn(
                decision -> decision.pageReferenceId().equals("page-high"))
                .singleElement().satisfies(decision -> {
                    assertThat(decision.feasible()).isFalse();
                    assertThat(decision.rejectionReasons()).contains("REQUIRED_DEMAND_NOT_FEASIBLE");
                });

        JsonNode response = objectMapper.readTree(postCompose(
                CompositionTestSupport.request(objectMapper, "req-transform-order", List.of(slide), profile), 200));
        assertThat(response.at("/plan/slides/0/layout/templatePage/pageReferenceId").asText())
                .isEqualTo("page-low");
        assertThat(response.at("/plan/slides/0/layout/componentSelections/0/selectedComponentId").asText())
                .isEqualTo("component-low-compatible");
    }

    @Test
    void productionComposePathRejectsStableReferenceReuseBeforeRanking() throws Exception {
        var base = ContractFixtures.profile();
        var slideBase = ContractFixtures.slide();
        var secondBlock = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptContentBlock(
                "block-002", BODY, "第二个内容块", slideBase.contentBlocks().get(0).sourceType(),
                "material-002", true);
        var semanticLayout = new com.auvdidao.a12.pptengine.contract.ContractModels.SemanticLayout(
                slideBase.semanticLayout().primaryRole(),
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.SemanticRegion(
                        "region-001", "BODY", CENTER, 2)), TRANSLATE_ONLY);
        var slide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                slideBase.slideId(), slideBase.pageNumber(), slideBase.title(), slideBase.teachingGoal(),
                List.of(slideBase.contentBlocks().get(0), secondBlock), semanticLayout, List.of(),
                slideBase.provenance(), slideBase.notes());

        var conflicting = componentWithRefsAndSlots(
                "component-conflicting", 1, List.of("shared-native", "shared-native"), 2, false);
        var legalOne = componentWithRefsAndSlots(
                "component-legal-1", 2, List.of("legal-native-1"), 1, false);
        var legalTwo = componentWithRefsAndSlots(
                "component-legal-2", 2, List.of("legal-native-2"), 1, false);
        var profile = new com.auvdidao.a12.pptengine.contract.ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.projectId(), base.ownerUserId(),
                base.templateVersion(), base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                List.of(
                        new com.auvdidao.a12.pptengine.contract.ContractModels.TemplatePageReference(
                                "page-conflicting", 1, "BODY", List.of("shared-native")),
                        new com.auvdidao.a12.pptengine.contract.ContractModels.TemplatePageReference(
                                "page-legal", 2, "BODY", List.of("legal-native-1", "legal-native-2"))),
                List.of(conflicting, legalOne, legalTwo), base.preservedNativeObjects(), base.textFitPolicy(),
                "EXECUTION_READY", base.sourceVersionId(), base.sourceSha256(), base.parserSnapshotChecksum());

        JsonNode response = objectMapper.readTree(postCompose(
                CompositionTestSupport.request(objectMapper, "req-stable-ref-order", List.of(slide), profile), 200));

        assertThat(response.at("/plan/slides/0/layout/templatePage/pageReferenceId").asText())
                .isEqualTo("page-legal");
        assertThat(response.at("/plan/slides/0/layout/componentSelections/0/selectedComponentId").asText())
                .isEqualTo("component-legal-1");
        assertThat(response.at("/plan/slides/0/layout/componentSelections/1/selectedComponentId").asText())
                .isEqualTo("component-legal-2");
    }

    @Test
    void productionComposePathCarriesGlobalAssignmentIntoComponentBinding() throws Exception {
        var base = ContractFixtures.profile();
        var slideBase = ContractFixtures.slide();
        var secondBlock = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptContentBlock(
                "block-title", com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.TITLE,
                "标题内容", slideBase.contentBlocks().get(0).sourceType(), "material-title", true);
        var semanticLayout = new com.auvdidao.a12.pptengine.contract.ContractModels.SemanticLayout(
                "BODY", List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.SemanticRegion(
                        "region-001", "BODY", CENTER, 2)), TRANSLATE_ONLY);
        var slide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                slideBase.slideId(), slideBase.pageNumber(), slideBase.title(), slideBase.teachingGoal(),
                List.of(slideBase.contentBlocks().get(0), secondBlock), semanticLayout, List.of(),
                slideBase.provenance(), slideBase.notes());
        var componentA = new com.auvdidao.a12.pptengine.contract.ContractModels.TemplateComponent(
                "component-a", "component-a", "BODY", 1,
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.StableObjectReference(
                        TEXT, "shape-a")), List.of(),
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.ComponentSlot(
                        "slot-a", "TEXT", List.of(BODY,
                        com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.TITLE),
                        new com.auvdidao.a12.pptengine.contract.ContractModels.Bounds(
                                500000, 500000, 5000000, 2500000), true,
                        new com.auvdidao.a12.pptengine.contract.ContractModels.CapacityConstraint(1000, 1))),
                TRANSLATE_ONLY, new com.auvdidao.a12.pptengine.contract.ContractModels.FixedStyle(
                        "style-a", "font", "color", true), false, 1.0, true, "EXECUTION_READY");
        var componentB = new com.auvdidao.a12.pptengine.contract.ContractModels.TemplateComponent(
                "component-b", "component-b", "BODY", 1,
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.StableObjectReference(
                        TEXT, "shape-b")), List.of(),
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.ComponentSlot(
                        "slot-b", "BODY", List.of(BODY),
                        new com.auvdidao.a12.pptengine.contract.ContractModels.Bounds(
                                600000, 500000, 5000000, 2500000), true,
                        new com.auvdidao.a12.pptengine.contract.ContractModels.CapacityConstraint(1000, 1))),
                TRANSLATE_ONLY, new com.auvdidao.a12.pptengine.contract.ContractModels.FixedStyle(
                        "style-b", "font", "color", true), false, 0.9, true, "EXECUTION_READY");
        var profile = new com.auvdidao.a12.pptengine.contract.ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.projectId(), base.ownerUserId(),
                base.templateVersion(), base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.TemplatePageReference(
                        "page-global-assignment", 1, "BODY", List.of("shape-a", "shape-b"))),
                List.of(componentA, componentB), base.preservedNativeObjects(), base.textFitPolicy(),
                "EXECUTION_READY", base.sourceVersionId(), base.sourceSha256(), base.parserSnapshotChecksum());

        JsonNode response = objectMapper.readTree(postCompose(
                CompositionTestSupport.request(objectMapper, "req-global-assignment", List.of(slide), profile), 200));

        assertThat(response.at("/plan/slides/0/layout/templatePage/pageReferenceId").asText())
                .isEqualTo("page-global-assignment");
        assertThat(response.at("/plan/slides/0/layout/componentSelections/0/selectedComponentId").asText())
                .isEqualTo("component-b");
        assertThat(response.at("/plan/slides/0/layout/componentSelections/0/selectedSlotId").asText())
                .isEqualTo("slot-b");
        assertThat(response.at("/plan/slides/0/layout/componentSelections/1/selectedComponentId").asText())
                .isEqualTo("component-a");
        assertThat(response.at("/plan/slides/0/layout/componentSelections/1/selectedSlotId").asText())
                .isEqualTo("slot-a");
    }

    @Test
    void productionComposePathRejectsPageWithoutGlobalAssignmentBeforeBinding() throws Exception {
        var base = ContractFixtures.profile();
        var slideBase = ContractFixtures.slide();
        var secondBlock = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptContentBlock(
                "block-title", com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.TITLE,
                "标题内容", slideBase.contentBlocks().get(0).sourceType(), "material-title", true);
        var semanticLayout = new com.auvdidao.a12.pptengine.contract.ContractModels.SemanticLayout(
                "BODY", List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.SemanticRegion(
                        "region-001", "BODY", CENTER, 2)), TRANSLATE_ONLY);
        var slide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                slideBase.slideId(), slideBase.pageNumber(), slideBase.title(), slideBase.teachingGoal(),
                List.of(slideBase.contentBlocks().get(0), secondBlock), semanticLayout, List.of(),
                slideBase.provenance(), slideBase.notes());
        var onlyComponent = new com.auvdidao.a12.pptengine.contract.ContractModels.TemplateComponent(
                "component-only", "component-only", "BODY", 1,
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.StableObjectReference(
                        TEXT, "shape-only")), List.of(),
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.ComponentSlot(
                        "slot-only", "TEXT", List.of(BODY,
                        com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.TITLE),
                        new com.auvdidao.a12.pptengine.contract.ContractModels.Bounds(
                                500000, 500000, 5000000, 2500000), true,
                        new com.auvdidao.a12.pptengine.contract.ContractModels.CapacityConstraint(1000, 1))),
                TRANSLATE_ONLY, new com.auvdidao.a12.pptengine.contract.ContractModels.FixedStyle(
                        "style-only", "font", "color", true), false, 1.0, true, "EXECUTION_READY");
        var profile = new com.auvdidao.a12.pptengine.contract.ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.projectId(), base.ownerUserId(),
                base.templateVersion(), base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.TemplatePageReference(
                        "page-no-global-assignment", 1, "BODY", List.of("shape-only"))),
                List.of(onlyComponent), base.preservedNativeObjects(), base.textFitPolicy(),
                "EXECUTION_READY", base.sourceVersionId(), base.sourceSha256(), base.parserSnapshotChecksum());

        JsonNode response = objectMapper.readTree(postCompose(
                CompositionTestSupport.request(objectMapper, "req-no-global-assignment", List.of(slide), profile), 422));

        assertThat(response.has("plan")).isFalse();
        assertThat(response.at("/feedback/status").asText()).isEqualTo("FAILED");
        assertThat(response.at("/feedback/diagnostics").toString())
                .contains("TEMPLATE_PAGE_MISSING", "JOB_BLOCKING", "feasiblePageCount");
    }

    private com.auvdidao.a12.pptengine.contract.ContractModels.TemplateComponent componentWithSlots(
            String id, int sourceSlide, String objectId,
            com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint transform,
            int slotCount) {
        List<com.auvdidao.a12.pptengine.contract.ContractModels.ComponentSlot> slots = new ArrayList<>();
        for (int index = 0; index < slotCount; index++) {
            slots.add(new com.auvdidao.a12.pptengine.contract.ContractModels.ComponentSlot(
                    id + "-slot-" + index, "BODY", List.of(BODY),
                    new com.auvdidao.a12.pptengine.contract.ContractModels.Bounds(
                            500000 + index * 100000, 500000, 5000000, 2500000),
                    index == 0, new com.auvdidao.a12.pptengine.contract.ContractModels.CapacityConstraint(
                            1000, 1)));
        }
        return new com.auvdidao.a12.pptengine.contract.ContractModels.TemplateComponent(
                id, id, "BODY", sourceSlide,
                List.of(new com.auvdidao.a12.pptengine.contract.ContractModels.StableObjectReference(
                        TEXT, objectId)), List.of(), slots, transform,
                new com.auvdidao.a12.pptengine.contract.ContractModels.FixedStyle(
                        id + "-style", "font", "color", true), true, 1.0, true,
                "EXECUTION_READY");
    }

    private com.auvdidao.a12.pptengine.contract.ContractModels.TemplateComponent componentWithRefsAndSlots(
            String id, int sourceSlide, List<String> objectIds, int slotCount, boolean reusable) {
        List<com.auvdidao.a12.pptengine.contract.ContractModels.ComponentSlot> slots = new ArrayList<>();
        for (int index = 0; index < slotCount; index++) {
            slots.add(new com.auvdidao.a12.pptengine.contract.ContractModels.ComponentSlot(
                    id + "-slot-" + index, "BODY", List.of(BODY),
                    new com.auvdidao.a12.pptengine.contract.ContractModels.Bounds(
                            500000 + index * 100000, 500000, 5000000, 2500000),
                    index == 0, new com.auvdidao.a12.pptengine.contract.ContractModels.CapacityConstraint(
                            1000, 1)));
        }
        return new com.auvdidao.a12.pptengine.contract.ContractModels.TemplateComponent(
                id, id, "BODY", sourceSlide,
                objectIds.stream().map(objectId ->
                        new com.auvdidao.a12.pptengine.contract.ContractModels.StableObjectReference(TEXT, objectId))
                        .toList(), List.of(), slots, TRANSLATE_ONLY,
                new com.auvdidao.a12.pptengine.contract.ContractModels.FixedStyle(
                        id + "-style", "font", "color", true), reusable, 1.0, true,
                "EXECUTION_READY");
    }

    @Test
    void teacherTextPathsUrlsAndSecretLikeInputNeverAppearInResponse() throws Exception {
        var base = ContractFixtures.slide();
        var block = base.contentBlocks().get(0);
        var sensitiveBlock = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptContentBlock(
                block.blockId(), block.type(), "TEACHER_BODY_SENTINEL_8f27",
                block.sourceType(), "D:\\private\\lesson.docx#page=1", true);
        var asset = base.assetRequirements().get(0);
        var sensitiveAsset = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptAssetReference(
                asset.assetId(), asset.assetType(),
                "https://private.invalid/image.png?api_key=SECRET_SENTINEL_4a91",
                asset.approvalStatus(), asset.required(), asset.placementIntent());
        var slide = new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(),
                List.of(sensitiveBlock), base.semanticLayout(), List.of(sensitiveAsset),
                base.provenance(), base.notes());

        String response = postCompose(CompositionTestSupport.request(
                objectMapper, "req-sensitive", List.of(slide), ContractFixtures.profile()), 200);

        assertThat(response)
                .doesNotContain("TEACHER_BODY_SENTINEL_8f27")
                .doesNotContain("D:\\private")
                .doesNotContain("private.invalid")
                .doesNotContain("SECRET_SENTINEL_4a91")
                .doesNotContain("api_key");
    }

    @Test
    void sameRequestProducesIdenticalPlanBytesAndChecksumTwentyTimes() throws Exception {
        var request = CompositionTestSupport.request(objectMapper);
        List<String> plans = new ArrayList<>();
        List<String> checksums = new ArrayList<>();

        for (int index = 0; index < 20; index++) {
            JsonNode response = objectMapper.readTree(postCompose(request, 200));
            plans.add(objectMapper.writeValueAsString(response.path("plan")));
            checksums.add(response.at("/plan/planChecksum").asText());
        }

        assertThat(plans).allMatch(plans.get(0)::equals);
        assertThat(checksums).containsOnly(checksums.get(0));
    }

    private String postCompose(Object request, int expectedStatus) throws Exception {
        var action = mockMvc.perform(post("/internal/v1/compose-plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)));
        if (expectedStatus == 200) {
            action.andExpect(status().isOk());
        } else if (expectedStatus == 422) {
            action.andExpect(status().isUnprocessableEntity());
        } else {
            throw new IllegalArgumentException("Unsupported expected status");
        }
        return action.andReturn().getResponse().getContentAsString();
    }

    private List<String> operationTypes(JsonNode operations) {
        List<String> result = new ArrayList<>();
        operations.forEach(operation -> result.add(operation.path("operationType").asText()));
        return result;
    }
}
