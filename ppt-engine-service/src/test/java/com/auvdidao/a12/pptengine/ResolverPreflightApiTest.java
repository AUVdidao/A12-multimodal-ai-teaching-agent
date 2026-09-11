package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ApprovalStatus.APPROVED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetType.VIDEO;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentSourceType.MATERIAL;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.IMAGE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.TEXT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ResolverPreflightApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void slotOverbookingReturns422InsteadOfSuccess() throws Exception {
        ContractModels.LockedPptSlide base = ContractFixtures.slide();
        ContractModels.LockedPptSlide slide = new ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(),
                List.of(
                        base.contentBlocks().get(0),
                        new ContractModels.LockedPptContentBlock(
                                "block-002", BODY, "第二个正文块", MATERIAL, "material-002", true)),
                base.semanticLayout(), base.assetRequirements(), base.provenance(), base.notes());

        postAndExpectResolverError(requestWith(slide, ContractFixtures.profile()), "SLOT_CAPACITY_EXCEEDED");
    }

    @Test
    void videoCannotUseTextSlotAndReturns422() throws Exception {
        ContractModels.LockedPptSlide base = ContractFixtures.slide();
        ContractModels.LockedPptSlide slide = new ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(), List.of(),
                base.semanticLayout(),
                List.of(new ContractModels.LockedPptAssetReference(
                        "asset-video", VIDEO, "approved-video", APPROVED, true,
                        ContractTypes.PlacementIntent.BODY)),
                base.provenance(), base.notes());
        ContractModels.TemplateComponent textComponent = copyComponent(
                ContractFixtures.profile().components().get(0),
                List.of(new ContractModels.ComponentSlot(
                        "slot-text", "BODY", List.of(TEXT),
                        new ContractModels.Bounds(1, 1, 100, 100), false,
                        new ContractModels.CapacityConstraint(100, 1))));
        ContractModels.ConfirmedTemplateProfile profile = profileWithComponents(List.of(textComponent));

        postAndExpectResolverError(requestWith(slide, profile), "ASSET_TYPE_UNSUPPORTED");
    }

    @Test
    void unfilledRequiredSlotReturns422WithComponentAndSlotIds() throws Exception {
        ContractModels.LockedPptSlide base = ContractFixtures.slide();
        ContractModels.LockedPptSlide slide = new ContractModels.LockedPptSlide(
                base.slideId(), base.pageNumber(), base.title(), base.teachingGoal(),
                base.contentBlocks(), base.semanticLayout(), List.of(), base.provenance(), base.notes());
        ContractModels.TemplateComponent original = ContractFixtures.profile().components().get(0);
        List<ContractModels.ComponentSlot> slots = new ArrayList<>(original.slots());
        slots.add(new ContractModels.ComponentSlot(
                "slot-required-image", "IMAGE", List.of(IMAGE),
                new ContractModels.Bounds(1, 1, 100, 100), true,
                new ContractModels.CapacityConstraint(null, 1)));
        ContractModels.ConfirmedTemplateProfile profile = profileWithComponents(
                List.of(copyComponent(original, slots)));

        ContractModels.EnginePreflightRequest request = requestWith(slide, profile);
        mockMvc.perform(post("/internal/v1/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("REQUIRED_SLOT_UNFILLED"))
                .andExpect(jsonPath("$.diagnostics[0].componentId").value(original.componentId()))
                .andExpect(jsonPath("$.diagnostics[0].slotId").value("slot-required-image"));
    }

    private void postAndExpectResolverError(
            ContractModels.EnginePreflightRequest request,
            String code) throws Exception {
        mockMvc.perform(post("/internal/v1/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.diagnostics[0].code").value(code));
    }

    private ContractModels.EnginePreflightRequest requestWith(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile) {
        ContractModels.LockedPptSpecification base = ContractFixtures.specification(objectMapper);
        ContractModels.LockedPptSpecification specification = ContractFixtures.withChecksum(
                objectMapper,
                new ContractModels.LockedPptSpecification(
                        base.contractVersion(), base.specificationId(), base.projectId(), base.version(), base.status(),
                        base.templateProfileId(), base.templateProfileVersion(), base.targetSlideCount(),
                        base.slideCountTolerance(), base.locale(), base.provider(), base.model(),
                        base.aiSupplementPolicy(), base.lockedBy(), base.lockedAt(), null, List.of(slide)));
        return new ContractModels.EnginePreflightRequest(
                ContractTypes.V1, "req-resolver-api", specification, profile);
    }

    private ContractModels.TemplateComponent copyComponent(
            ContractModels.TemplateComponent component,
            List<ContractModels.ComponentSlot> slots) {
        return new ContractModels.TemplateComponent(
                component.componentId(), component.name(), component.semanticRole(), component.sourceSlide(),
                component.shapeRefs(), component.childComponentIds(), slots, component.transformConstraint(),
                component.fixedStyle(), component.reusable(), component.confidence(), component.teacherConfirmed());
    }

    private ContractModels.ConfirmedTemplateProfile profileWithComponents(
            List<ContractModels.TemplateComponent> components) {
        ContractModels.ConfirmedTemplateProfile base = ContractFixtures.profile();
        return new ContractModels.ConfirmedTemplateProfile(
                base.contractVersion(), base.profileId(), base.templateId(), base.templateVersion(),
                base.profileVersion(), base.status(), base.pageSize(), base.spatialProfile(),
                base.templatePageReferences(), components);
    }
}
