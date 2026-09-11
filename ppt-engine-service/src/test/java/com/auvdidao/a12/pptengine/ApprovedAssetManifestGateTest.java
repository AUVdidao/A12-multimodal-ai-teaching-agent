package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ApprovedAssetManifestGate;
import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovedAssetManifestGateTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ChecksumService checksums = new ChecksumService(objectMapper);
    private final ApprovedAssetManifestGate gate = new ApprovedAssetManifestGate(checksums);

    @Test
    void validManifestProducesImmutableRequirementLookup() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);

        ApprovedAssetManifestGate.ManifestValidationResult result =
                gate.validate(request.specification(), request.approvedAssetManifest());

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.entriesByRequirementId()).containsOnlyKeys("asset-001");
        assertThat(result.entriesByRequirementId().get("asset-001").approvedAssetId())
                .isEqualTo("approved-asset-001");
    }

    @Test
    void statusSpecificationAndChecksumFailuresStayPrecise() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ApprovedAssetManifest base = request.approvedAssetManifest();
        CompositionModels.ApprovedAssetManifest changed = manifest(
                base, ContractTypes.AssetManifestStatus.REVIEW, "spec-other",
                base.entries(), "0".repeat(64));

        assertThat(gate.validate(request.specification(), changed).diagnostics())
                .extracting(ContractModels.Diagnostic::code)
                .contains(
                        "ASSET_MANIFEST_NOT_APPROVED",
                        "ASSET_MANIFEST_SPECIFICATION_MISMATCH",
                        "ASSET_MANIFEST_CHECKSUM_MISMATCH");
    }

    @Test
    void missingRequiredOmissionDuplicateDanglingAndTypeMismatchAreRejectedSeparately() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ApprovedAssetManifest base = request.approvedAssetManifest();

        assertCode(request, withEntries(base, List.of()), "ASSET_MANIFEST_REQUIREMENT_MISSING");

        var omission = new CompositionModels.ApprovedAssetManifestEntry(
                "asset-001", ContractTypes.AssetResolution.APPROVED_OMISSION,
                null, null, null);
        assertCode(request, withEntries(base, List.of(omission)), "ASSET_MANIFEST_REQUIRED_OMISSION");

        var approved = base.entries().get(0);
        assertCode(request, withEntries(base, List.of(approved, approved)),
                "ASSET_MANIFEST_ENTRY_DUPLICATE");

        var dangling = new CompositionModels.ApprovedAssetManifestEntry(
                "asset-unknown", approved.resolution(), approved.approvedAssetId(),
                approved.assetType(), approved.contentSha256());
        assertCode(request, withEntries(base, List.of(approved, dangling)),
                "ASSET_MANIFEST_ENTRY_DANGLING");

        var wrongType = new CompositionModels.ApprovedAssetManifestEntry(
                approved.assetRequirementId(), approved.resolution(), approved.approvedAssetId(),
                ContractTypes.AssetType.CHART, approved.contentSha256());
        assertCode(request, withEntries(base, List.of(wrongType)),
                "ASSET_MANIFEST_TYPE_MISMATCH");
    }

    @Test
    void invalidApprovedAssetFieldsNeverCollapseIntoGenericContractError() {
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.request(objectMapper);
        CompositionModels.ApprovedAssetManifest base = request.approvedAssetManifest();
        var invalid = new CompositionModels.ApprovedAssetManifestEntry(
                "asset-001", ContractTypes.AssetResolution.APPROVED_ASSET,
                "approved-asset-001", ContractTypes.AssetType.IMAGE, "not-a-sha256");

        assertCode(request, withEntries(base, List.of(invalid)),
                "ASSET_MANIFEST_CONTENT_HASH_INVALID");
    }

    private void assertCode(
            CompositionModels.EngineComposePlanRequest request,
            CompositionModels.ApprovedAssetManifest manifest,
            String expectedCode) {
        assertThat(gate.validate(request.specification(), manifest).diagnostics())
                .extracting(ContractModels.Diagnostic::code)
                .contains(expectedCode)
                .doesNotContain("CONTRACT_INVALID");
    }

    private CompositionModels.ApprovedAssetManifest withEntries(
            CompositionModels.ApprovedAssetManifest base,
            List<CompositionModels.ApprovedAssetManifestEntry> entries) {
        CompositionModels.ApprovedAssetManifest withoutChecksum = manifest(
                base, base.status(), base.specificationId(), entries, null);
        return manifest(base, base.status(), base.specificationId(), entries,
                checksums.computeManifest(withoutChecksum));
    }

    private CompositionModels.ApprovedAssetManifest manifest(
            CompositionModels.ApprovedAssetManifest base,
            ContractTypes.AssetManifestStatus status,
            String specificationId,
            List<CompositionModels.ApprovedAssetManifestEntry> entries,
            String manifestChecksum) {
        return new CompositionModels.ApprovedAssetManifest(
                base.contractVersion(), base.manifestId(), base.manifestVersion(), status,
                specificationId, base.specificationVersion(), base.specificationChecksum(),
                manifestChecksum, new ArrayList<>(entries));
    }
}
