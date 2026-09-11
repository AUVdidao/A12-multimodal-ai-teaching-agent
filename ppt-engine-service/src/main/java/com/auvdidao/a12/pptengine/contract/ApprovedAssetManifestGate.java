package com.auvdidao.a12.pptengine.contract;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetManifestStatus.APPROVED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetResolution.APPROVED_ASSET;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.AssetResolution.APPROVED_OMISSION;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_CHECKSUM_MISMATCH;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_CONTENT_HASH_INVALID;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_FILE_IDENTITY_INVALID;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_ENTRY_DANGLING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_ENTRY_DUPLICATE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_ENTRY_INVALID;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_NOT_APPROVED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_REQUIRED_OMISSION;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_REQUIREMENT_MISSING;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_SPECIFICATION_MISMATCH;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.ASSET_MANIFEST_TYPE_MISMATCH;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.ASSET_MANIFEST_GATE;

/** Validates the read-only, teacher-approved asset decision snapshot. */
@Component
public class ApprovedAssetManifestGate {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    private final ChecksumService checksumService;

    public ApprovedAssetManifestGate(ChecksumService checksumService) {
        this.checksumService = checksumService;
    }

    public ManifestValidationResult validate(
            ContractModels.LockedPptSpecification specification,
            CompositionModels.ApprovedAssetManifest manifest) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        Map<String, RequirementContext> requirements = collectRequirements(specification);
        Map<String, CompositionModels.ApprovedAssetManifestEntry> entries = new LinkedHashMap<>();

        if (manifest.status() != APPROVED) {
            diagnostics.add(error(ASSET_MANIFEST_NOT_APPROVED, null,
                    "assetManifest.notApproved", Map.of("status", String.valueOf(manifest.status()))));
        }
        if (!specification.specificationId().equals(manifest.specificationId())
                || specification.version() != manifest.specificationVersion()
                || !specification.checksum().equals(manifest.specificationChecksum())) {
            diagnostics.add(error(ASSET_MANIFEST_SPECIFICATION_MISMATCH, null,
                    "assetManifest.specificationMismatch", Map.of("binding", "specification")));
        }

        String expectedChecksum = checksumService.computeManifest(manifest);
        if (!expectedChecksum.equals(manifest.manifestChecksum())) {
            diagnostics.add(error(ASSET_MANIFEST_CHECKSUM_MISMATCH, null,
                    "assetManifest.checksumMismatch", Map.of("expectedChecksum", expectedChecksum)));
        }

        for (CompositionModels.ApprovedAssetManifestEntry entry : manifest.entries()) {
            String requirementId = entry.assetRequirementId();
            if (entries.putIfAbsent(requirementId, entry) != null) {
                diagnostics.add(error(ASSET_MANIFEST_ENTRY_DUPLICATE, requirementId,
                        "assetManifest.entryDuplicate", Map.of("scope", "assetRequirementId")));
                continue;
            }
            RequirementContext requirement = requirements.get(requirementId);
            if (requirement == null) {
                diagnostics.add(error(ASSET_MANIFEST_ENTRY_DANGLING, requirementId,
                        "assetManifest.entryDangling", Map.of("scope", "assetRequirementId")));
                continue;
            }
            validateEntry(entry, requirement, diagnostics);
        }

        for (Map.Entry<String, RequirementContext> requirement : requirements.entrySet()) {
            if (!entries.containsKey(requirement.getKey())) {
                diagnostics.add(error(ASSET_MANIFEST_REQUIREMENT_MISSING, requirement.getKey(),
                        "assetManifest.requirementMissing",
                        Map.of("required", Boolean.toString(requirement.getValue().asset().required()))));
            }
        }

        return new ManifestValidationResult(diagnostics, entries);
    }

    private void validateEntry(
            CompositionModels.ApprovedAssetManifestEntry entry,
            RequirementContext requirement,
            List<ContractModels.Diagnostic> diagnostics) {
        if (entry.resolution() == APPROVED_OMISSION) {
            if (requirement.asset().required()) {
                diagnostics.add(error(ASSET_MANIFEST_REQUIRED_OMISSION, entry.assetRequirementId(),
                        "assetManifest.requiredOmission", Map.of("required", "true")));
            }
            if (entry.approvedAssetId() != null || entry.assetType() != null || entry.contentSha256() != null) {
                diagnostics.add(error(ASSET_MANIFEST_ENTRY_INVALID, entry.assetRequirementId(),
                        "assetManifest.omissionContainsAsset", Map.of("resolution", APPROVED_OMISSION.name())));
            }
            return;
        }

        if (entry.resolution() != APPROVED_ASSET
                || entry.approvedAssetId() == null
                || entry.assetType() == null
                || entry.contentSha256() == null) {
            diagnostics.add(error(ASSET_MANIFEST_ENTRY_INVALID, entry.assetRequirementId(),
                    "assetManifest.approvedAssetIncomplete", Map.of("resolution", String.valueOf(entry.resolution()))));
            return;
        }
        if (entry.assetType() != requirement.asset().assetType()) {
            diagnostics.add(error(ASSET_MANIFEST_TYPE_MISMATCH, entry.assetRequirementId(),
                    "assetManifest.assetTypeMismatch", Map.of(
                            "requiredType", requirement.asset().assetType().name(),
                            "approvedType", entry.assetType().name())));
        }
        if (!SHA_256.matcher(entry.contentSha256()).matches()) {
            diagnostics.add(error(ASSET_MANIFEST_CONTENT_HASH_INVALID, entry.assetRequirementId(),
                    "assetManifest.contentHashInvalid", Map.of("field", "contentSha256")));
        }
        if (entry.storageKey() == null || entry.storageKey().isBlank()
                || entry.fileSize() == null || entry.fileSize() < 1
                || entry.lastModifiedUtc() == null) {
            diagnostics.add(error(ASSET_MANIFEST_FILE_IDENTITY_INVALID, entry.assetRequirementId(),
                    "assetManifest.fileIdentityInvalid", Map.of("fields", "storageKey,size,lastModifiedUtc")));
        }
    }

    private Map<String, RequirementContext> collectRequirements(
            ContractModels.LockedPptSpecification specification) {
        Map<String, RequirementContext> requirements = new LinkedHashMap<>();
        for (ContractModels.LockedPptSlide slide : specification.slides()) {
            for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
                requirements.putIfAbsent(asset.assetId(), new RequirementContext(slide, asset));
            }
        }
        return requirements;
    }

    private ContractModels.Diagnostic error(
            ContractTypes.DiagnosticCode code,
            String assetRequirementId,
            String messageKey,
            Map<String, String> details) {
        return DiagnosticFactory.stageError(
                ASSET_MANIFEST_GATE, code, messageKey,
                null, null, null, assetRequirementId, null, null, details);
    }

    private record RequirementContext(
            ContractModels.LockedPptSlide slide,
            ContractModels.LockedPptAssetReference asset) {
    }

    public record ManifestValidationResult(
            List<ContractModels.Diagnostic> diagnostics,
            Map<String, CompositionModels.ApprovedAssetManifestEntry> entriesByRequirementId) {
        public ManifestValidationResult {
            diagnostics = List.copyOf(diagnostics);
            entriesByRequirementId = Map.copyOf(entriesByRequirementId);
        }
    }
}
