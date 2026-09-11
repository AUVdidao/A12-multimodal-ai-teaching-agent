package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ApprovedAssetManifestGate;
import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractRejectedException;
import com.auvdidao.a12.pptengine.contract.JsonSchemaCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ApprovalStatus.PENDING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TemplateProfileStatus.READY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposeContractGateTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ChecksumService checksums = new ChecksumService(objectMapper);
    private final ContractGate gate = new ContractGate(
            objectMapper, new JsonSchemaCatalog(objectMapper), checksums,
            new ApprovedAssetManifestGate(checksums));

    @Test
    void validThreeInputJobParsesAndProducesValidatedExecutionPackage() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);

        CompositionModels.EngineComposePlanRequest parsed = gate.parseCompose(
                objectMapper.valueToTree(request));
        ContractGate.ComposeValidationResult result = gate.validateCompose(parsed);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.executionPackage()).isNotNull();
        assertThat(result.executionPackage().generationJob().generationJobId()).isEqualTo("job-001");
        assertThat(result.executionPackage().manifestEntriesByRequirementId())
                .containsOnlyKeys("asset-001");
    }

    @Test
    void readyProfileRequiresExplicitExecutionReadyProjection() {
        ObjectNode raw = objectMapper.valueToTree(CompositionTestSupport.request(objectMapper));
        ObjectNode profile = (ObjectNode) raw.get("templateProfile");
        profile.put("status", "READY");
        profile.put("executionStatus", "EXECUTION_READY");
        profile.put("sourceVersionId", 1);
        profile.put("sourceSha256", "a".repeat(64));
        profile.put("parserSnapshotChecksum", "b".repeat(64));

        CompositionModels.EngineComposePlanRequest parsed = gate.parseCompose(raw);

        assertThat(parsed.templateProfile().status()).isEqualTo(READY);
    }

    @Test
    void missingGenerationJobOrUnknownFieldsAreSchema400Failures() {
        ObjectNode missing = objectMapper.valueToTree(CompositionTestSupport.request(objectMapper));
        missing.remove("generationJob");
        assertContractInvalid(missing);

        ObjectNode unknown = objectMapper.valueToTree(CompositionTestSupport.request(objectMapper));
        ((ObjectNode) unknown.get("generationJob")).put("unknownField", "sensitive-marker");
        assertContractInvalid(unknown);
    }

    @Test
    void eachGenerationJobSnapshotBindingMismatchHasItsOwn422Diagnostic() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.GenerationJob job = request.generationJob();

        assertBindingCode(request, copyJob(job,
                        changed(job.specificationBinding()), job.templateProfileBinding(),
                        job.approvedAssetManifestBinding()),
                "GENERATION_JOB_SPECIFICATION_BINDING_MISMATCH");
        assertBindingCode(request, copyJob(job,
                        job.specificationBinding(), changed(job.templateProfileBinding()),
                        job.approvedAssetManifestBinding()),
                "GENERATION_JOB_TEMPLATE_PROFILE_BINDING_MISMATCH");
        assertBindingCode(request, copyJob(job,
                        job.specificationBinding(), job.templateProfileBinding(),
                        changed(job.approvedAssetManifestBinding())),
                "GENERATION_JOB_ASSET_MANIFEST_BINDING_MISMATCH");
    }

    @Test
    void composeGateIgnoresLegacySpecificationApprovalAndUsesManifestDecision() {
        CompositionModels.EngineComposePlanRequest original = CompositionTestSupport.request(objectMapper);
        ContractModels.LockedPptSlide baseSlide = original.specification().slides().get(0);
        ContractModels.LockedPptAssetReference baseAsset = baseSlide.assetRequirements().get(0);
        ContractModels.LockedPptAssetReference pendingAsset = new ContractModels.LockedPptAssetReference(
                baseAsset.assetId(), baseAsset.assetType(), baseAsset.source(), PENDING,
                baseAsset.required(), baseAsset.placementIntent());
        ContractModels.LockedPptSlide changedSlide = new ContractModels.LockedPptSlide(
                baseSlide.slideId(), baseSlide.pageNumber(), baseSlide.title(), baseSlide.teachingGoal(),
                baseSlide.contentBlocks(), baseSlide.semanticLayout(), List.of(pendingAsset),
                baseSlide.provenance(), baseSlide.notes());
        ContractModels.LockedPptSpecification baseSpec = original.specification();
        ContractModels.LockedPptSpecification withoutChecksum = new ContractModels.LockedPptSpecification(
                baseSpec.contractVersion(), baseSpec.specificationId(), baseSpec.projectId(), baseSpec.version(),
                baseSpec.status(), baseSpec.templateProfileId(), baseSpec.templateProfileVersion(),
                baseSpec.targetSlideCount(), baseSpec.slideCountTolerance(), baseSpec.locale(),
                baseSpec.provider(), baseSpec.model(), baseSpec.aiSupplementPolicy(), baseSpec.lockedBy(),
                baseSpec.lockedAt(), null, List.of(changedSlide));
        ContractModels.LockedPptSpecification changedSpec = new ContractModels.LockedPptSpecification(
                withoutChecksum.contractVersion(), withoutChecksum.specificationId(), withoutChecksum.projectId(),
                withoutChecksum.version(), withoutChecksum.status(), withoutChecksum.templateProfileId(),
                withoutChecksum.templateProfileVersion(), withoutChecksum.targetSlideCount(),
                withoutChecksum.slideCountTolerance(), withoutChecksum.locale(), withoutChecksum.provider(),
                withoutChecksum.model(), withoutChecksum.aiSupplementPolicy(), withoutChecksum.lockedBy(),
                withoutChecksum.lockedAt(), checksums.compute(withoutChecksum), withoutChecksum.slides());
        CompositionModels.EngineComposePlanRequest changed = CompositionTestSupport.request(
                objectMapper, original.requestId(), changedSpec, original.templateProfile());

        assertThat(gate.validateCompose(changed).diagnostics())
                .extracting(ContractModels.Diagnostic::code)
                .doesNotContain("IMAGE_NOT_APPROVED");
        assertThat(gate.validateCompose(changed).executionPackage()).isNotNull();
    }

    @Test
    void retryMayChangeOnlyExecutionAttemptIdentityWhileKeepingJobFingerprint() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.GenerationJob job = request.generationJob();
        CompositionModels.GenerationJob retry = new CompositionModels.GenerationJob(
                job.generationJobId(), "attempt-002", job.jobBindingChecksum(),
                job.specificationBinding(), job.templateProfileBinding(),
                job.approvedAssetManifestBinding(), job.composeContractVersion(),
                job.planContractVersion(), job.engineBuildVersion(), job.executorAdapterVersion(),
                job.fontEnvironmentVersion(), job.requestedBy(), job.requestedAt(), job.idempotencyKey());
        CompositionModels.EngineComposePlanRequest retryRequest =
                new CompositionModels.EngineComposePlanRequest(
                        request.contractVersion(), request.requestId(), retry,
                        request.specification(), request.templateProfile(), request.approvedAssetManifest());

        assertThat(gate.validateCompose(retryRequest).diagnostics()).isEmpty();
        assertThat(gate.validateCompose(retryRequest).executionPackage()).isNotNull();
        assertThat(retry.generationJobId()).isEqualTo(job.generationJobId());
        assertThat(retry.executionAttemptId()).isNotEqualTo(job.executionAttemptId());
    }

    private void assertContractInvalid(ObjectNode raw) {
        assertThatThrownBy(() -> gate.parseCompose(raw))
                .isInstanceOfSatisfying(ContractRejectedException.class, exception ->
                        assertThat(exception.diagnostic().code()).isEqualTo("CONTRACT_INVALID"));
    }

    private void assertBindingCode(
            CompositionModels.EngineComposePlanRequest request,
            CompositionModels.GenerationJob changedJob,
            String expectedCode) {
        CompositionModels.EngineComposePlanRequest changed = new CompositionModels.EngineComposePlanRequest(
                request.contractVersion(), request.requestId(), changedJob,
                request.specification(), request.templateProfile(), request.approvedAssetManifest());
        assertThat(gate.validateCompose(changed).diagnostics())
                .extracting(ContractModels.Diagnostic::code)
                .contains(expectedCode);
        assertThat(gate.validateCompose(changed).executionPackage()).isNull();
    }

    private CompositionModels.ExecutionInputBinding changed(
            CompositionModels.ExecutionInputBinding binding) {
        return new CompositionModels.ExecutionInputBinding(
                binding.inputId() + "-changed", binding.inputVersion(), binding.inputChecksum());
    }

    private CompositionModels.GenerationJob copyJob(
            CompositionModels.GenerationJob base,
            CompositionModels.ExecutionInputBinding specification,
            CompositionModels.ExecutionInputBinding profile,
            CompositionModels.ExecutionInputBinding manifest) {
        return new CompositionModels.GenerationJob(
                base.generationJobId(), base.executionAttemptId(), base.jobBindingChecksum(),
                specification, profile, manifest,
                base.composeContractVersion(), base.planContractVersion(), base.engineBuildVersion(),
                base.executorAdapterVersion(), base.fontEnvironmentVersion(), base.requestedBy(),
                base.requestedAt(), base.idempotencyKey());
    }
}
