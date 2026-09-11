package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CompositionTestSupport {

    private CompositionTestSupport() {
    }

    static CompositionModels.EngineComposePlanRequest request(
            ObjectMapper mapper,
            String requestId,
            List<ContractModels.LockedPptSlide> slides,
            ContractModels.ConfirmedTemplateProfile profile) {
        ContractModels.LockedPptSpecification base = ContractFixtures.specification(mapper);
        ContractModels.LockedPptSpecification specification = ContractFixtures.withChecksum(
                mapper,
                new ContractModels.LockedPptSpecification(
                        base.contractVersion(), base.specificationId(), base.projectId(), base.version(),
                        base.status(), profile.profileId(), profile.profileVersion(), slides.size(), 0,
                        base.locale(), base.provider(), base.model(), base.aiSupplementPolicy(),
                        base.lockedBy(), base.lockedAt(), null, slides));
        return request(mapper, requestId, specification, profile);
    }

    static CompositionModels.EngineComposePlanRequest request(ObjectMapper mapper) {
        ContractModels.EnginePreflightRequest base = ContractFixtures.request(mapper);
        return request(mapper, base.requestId(), base.specification(), base.templateProfile());
    }

    static CompositionModels.EngineComposePlanRequest request(
            ObjectMapper mapper,
            String requestId,
            ContractModels.LockedPptSpecification specification,
            ContractModels.ConfirmedTemplateProfile profile) {
        profile = executionReadyProfile(profile);
        ChecksumService checksums = new ChecksumService(mapper);
        List<CompositionModels.ApprovedAssetManifestEntry> entries = new ArrayList<>();
        for (ContractModels.LockedPptSlide slide : specification.slides()) {
            for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
                entries.add(new CompositionModels.ApprovedAssetManifestEntry(
                        asset.assetId(), ContractTypes.AssetResolution.APPROVED_ASSET,
                        "approved-" + asset.assetId(), asset.assetType(),
                        checksums.sha256Parts("approved-asset", asset.assetId())));
            }
        }
        CompositionModels.ApprovedAssetManifest withoutChecksum =
                new CompositionModels.ApprovedAssetManifest(
                        ContractTypes.APPROVED_ASSET_MANIFEST_V1,
                        "manifest-001", specification.projectId(), profile.ownerUserId(),
                        1, ContractTypes.AssetManifestStatus.APPROVED,
                        specification.specificationId(), specification.version(), specification.checksum(),
                        null, entries);
                CompositionModels.ApprovedAssetManifest manifest =
                new CompositionModels.ApprovedAssetManifest(
                        withoutChecksum.contractVersion(), withoutChecksum.manifestId(),
                        specification.projectId(), profile.ownerUserId(),
                        withoutChecksum.manifestVersion(), withoutChecksum.status(),
                        withoutChecksum.specificationId(), withoutChecksum.specificationVersion(),
                        withoutChecksum.specificationChecksum(), checksums.computeManifest(withoutChecksum),
                        withoutChecksum.entries());
        CompositionModels.GenerationJob jobWithoutChecksum = new CompositionModels.GenerationJob(
                "job-001", "attempt-001", specification.projectId(), profile.ownerUserId(), null,
                new CompositionModels.ExecutionInputBinding(
                        specification.specificationId(), specification.version(), specification.checksum()),
                new CompositionModels.ExecutionInputBinding(
                        profile.profileId(), profile.profileVersion(), checksums.computeProfile(profile)),
                new CompositionModels.ExecutionInputBinding(
                        manifest.manifestId(), manifest.manifestVersion(), manifest.manifestChecksum()),
                ContractTypes.COMPOSE_CONTRACT_V2, ContractTypes.COMPOSITION_PLAN_V2,
                "engine-build-test", "executor-adapter-none", "font-env-test",
                "teacher-test", OffsetDateTime.parse("2026-08-24T00:00:00Z"),
                "idempotency-" + requestId);
        CompositionModels.GenerationJob job = new CompositionModels.GenerationJob(
                jobWithoutChecksum.generationJobId(), jobWithoutChecksum.executionAttemptId(),
                jobWithoutChecksum.projectId(), jobWithoutChecksum.ownerUserId(),
                checksums.computeGenerationJobBinding(jobWithoutChecksum),
                jobWithoutChecksum.specificationBinding(), jobWithoutChecksum.templateProfileBinding(),
                jobWithoutChecksum.approvedAssetManifestBinding(),
                jobWithoutChecksum.composeContractVersion(), jobWithoutChecksum.planContractVersion(),
                jobWithoutChecksum.engineBuildVersion(), jobWithoutChecksum.executorAdapterVersion(),
                jobWithoutChecksum.fontEnvironmentVersion(), jobWithoutChecksum.requestedBy(),
                jobWithoutChecksum.requestedAt(), jobWithoutChecksum.idempotencyKey());
        return new CompositionModels.EngineComposePlanRequest(
                ContractTypes.COMPOSE_CONTRACT_V2, requestId, job,
                specification, profile, manifest);
    }

    static CompositionModels.ValidatedExecutionPackage validatedPackage(
            CompositionModels.EngineComposePlanRequest request) {
        Map<String, CompositionModels.ApprovedAssetManifestEntry> entries = new LinkedHashMap<>();
        request.approvedAssetManifest().entries().forEach(entry ->
                entries.put(entry.assetRequirementId(), entry));
        return new CompositionModels.ValidatedExecutionPackage(
                request.contractVersion(), request.requestId(), request.generationJob(),
                request.specification(), request.templateProfile(), request.approvedAssetManifest(), entries);
    }

    static CompositionModels.EngineComposePlanRequest withManifestEntries(
            ObjectMapper mapper,
            CompositionModels.EngineComposePlanRequest request,
            String generationJobId,
            int manifestVersion,
            List<CompositionModels.ApprovedAssetManifestEntry> entries) {
        ChecksumService checksums = new ChecksumService(mapper);
        CompositionModels.ApprovedAssetManifest base = request.approvedAssetManifest();
        CompositionModels.ApprovedAssetManifest withoutChecksum =
                new CompositionModels.ApprovedAssetManifest(
                        base.contractVersion(), base.manifestId(), base.projectId(), base.ownerUserId(),
                        manifestVersion, base.status(),
                        base.specificationId(), base.specificationVersion(), base.specificationChecksum(),
                        null, entries);
        CompositionModels.ApprovedAssetManifest manifest =
                new CompositionModels.ApprovedAssetManifest(
                        withoutChecksum.contractVersion(), withoutChecksum.manifestId(),
                        withoutChecksum.projectId(), withoutChecksum.ownerUserId(),
                        withoutChecksum.manifestVersion(), withoutChecksum.status(),
                        withoutChecksum.specificationId(), withoutChecksum.specificationVersion(),
                        withoutChecksum.specificationChecksum(), checksums.computeManifest(withoutChecksum),
                        withoutChecksum.entries());
        CompositionModels.GenerationJob baseJob = request.generationJob();
        CompositionModels.GenerationJob withoutJobChecksum = new CompositionModels.GenerationJob(
                generationJobId, baseJob.executionAttemptId(), baseJob.projectId(), baseJob.ownerUserId(), null,
                baseJob.specificationBinding(), baseJob.templateProfileBinding(),
                new CompositionModels.ExecutionInputBinding(
                        manifest.manifestId(), manifest.manifestVersion(), manifest.manifestChecksum()),
                baseJob.composeContractVersion(), baseJob.planContractVersion(), baseJob.engineBuildVersion(),
                baseJob.executorAdapterVersion(), baseJob.fontEnvironmentVersion(), baseJob.requestedBy(),
                baseJob.requestedAt(), "idempotency-" + generationJobId);
        CompositionModels.GenerationJob job = new CompositionModels.GenerationJob(
                withoutJobChecksum.generationJobId(), withoutJobChecksum.executionAttemptId(),
                withoutJobChecksum.projectId(), withoutJobChecksum.ownerUserId(),
                checksums.computeGenerationJobBinding(withoutJobChecksum),
                withoutJobChecksum.specificationBinding(), withoutJobChecksum.templateProfileBinding(),
                withoutJobChecksum.approvedAssetManifestBinding(),
                withoutJobChecksum.composeContractVersion(), withoutJobChecksum.planContractVersion(),
                withoutJobChecksum.engineBuildVersion(), withoutJobChecksum.executorAdapterVersion(),
                withoutJobChecksum.fontEnvironmentVersion(), withoutJobChecksum.requestedBy(),
                withoutJobChecksum.requestedAt(), withoutJobChecksum.idempotencyKey());
        return new CompositionModels.EngineComposePlanRequest(
                request.contractVersion(), request.requestId(), job,
                request.specification(), request.templateProfile(), manifest);
    }

    static CompositionModels.EngineComposePlanRequest withGenerationJob(
            ObjectMapper mapper,
            CompositionModels.EngineComposePlanRequest request,
            String generationJobId,
            String executionAttemptId,
            String engineBuildVersion,
            String executorAdapterVersion,
            String fontEnvironmentVersion) {
        ChecksumService checksums = new ChecksumService(mapper);
        CompositionModels.GenerationJob base = request.generationJob();
        CompositionModels.GenerationJob withoutChecksum = new CompositionModels.GenerationJob(
                generationJobId, executionAttemptId, base.projectId(), base.ownerUserId(), null,
                base.specificationBinding(), base.templateProfileBinding(),
                base.approvedAssetManifestBinding(), base.composeContractVersion(),
                base.planContractVersion(), engineBuildVersion, executorAdapterVersion,
                fontEnvironmentVersion, base.requestedBy(), base.requestedAt(),
                "idempotency-" + generationJobId);
        CompositionModels.GenerationJob job = new CompositionModels.GenerationJob(
                withoutChecksum.generationJobId(), withoutChecksum.executionAttemptId(),
                withoutChecksum.projectId(), withoutChecksum.ownerUserId(),
                checksums.computeGenerationJobBinding(withoutChecksum),
                withoutChecksum.specificationBinding(), withoutChecksum.templateProfileBinding(),
                withoutChecksum.approvedAssetManifestBinding(),
                withoutChecksum.composeContractVersion(), withoutChecksum.planContractVersion(),
                withoutChecksum.engineBuildVersion(), withoutChecksum.executorAdapterVersion(),
                withoutChecksum.fontEnvironmentVersion(), withoutChecksum.requestedBy(),
                withoutChecksum.requestedAt(), withoutChecksum.idempotencyKey());
        return new CompositionModels.EngineComposePlanRequest(
                request.contractVersion(), request.requestId(), job,
                request.specification(), request.templateProfile(), request.approvedAssetManifest());
    }

    private static ContractModels.ConfirmedTemplateProfile executionReadyProfile(
            ContractModels.ConfirmedTemplateProfile profile) {
        List<ContractModels.TemplatePageReference> pages = profile.templatePageReferences().stream()
                .map(page -> new ContractModels.TemplatePageReference(
                        page.pageReferenceId(), page.sourceSlide(), page.semanticRole(), page.objectIds(),
                        "COVER".equals(page.semanticRole())
                                ? "ANALYZER_PROFILE_COVER_FIRST_PAGE"
                                : "BODY".equals(page.semanticRole()) || "CONTENT".equals(page.semanticRole())
                                ? "ANALYZER_PROFILE_CONTENT_REMAINDER"
                                : "PROFILE_ROLE_UNAVAILABLE_FAIL_CLOSED"))
                .toList();
        List<ContractModels.TemplateComponent> components = profile.components().stream()
                .map(component -> new ContractModels.TemplateComponent(
                        component.componentId(), component.name(), component.semanticRole(), component.sourceSlide(),
                        component.shapeRefs(), component.childComponentIds(), component.slots(),
                        component.transformConstraint(), component.fixedStyle(), component.reusable(),
                        component.confidence(), component.teacherConfirmed(),
                        component.executionEligibility() != null
                                ? component.executionEligibility()
                                : component.reusable() && component.teacherConfirmed()
                                ? "EXECUTION_READY" : "NOT_EXECUTION_READY"))
                .toList();
        return new ContractModels.ConfirmedTemplateProfile(
                profile.contractVersion(), profile.profileId(), profile.templateId(), profile.projectId(),
                profile.ownerUserId(), profile.templateVersion(), profile.profileVersion(), profile.status(),
                profile.pageSize(), profile.spatialProfile(), pages, components, profile.preservedNativeObjects(),
                profile.textFitPolicy(), "EXECUTION_READY", profile.sourceVersionId(), profile.sourceSha256(),
                profile.parserSnapshotChecksum());
    }

    static ContractModels.ConfirmedTemplateProfile withPages(
            ContractModels.ConfirmedTemplateProfile profile,
            List<ContractModels.TemplatePageReference> pages) {
        return new ContractModels.ConfirmedTemplateProfile(
                profile.contractVersion(), profile.profileId(), profile.templateId(),
                profile.templateVersion(), profile.profileVersion(), profile.status(),
                profile.pageSize(), profile.spatialProfile(), pages, profile.components());
    }

    static ContractModels.ConfirmedTemplateProfile withSlotBounds(
            ContractModels.ConfirmedTemplateProfile profile,
            String slotId,
            ContractModels.Bounds bounds) {
        List<ContractModels.TemplateComponent> components = new ArrayList<>();
        for (ContractModels.TemplateComponent component : profile.components()) {
            List<ContractModels.ComponentSlot> slots = new ArrayList<>();
            for (ContractModels.ComponentSlot slot : component.slots()) {
                slots.add(slot.slotId().equals(slotId)
                        ? new ContractModels.ComponentSlot(
                        slot.slotId(), slot.semanticRole(), slot.acceptedContentTypes(), bounds,
                        slot.required(), slot.capacityConstraint())
                        : slot);
            }
            components.add(copyComponent(component, component.sourceSlide(), component.shapeRefs(), slots));
        }
        return withComponents(profile, components);
    }

    static ContractModels.ConfirmedTemplateProfile withSlotMaxItems(
            ContractModels.ConfirmedTemplateProfile profile,
            String slotId,
            int maxItems) {
        List<ContractModels.TemplateComponent> components = new ArrayList<>();
        for (ContractModels.TemplateComponent component : profile.components()) {
            List<ContractModels.ComponentSlot> slots = new ArrayList<>();
            for (ContractModels.ComponentSlot slot : component.slots()) {
                ContractModels.CapacityConstraint capacity = slot.capacityConstraint();
                slots.add(slot.slotId().equals(slotId)
                        ? new ContractModels.ComponentSlot(
                        slot.slotId(), slot.semanticRole(), slot.acceptedContentTypes(), slot.bounds(),
                        slot.required(), new ContractModels.CapacityConstraint(
                        capacity == null ? null : capacity.maxCharacters(), maxItems))
                        : slot);
            }
            components.add(copyComponent(component, component.sourceSlide(), component.shapeRefs(), slots));
        }
        return withComponents(profile, components);
    }

    static ContractModels.ConfirmedTemplateProfile withComponents(
            ContractModels.ConfirmedTemplateProfile profile,
            List<ContractModels.TemplateComponent> components) {
        return new ContractModels.ConfirmedTemplateProfile(
                profile.contractVersion(), profile.profileId(), profile.templateId(),
                profile.templateVersion(), profile.profileVersion(), profile.status(),
                profile.pageSize(), profile.spatialProfile(), profile.templatePageReferences(), components);
    }

    static ContractModels.TemplateComponent copyComponent(
            ContractModels.TemplateComponent component,
            int sourceSlide,
            List<ContractModels.StableObjectReference> shapeRefs,
            List<ContractModels.ComponentSlot> slots) {
        return new ContractModels.TemplateComponent(
                component.componentId(), component.name(), component.semanticRole(), sourceSlide,
                shapeRefs, component.childComponentIds(), slots, component.transformConstraint(),
                component.fixedStyle(), component.reusable(), component.confidence(),
                component.teacherConfirmed(), component.executionEligibility());
    }
}
