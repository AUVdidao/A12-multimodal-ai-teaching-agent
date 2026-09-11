package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ApprovalStatus.APPROVED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentSourceType.MATERIAL;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ObjectType.GROUP;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ObjectType.PICTURE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ObjectType.TEXT;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.PlacementIntent.IMAGE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.PreferredPosition.CENTER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.SpecificationStatus.LOCKED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TemplateProfileStatus.CONFIRMED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.TRANSLATE_ONLY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.V1;

public final class ContractFixtures {

    private ContractFixtures() {
    }

    public static ContractModels.EnginePreflightRequest request(ObjectMapper mapper) {
        return new ContractModels.EnginePreflightRequest(V1, "req-001", specification(mapper), profile());
    }

    public static ContractModels.LockedPptSpecification specification(ObjectMapper mapper) {
        ContractModels.LockedPptSpecification specification = new ContractModels.LockedPptSpecification(
                V1, "spec-001", "project-001", 1, LOCKED, "profile-001", 1,
                1, 0, "zh-CN", "mock", "offline", "DISABLED", "teacher-001",
                OffsetDateTime.parse("2026-08-24T00:00:00Z"), null, List.of(slide()));
        return withChecksum(mapper, specification);
    }

    public static ContractModels.LockedPptSpecification withChecksum(
            ObjectMapper mapper,
            ContractModels.LockedPptSpecification specification) {
        ChecksumService checksumService = new ChecksumService(mapper);
        return new ContractModels.LockedPptSpecification(
                specification.contractVersion(), specification.specificationId(), specification.projectId(),
                specification.version(), specification.status(), specification.templateProfileId(),
                specification.templateProfileVersion(), specification.targetSlideCount(),
                specification.slideCountTolerance(), specification.locale(), specification.provider(),
                specification.model(), specification.aiSupplementPolicy(), specification.lockedBy(),
                specification.lockedAt(), checksumService.compute(specification), specification.slides());
    }

    public static ContractModels.LockedPptSlide slide() {
        return new ContractModels.LockedPptSlide(
                "slide-001", 1, "光合作用", "说明光合作用的基本过程",
                List.of(new ContractModels.LockedPptContentBlock(
                        "block-001", BODY, "植物利用光能将二氧化碳和水转化为有机物。",
                        MATERIAL, "material-001", true)),
                new ContractModels.SemanticLayout(
                        "BODY",
                        List.of(new ContractModels.SemanticRegion("region-001", "BODY", CENTER, 1)),
                        TRANSLATE_ONLY),
                List.of(new ContractModels.LockedPptAssetReference(
                        "asset-001", com.auvdidao.a12.pptengine.contract.ContractTypes.AssetType.IMAGE,
                        "asset-001", APPROVED, true, IMAGE)),
                List.of(new ContractModels.ProvenanceEntry(MATERIAL, "material-001")),
                "");
    }

    public static ContractModels.ConfirmedTemplateProfile profile() {
        return new ContractModels.ConfirmedTemplateProfile(
                V1, "profile-001", "template-001", 1, 1, CONFIRMED,
                new ContractModels.PageSize(12192000, 6858000),
                new ContractModels.SpatialProfile(300000, 300000, 300000, 300000),
                List.of(new ContractModels.TemplatePageReference(
                        "page-ref-001", 1, "BODY", List.of("shape-body", "shape-image"))),
                List.of(
                        component("component-body", "BODY", "shape-body", new ContractModels.ComponentSlot(
                                "slot-body", "BODY", List.of(BODY),
                                new ContractModels.Bounds(500000, 500000, 5000000, 2500000),
                                true, new ContractModels.CapacityConstraint(1000, 1))),
                        component("component-image", "BODY", "shape-image", new ContractModels.ComponentSlot(
                                "slot-image", "IMAGE", List.of(com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.IMAGE),
                                new ContractModels.Bounds(6000000, 500000, 4000000, 4000000),
                                true, new ContractModels.CapacityConstraint(null, 1))),
                        new ContractModels.TemplateComponent(
                                "component-unconfirmed", "未确认组件", "BODY", 1,
                List.of(new ContractModels.StableObjectReference(GROUP, "shape-body")),
                                List.of(), List.of(new ContractModels.ComponentSlot(
                                        "slot-unconfirmed", "BODY", List.of(BODY),
                                        new ContractModels.Bounds(1, 1, 100, 100), false,
                                        new ContractModels.CapacityConstraint(1000, 1))),
                                TRANSLATE_ONLY,
                                new ContractModels.FixedStyle("style-001", "font-body", "color-body", true),
                                true, 1.0, false)));
    }

    private static ContractModels.TemplateComponent component(
            String componentId,
            String semanticRole,
            String shapeId,
            ContractModels.ComponentSlot slot) {
        return new ContractModels.TemplateComponent(
                componentId, componentId, semanticRole, 1,
                List.of(new ContractModels.StableObjectReference(
                        shapeId.contains("image") ? PICTURE : TEXT, shapeId)),
                List.of(), List.of(slot), TRANSLATE_ONLY,
                new ContractModels.FixedStyle("style-001", "font-body", "color-body", true),
                true, 0.9, true);
    }
}
