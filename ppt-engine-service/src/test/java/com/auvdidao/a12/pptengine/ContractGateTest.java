package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractRejectedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.SpecificationStatus.DRAFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ContractGateTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContractGate contractGate;

    @Autowired
    private com.auvdidao.a12.pptengine.service.PreflightService preflightService;

    @Autowired
    private com.auvdidao.a12.pptengine.contract.JsonSchemaCatalog schemaCatalog;

    @Test
    void validRequestPassesSchemaAndSemanticGate() throws Exception {
        ContractModels.EnginePreflightRequest request = ContractFixtures.request(objectMapper);
        ContractModels.EnginePreflightRequest parsed = contractGate.parse(objectMapper.valueToTree(request));

        assertThat(contractGate.validateSemantics(parsed)).isEmpty();
    }

    @Test
    void unknownFieldIsRejectedWithoutEchoingItsValue() {
        JsonNode raw = objectMapper.valueToTree(ContractFixtures.request(objectMapper));
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw).put("unexpectedSecret", "do-not-echo-this");

        assertThatThrownBy(() -> contractGate.parse(raw))
                .isInstanceOf(ContractRejectedException.class)
                .satisfies(error -> {
                    ContractRejectedException rejected = (ContractRejectedException) error;
                    assertThat(rejected.diagnostic().code()).isEqualTo("CONTRACT_INVALID");
                    assertThat(rejected.diagnostic().safeDetails()).doesNotContainValue("do-not-echo-this");
                });
    }

    @Test
    void missingRequiredFieldIsRejectedBySchema() {
        JsonNode raw = objectMapper.valueToTree(ContractFixtures.request(objectMapper));
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.get("specification")).remove("checksum");

        assertThatThrownBy(() -> contractGate.parse(raw))
                .isInstanceOf(ContractRejectedException.class);
    }

    @Test
    void malformedNestedExecuteRequestIsRejectedAsStructuredContract4xxCandidate() {
        JsonNode raw = objectMapper.createObjectNode()
                .put("contractVersion", "1.0.0")
                .put("requestId", "execute-nested-invalid")
                .set("generationJob", objectMapper.createObjectNode());

        assertThatThrownBy(() -> contractGate.parseExecute(raw))
                .isInstanceOf(ContractRejectedException.class)
                .satisfies(error -> {
                    ContractRejectedException rejected = (ContractRejectedException) error;
                    assertThat(rejected.requestId()).isEqualTo("execute-nested-invalid");
                    assertThat(rejected.diagnostic().code()).isEqualTo("CONTRACT_INVALID");
                    assertThat(rejected.diagnostic().safeDetails()).containsKey("violationCount");
                });
    }

    @Test
    void semanticFailuresAre422CandidatesAndDoNotRepairInput() {
        ContractModels.LockedPptSpecification base = ContractFixtures.specification(objectMapper);
        ContractModels.LockedPptSpecification draft = ContractFixtures.withChecksum(objectMapper,
                new ContractModels.LockedPptSpecification(
                        base.contractVersion(), base.specificationId(), base.projectId(), base.version(), DRAFT,
                        base.templateProfileId(), base.templateProfileVersion(), base.targetSlideCount(),
                        base.slideCountTolerance(), base.locale(), base.provider(), base.model(),
                        base.aiSupplementPolicy(), base.lockedBy(), base.lockedAt(), null, base.slides()));
        ContractModels.EnginePreflightRequest request = new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-draft", draft, ContractFixtures.profile());

        String before = objectMapper.valueToTree(request).toString();
        var result = preflightService.execute(request);

        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.feedback().diagnostics()).extracting(ContractModels.Diagnostic::code)
                .contains("SPECIFICATION_NOT_LOCKED");
        assertThat(objectMapper.valueToTree(request).toString()).isEqualTo(before);
    }

    @Test
    void checksumMismatchIsDetectedBeforeResolver() {
        ContractModels.LockedPptSpecification base = ContractFixtures.specification(objectMapper);
        ContractModels.LockedPptSpecification corrupted = new ContractModels.LockedPptSpecification(
                base.contractVersion(), base.specificationId(), base.projectId(), base.version(), base.status(),
                base.templateProfileId(), base.templateProfileVersion(), base.targetSlideCount(),
                base.slideCountTolerance(), base.locale(), base.provider(), base.model(),
                base.aiSupplementPolicy(), base.lockedBy(), base.lockedAt(),
                "0000000000000000000000000000000000000000000000000000000000000000", base.slides());
        var result = preflightService.execute(new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-checksum", corrupted, ContractFixtures.profile()));

        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.feedback().diagnostics()).extracting(ContractModels.Diagnostic::code)
                .contains("CHECKSUM_MISMATCH");
        assertThat(result.plan()).isNull();
    }

    @Test
    void requiredUnapprovedAssetIsRejected() {
        ContractModels.LockedPptSlide baseSlide = ContractFixtures.slide();
        ContractModels.LockedPptSlide invalidSlide = new ContractModels.LockedPptSlide(
                baseSlide.slideId(), baseSlide.pageNumber(), baseSlide.title(), baseSlide.teachingGoal(),
                baseSlide.contentBlocks(), baseSlide.semanticLayout(),
                List.of(new ContractModels.LockedPptAssetReference(
                        "asset-001", com.auvdidao.a12.pptengine.contract.ContractTypes.AssetType.IMAGE,
                        "asset-001", com.auvdidao.a12.pptengine.contract.ContractTypes.ApprovalStatus.PENDING,
                        true, com.auvdidao.a12.pptengine.contract.ContractTypes.PlacementIntent.IMAGE)),
                baseSlide.provenance(), baseSlide.notes());
        ContractModels.LockedPptSpecification base = ContractFixtures.specification(objectMapper);
        ContractModels.LockedPptSpecification invalid = ContractFixtures.withChecksum(objectMapper,
                new ContractModels.LockedPptSpecification(
                        base.contractVersion(), base.specificationId(), base.projectId(), base.version(), base.status(),
                        base.templateProfileId(), base.templateProfileVersion(), base.targetSlideCount(),
                        base.slideCountTolerance(), base.locale(), base.provider(), base.model(),
                        base.aiSupplementPolicy(), base.lockedBy(), base.lockedAt(), null, List.of(invalidSlide)));

        var result = preflightService.execute(new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-asset", invalid, ContractFixtures.profile()));

        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.feedback().diagnostics()).extracting(ContractModels.Diagnostic::code)
                .contains("IMAGE_NOT_APPROVED");
    }

    @Test
    void duplicatePageBlockAndUnlockedContentAreSemanticFailures() {
        ContractModels.LockedPptSlide baseSlide = ContractFixtures.slide();
        ContractModels.LockedPptContentBlock unlocked = new ContractModels.LockedPptContentBlock(
                "block-001", baseSlide.contentBlocks().get(0).type(), baseSlide.contentBlocks().get(0).content(),
                baseSlide.contentBlocks().get(0).sourceType(), baseSlide.contentBlocks().get(0).sourceReference(), false);
        ContractModels.LockedPptSlide invalidSlide = new ContractModels.LockedPptSlide(
                baseSlide.slideId(), 2, baseSlide.title(), baseSlide.teachingGoal(),
                List.of(baseSlide.contentBlocks().get(0), unlocked), baseSlide.semanticLayout(),
                baseSlide.assetRequirements(), baseSlide.provenance(), baseSlide.notes());
        ContractModels.LockedPptSpecification base = ContractFixtures.specification(objectMapper);
        ContractModels.LockedPptSpecification invalid = ContractFixtures.withChecksum(objectMapper,
                new ContractModels.LockedPptSpecification(
                        base.contractVersion(), base.specificationId(), base.projectId(), base.version(), base.status(),
                        base.templateProfileId(), base.templateProfileVersion(), base.targetSlideCount(),
                        base.slideCountTolerance(), base.locale(), base.provider(), base.model(),
                        base.aiSupplementPolicy(), base.lockedBy(), base.lockedAt(), null, List.of(invalidSlide)));

        var result = preflightService.execute(new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-duplicates", invalid, ContractFixtures.profile()));

        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.feedback().diagnostics()).extracting(ContractModels.Diagnostic::code)
                .contains("PAGE_SEQUENCE_INVALID", "DUPLICATE_ID", "CONTRACT_INVALID");
    }

    @Test
    void slideCountAndTemplateProfileMismatchAreRejected() {
        ContractModels.LockedPptSpecification base = ContractFixtures.specification(objectMapper);
        ContractModels.LockedPptSpecification wrongCount = ContractFixtures.withChecksum(objectMapper,
                new ContractModels.LockedPptSpecification(
                        base.contractVersion(), base.specificationId(), base.projectId(), base.version(), base.status(),
                        base.templateProfileId(), base.templateProfileVersion(), 2, 0, base.locale(),
                        base.provider(), base.model(), base.aiSupplementPolicy(), base.lockedBy(), base.lockedAt(),
                        null, base.slides()));
        ContractModels.LockedPptSpecification wrongProfile = ContractFixtures.withChecksum(objectMapper,
                new ContractModels.LockedPptSpecification(
                        base.contractVersion(), base.specificationId(), base.projectId(), base.version(), base.status(),
                        "profile-other", base.templateProfileVersion(), base.targetSlideCount(),
                        base.slideCountTolerance(), base.locale(), base.provider(), base.model(),
                        base.aiSupplementPolicy(), base.lockedBy(), base.lockedAt(), null, base.slides()));

        var countResult = preflightService.execute(new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-count", wrongCount, ContractFixtures.profile()));
        var profileResult = preflightService.execute(new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-profile", wrongProfile, ContractFixtures.profile()));

        assertThat(countResult.feedback().diagnostics()).extracting(ContractModels.Diagnostic::code)
                .contains("SLIDE_COUNT_MISMATCH");
        assertThat(profileResult.feedback().diagnostics()).extracting(ContractModels.Diagnostic::code)
                .contains("TEMPLATE_PROFILE_MISMATCH");
    }

    @Test
    void optionalUnapprovedAssetRemainsUnresolvedAsWarning() {
        ContractModels.LockedPptSlide baseSlide = ContractFixtures.slide();
        ContractModels.LockedPptAssetReference optional = new ContractModels.LockedPptAssetReference(
                "asset-001", baseSlide.assetRequirements().get(0).assetType(), baseSlide.assetRequirements().get(0).source(),
                com.auvdidao.a12.pptengine.contract.ContractTypes.ApprovalStatus.PENDING, false,
                baseSlide.assetRequirements().get(0).placementIntent());
        ContractModels.LockedPptSlide slide = new ContractModels.LockedPptSlide(
                baseSlide.slideId(), baseSlide.pageNumber(), baseSlide.title(), baseSlide.teachingGoal(),
                baseSlide.contentBlocks(), baseSlide.semanticLayout(), List.of(optional),
                baseSlide.provenance(), baseSlide.notes());
        ContractModels.LockedPptSpecification base = ContractFixtures.specification(objectMapper);
        ContractModels.LockedPptSpecification specification = ContractFixtures.withChecksum(objectMapper,
                new ContractModels.LockedPptSpecification(
                        base.contractVersion(), base.specificationId(), base.projectId(), base.version(), base.status(),
                        base.templateProfileId(), base.templateProfileVersion(), base.targetSlideCount(),
                        base.slideCountTolerance(), base.locale(), base.provider(), base.model(),
                        base.aiSupplementPolicy(), base.lockedBy(), base.lockedAt(), null, List.of(slide)));

        var result = preflightService.execute(new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-optional-asset", specification, ContractFixtures.profile()));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.feedback().outcome()).isEqualTo(com.auvdidao.a12.pptengine.contract.ContractTypes.FeedbackOutcome.PARTIAL);
        assertThat(result.plan().slides().get(0).unresolvedAssetIds()).containsExactly("asset-001");
        assertThat(result.plan().slides().get(0).assetToSlotBindings()).isEmpty();
    }

    @Test
    void unresolvedComponentIs422AndComponentCycleIsRejected() {
        ContractModels.ConfirmedTemplateProfile baseProfile = ContractFixtures.profile();
        List<ContractModels.TemplateComponent> unconfirmed = baseProfile.components().stream()
                .map(component -> new ContractModels.TemplateComponent(
                        component.componentId(), component.name(), component.semanticRole(), component.sourceSlide(),
                        component.shapeRefs(), component.childComponentIds(), component.slots(), component.transformConstraint(),
                        component.fixedStyle(), component.reusable(), component.confidence(), false))
                .toList();
        ContractModels.ConfirmedTemplateProfile noCandidate = profileWithComponents(baseProfile, unconfirmed);
        var unresolved = preflightService.execute(new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-no-component", ContractFixtures.specification(objectMapper), noCandidate));

        ContractModels.TemplateComponent body = baseProfile.components().get(0);
        ContractModels.TemplateComponent image = baseProfile.components().get(1);
        List<ContractModels.TemplateComponent> cycle = new ArrayList<>();
        cycle.add(copyWithChildren(body, List.of(image.componentId())));
        cycle.add(copyWithChildren(image, List.of(body.componentId())));
        cycle.add(baseProfile.components().get(2));
        var cyclic = preflightService.execute(new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-cycle", ContractFixtures.specification(objectMapper),
                profileWithComponents(baseProfile, cycle)));

        assertThat(unresolved.httpStatus()).isEqualTo(422);
        assertThat(unresolved.feedback().diagnostics()).extracting(ContractModels.Diagnostic::code)
                .contains("COMPONENT_MISSING");
        assertThat(cyclic.httpStatus()).isEqualTo(422);
        assertThat(cyclic.feedback().diagnostics()).extracting(ContractModels.Diagnostic::messageKey)
                .contains("reference.componentCycle");
    }

    @Test
    void candidateProfileIsRejectedBeforeResolver() {
        ContractModels.ConfirmedTemplateProfile base = ContractFixtures.profile();
        ContractModels.ConfirmedTemplateProfile candidate = new ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.templateVersion(),
                base.profileVersion(), com.auvdidao.a12.pptengine.contract.ContractTypes.TemplateProfileStatus.CANDIDATE,
                base.pageSize(), base.spatialProfile(), base.templatePageReferences(), base.components());

        var result = preflightService.execute(new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-candidate", ContractFixtures.specification(objectMapper), candidate));

        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.feedback().diagnostics()).extracting(ContractModels.Diagnostic::code)
                .contains("TEMPLATE_PROFILE_NOT_CONFIRMED");
    }

    @Test
    void duplicateComponentAndSlotIdsAreRejected() {
        ContractModels.ConfirmedTemplateProfile base = ContractFixtures.profile();
        List<ContractModels.TemplateComponent> duplicates = List.of(
                base.components().get(0),
                new ContractModels.TemplateComponent(
                        base.components().get(0).componentId(), "重复组件", "BODY", 1,
                        base.components().get(0).shapeRefs(), List.of(),
                        List.of(new ContractModels.ComponentSlot(
                                base.components().get(0).slots().get(0).slotId(), "BODY",
                                List.of(com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY),
                                base.components().get(0).slots().get(0).bounds(), false,
                                base.components().get(0).slots().get(0).capacityConstraint())),
                        base.components().get(0).transformConstraint(), base.components().get(0).fixedStyle(),
                        true, 0.5, true),
                base.components().get(1));
        var result = preflightService.execute(new ContractModels.EnginePreflightRequest(
                requestContractVersion(), "req-duplicate-components", ContractFixtures.specification(objectMapper),
                profileWithComponents(base, duplicates)));

        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.feedback().diagnostics()).extracting(ContractModels.Diagnostic::code)
                .contains("DUPLICATE_ID");
    }

    @Test
    void schemaRejectsIllegalCoordinate() {
        JsonNode raw = objectMapper.valueToTree(ContractFixtures.request(objectMapper));
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.at("/templateProfile/components/0/slots/0/bounds"))
                .put("widthEmu", 0);
        assertThat(schemaCatalog.violationCount(
                "confirmed-template-profile.schema.json", raw.get("templateProfile"))).isGreaterThan(0);
    }

    @Test
    void schemaRejectsTextAndComponentResourceOverflow() {
        JsonNode raw = objectMapper.valueToTree(ContractFixtures.request(objectMapper));
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.at("/specification/slides/0/contentBlocks/0"))
                .put("content", "x".repeat(4001));
        assertThat(schemaCatalog.violationCount(
                "locked-ppt-specification.schema.json", raw.get("specification"))).isGreaterThan(0);

        var components = (com.fasterxml.jackson.databind.node.ArrayNode) raw.at("/templateProfile/components");
        JsonNode component = components.get(0);
        for (int index = components.size(); index < 501; index++) {
            components.add(component);
        }
        assertThat(schemaCatalog.violationCount(
                "confirmed-template-profile.schema.json", raw.get("templateProfile"))).isGreaterThan(0);
    }

    private ContractModels.ConfirmedTemplateProfile profileWithComponents(
            ContractModels.ConfirmedTemplateProfile base,
            List<ContractModels.TemplateComponent> components) {
        return new ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.templateVersion(),
                base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                base.templatePageReferences(), components);
    }

    private ContractModels.TemplateComponent copyWithChildren(
            ContractModels.TemplateComponent component,
            List<String> children) {
        return new ContractModels.TemplateComponent(
                component.componentId(), component.name(), component.semanticRole(), component.sourceSlide(),
                component.shapeRefs(), children, component.slots(), component.transformConstraint(),
                component.fixedStyle(), component.reusable(), component.confidence(), component.teacherConfirmed());
    }

    private String requestContractVersion() {
        return com.auvdidao.a12.pptengine.contract.ContractTypes.V1;
    }
}
