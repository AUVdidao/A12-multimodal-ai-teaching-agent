package com.auvdidao.a12.pptengine.contract;

import com.auvdidao.a12.pptengine.executor.ExecutorModels;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ApprovalStatus.APPROVED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.COMPOSE_CONTRACT_V2;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.COMPOSITION_PLAN_V2;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity.ERROR;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.EXECUTOR_CONTRACT_V2;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.SpecificationStatus.LOCKED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TemplateProfileStatus.CONFIRMED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.V1;

@Component
public class ContractGate {

    private final ObjectMapper objectMapper;
    private final JsonSchemaCatalog schemaCatalog;
    private final ChecksumService checksumService;
    private final ApprovedAssetManifestGate approvedAssetManifestGate;

    public ContractGate(
            ObjectMapper objectMapper,
            JsonSchemaCatalog schemaCatalog,
            ChecksumService checksumService,
            ApprovedAssetManifestGate approvedAssetManifestGate) {
        this.objectMapper = objectMapper;
        this.schemaCatalog = schemaCatalog;
        this.checksumService = checksumService;
        this.approvedAssetManifestGate = approvedAssetManifestGate;
    }

    public ContractModels.EnginePreflightRequest parse(JsonNode rawRequest) {
        return parsePreflight(rawRequest, "engine-preflight-request.schema.json");
    }

    public CompositionModels.EngineComposePlanRequest parseCompose(JsonNode rawRequest) {
        List<JsonSchemaCatalog.SchemaViolation> requestViolations = schemaCatalog.violations(
                "engine-compose-plan-request.schema.json", rawRequest);
        JsonNode rawSpecification = rawRequest == null ? null : rawRequest.get("specification");
        JsonNode rawProfile = rawRequest == null ? null : rawRequest.get("templateProfile");
        JsonNode rawManifest = rawRequest == null ? null : rawRequest.get("approvedAssetManifest");
        List<JsonSchemaCatalog.SchemaViolation> specificationViolations = rawSpecification == null
                ? List.of(new JsonSchemaCatalog.SchemaViolation("/specification", "required", "missing"))
                : schemaCatalog.violations("locked-ppt-specification.schema.json", rawSpecification);
        List<JsonSchemaCatalog.SchemaViolation> profileViolations = rawProfile == null
                ? List.of(new JsonSchemaCatalog.SchemaViolation("/templateProfile", "required", "missing"))
                : schemaCatalog.violations("execution-ready-template-profile.schema.json", rawProfile);
        List<JsonSchemaCatalog.SchemaViolation> manifestViolations = rawManifest == null
                ? List.of(new JsonSchemaCatalog.SchemaViolation("/approvedAssetManifest", "required", "missing"))
                : schemaCatalog.violations("approved-asset-manifest.schema.json", rawManifest);
        List<JsonSchemaCatalog.SchemaViolation> violations = new ArrayList<>();
        violations.addAll(requestViolations);
        violations.addAll(specificationViolations);
        violations.addAll(profileViolations);
        violations.addAll(manifestViolations);
        if (!violations.isEmpty()) {
            throw invalidContract(rawRequest, violations);
        }
        try {
            if (!hasValidExecutionReadyProjection(rawProfile)) {
                throw invalidExecutionReadyProjection(rawRequest);
            }
            return objectMapper.treeToValue(rawRequest, CompositionModels.EngineComposePlanRequest.class);
        } catch (JsonProcessingException exception) {
            throw invalidContract(rawRequest, 1);
        }
    }

    private ContractRejectedException invalidContract(
            JsonNode rawRequest, List<JsonSchemaCatalog.SchemaViolation> violations) {
        String paths = violations.stream().limit(20)
                .map(item -> item.path() + "|" + item.errorType())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
        String types = violations.stream().limit(20)
                .map(JsonSchemaCatalog.SchemaViolation::errorType)
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String observed = rawRequest == null ? "null"
                : "topLevelFields=" + firstField(rawRequest);
        return new ContractRejectedException(
                safeRequestId(rawRequest),
                DiagnosticFactory.gateError(
                        "CONTRACT_INVALID", "contract.invalid", null, null, null, null,
                        null, null,
                        Map.of("violationCount", Integer.toString(violations.size()),
                                "contractVersion", rawRequest == null
                                        ? "" : rawRequest.path("contractVersion").asText(""),
                                "paths", paths, "errorTypes", types, "observed", observed)));
    }

    private ContractRejectedException invalidExecutionReadyProjection(JsonNode rawRequest) {
        JsonNode profile = rawRequest == null ? null : rawRequest.path("templateProfile");
        return new ContractRejectedException(
                safeRequestId(rawRequest),
                DiagnosticFactory.gateError(
                        "EXECUTION_READY_PROJECTION_INVALID",
                        "templateProfile.executionReadyProjectionInvalid",
                        null, null, null, null, null, null,
                        Map.of(
                                "contractVersion", rawRequest == null
                                        ? "" : rawRequest.path("contractVersion").asText(""),
                                "path", "/templateProfile/executionStatus",
                                "errorType", "const/source-identity",
                                "observed", "status=" + profile.path("status").asText("")
                                        + ",executionStatus=" + profile.path("executionStatus").asText(""))));
    }

    public ExecutorModels.ExecuteRequest parseExecute(JsonNode rawRequest) {
        return parseExecute(rawRequest, "engine-execute-request.schema.json", V1);
    }

    public ExecutorModels.ExecuteRequest parseExecuteV2(JsonNode rawRequest) {
        return parseExecute(rawRequest, "v2/engine-execute-request.schema.json", EXECUTOR_CONTRACT_V2);
    }

    private ExecutorModels.ExecuteRequest parseExecute(
            JsonNode rawRequest, String requestSchema, String expectedContractVersion) {
        int requestViolations = schemaCatalog.violationCount(
                requestSchema, rawRequest);
        JsonNode rawSpecification = rawRequest == null ? null : rawRequest.get("specification");
        JsonNode rawProfile = rawRequest == null ? null : rawRequest.get("templateProfile");
        JsonNode rawManifest = rawRequest == null ? null : rawRequest.get("approvedAssetManifest");
        int specificationViolations = rawSpecification == null
                ? 1
                : schemaCatalog.violationCount("locked-ppt-specification.schema.json", rawSpecification);
        int profileViolations = rawProfile == null
                ? 1
                : schemaCatalog.violationCount("execution-ready-template-profile.schema.json", rawProfile);
        int manifestViolations = rawManifest == null
                ? 1
                : schemaCatalog.violationCount("approved-asset-manifest.schema.json", rawManifest);
        int totalViolations = requestViolations + specificationViolations
                + profileViolations + manifestViolations;
        if (totalViolations > 0) {
            throw invalidContract(rawRequest, totalViolations);
        }
        if (!hasValidExecutionReadyProjection(rawProfile)) {
            throw invalidExecutionReadyProjection(rawRequest);
        }
        try {
            ExecutorModels.ExecuteRequest request = objectMapper.treeToValue(rawRequest, ExecutorModels.ExecuteRequest.class);
            if (!expectedContractVersion.equals(request.contractVersion())) {
                throw invalidContract(rawRequest, 1);
            }
            List<ContractModels.Diagnostic> bindingDiagnostics = validateExecuteGenerationBindings(request);
            if (!bindingDiagnostics.isEmpty()) {
                throw new ContractRejectedException(request.requestId(), bindingDiagnostics.get(0));
            }
            List<ContractModels.Diagnostic> identityDiagnostics = validateExecuteAssetIdentity(request);
            if (!identityDiagnostics.isEmpty()) {
                throw new ContractRejectedException(request.requestId(), identityDiagnostics.get(0));
            }
            return request;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidContract(rawRequest, 1);
        }
    }

    /** Execute is a separate trust boundary and must repeat ownership checks even when
     * the request bypasses the compose-plan endpoint. */
    private List<ContractModels.Diagnostic> validateExecuteGenerationBindings(
            ExecutorModels.ExecuteRequest request) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        CompositionModels.GenerationJob job = request.generationJob();
        ContractModels.LockedPptSpecification specification = request.specification();
        ContractModels.ConfirmedTemplateProfile profile = request.templateProfile();
        CompositionModels.ApprovedAssetManifest manifest = request.approvedAssetManifest();
        if (!safeEquals(job.projectId(), specification.projectId())
                || !safeEquals(job.projectId(), profile.projectId())
                || !safeEquals(job.projectId(), manifest.projectId())) {
            diagnostics.add(DiagnosticFactory.gateError(
                    ContractTypes.DiagnosticCode.GENERATION_JOB_PROJECT_BINDING_MISMATCH,
                    "generationJob.projectBindingMismatch", null, null, null, null,
                    null, null, Map.of("binding", "projectId")));
        }
        if (!safeEquals(job.ownerUserId(), profile.ownerUserId())
                || !safeEquals(job.ownerUserId(), manifest.ownerUserId())) {
            diagnostics.add(DiagnosticFactory.gateError(
                    ContractTypes.DiagnosticCode.GENERATION_JOB_OWNER_BINDING_MISMATCH,
                    "generationJob.ownerBindingMismatch", null, null, null, null,
                    null, null, Map.of("binding", "ownerUserId")));
        }
        return diagnostics;
    }

    /** The manifest is the approval baseline; the file list may not replace it at execute time. */
    private List<ContractModels.Diagnostic> validateExecuteAssetIdentity(ExecutorModels.ExecuteRequest request) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        Map<String, CompositionModels.ApprovedAssetManifestEntry> entries = new HashMap<>();
        request.approvedAssetManifest().entries().forEach(entry -> entries.put(entry.approvedAssetId(), entry));
        Set<String> matchedRequirements = new HashSet<>();
        for (ExecutorModels.ApprovedAssetFile file : request.approvedAssetFiles()) {
            CompositionModels.ApprovedAssetManifestEntry entry = entries.get(file.approvedAssetId());
            if (entry == null || entry.resolution() != ContractTypes.AssetResolution.APPROVED_ASSET
                    || !safeEquals(entry.assetRequirementId(), file.assetRequirementId())
                    || !safeEquals(entry.storageKey(), file.absolutePath())
                    || !safeEquals(entry.contentSha256(), file.sha256())
                    || !safeEquals(String.valueOf(entry.fileSize()), String.valueOf(file.size()))
                    || entry.lastModifiedUtc() == null
                    || !sameInstant(entry.lastModifiedUtc(), file.lastModifiedUtc())) {
                diagnostics.add(DiagnosticFactory.gateError(
                        ContractTypes.DiagnosticCode.ASSET_MANIFEST_FILE_IDENTITY_INVALID,
                        "assetManifest.fileIdentityMismatch", null, null, null,
                        file.assetRequirementId(), null, null,
                        Map.of("binding", "approvedAssetManifest")));
            } else {
                matchedRequirements.add(entry.assetRequirementId());
            }
        }
        request.approvedAssetManifest().entries().stream()
                .filter(entry -> entry.resolution() == ContractTypes.AssetResolution.APPROVED_ASSET)
                .filter(entry -> !matchedRequirements.contains(entry.assetRequirementId()))
                .forEach(entry -> diagnostics.add(DiagnosticFactory.gateError(
                        ContractTypes.DiagnosticCode.ASSET_MANIFEST_FILE_IDENTITY_INVALID,
                        "assetManifest.fileIdentityMissing", null, null, null,
                        entry.assetRequirementId(), null, null,
                        Map.of("binding", "approvedAssetManifest"))));
        return diagnostics;
    }

    private ContractModels.EnginePreflightRequest parsePreflight(JsonNode rawRequest, String requestSchema) {
        int requestViolations = schemaCatalog.violationCount(requestSchema, rawRequest);
        JsonNode rawSpecification = rawRequest == null ? null : rawRequest.get("specification");
        JsonNode rawProfile = rawRequest == null ? null : rawRequest.get("templateProfile");
        int specificationViolations = rawSpecification == null
                ? 1
                : schemaCatalog.violationCount("locked-ppt-specification.schema.json", rawSpecification);
        int profileViolations = rawProfile == null
                ? 1
                : schemaCatalog.violationCount("confirmed-template-profile.schema.json", rawProfile);
        int totalViolations = requestViolations + specificationViolations + profileViolations;
        if (totalViolations > 0) {
            throw new ContractRejectedException(
                    safeRequestId(rawRequest),
                    DiagnosticFactory.gateError(
                            "CONTRACT_INVALID", "contract.invalid", null, null, null, null,
                            null, null, Map.of("violationCount", Integer.toString(totalViolations))));
        }
        try {
            return objectMapper.treeToValue(rawRequest, ContractModels.EnginePreflightRequest.class);
        } catch (JsonProcessingException exception) {
            throw new ContractRejectedException(
                    safeRequestId(rawRequest),
                    DiagnosticFactory.gateError(
                            "CONTRACT_INVALID", "contract.invalid", null, null, null, null,
                            null, null, Map.of("violationCount", "1")));
        }
    }

    /**
     * Validates all compose bindings and only emits a package when every
     * job-blocking input invariant is satisfied.
     */
    public ComposeValidationResult validateCompose(
            CompositionModels.EngineComposePlanRequest request) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        if (!COMPOSE_CONTRACT_V2.equals(request.contractVersion())) {
            diagnostics.add(DiagnosticFactory.gateError(
                    ContractTypes.DiagnosticCode.GENERATION_JOB_CONTRACT_VERSION_MISMATCH,
                    "generationJob.composeContractMismatch", null, null, null, null,
                    null, null, Map.of("field", "contractVersion")));
        }

        // Reuse the frozen v1 semantic gate but deliberately remove its legacy
        // asset approval decision. Final compose authorization comes only from
        // the Approved Asset Manifest.
        ContractModels.EnginePreflightRequest compatibilityView =
                new ContractModels.EnginePreflightRequest(
                        V1, request.requestId(), request.specification(), request.templateProfile());
        validateSemantics(compatibilityView).stream()
                .filter(item -> !ContractTypes.DiagnosticCode.IMAGE_NOT_APPROVED.name().equals(item.code()))
                .filter(item -> !(request.templateProfile().status()
                        == ContractTypes.TemplateProfileStatus.READY
                        && "TEMPLATE_PROFILE_NOT_CONFIRMED".equals(item.code())))
                .forEach(diagnostics::add);

        validateGenerationJobBindings(request, diagnostics);
        ApprovedAssetManifestGate.ManifestValidationResult manifestResult =
                approvedAssetManifestGate.validate(
                        request.specification(), request.approvedAssetManifest());
        diagnostics.addAll(manifestResult.diagnostics());

        CompositionModels.ValidatedExecutionPackage executionPackage = hasErrors(diagnostics)
                ? null
                : new CompositionModels.ValidatedExecutionPackage(
                        COMPOSE_CONTRACT_V2,
                        request.requestId(),
                        request.generationJob(),
                        request.specification(),
                        request.templateProfile(),
                        request.approvedAssetManifest(),
                        manifestResult.entriesByRequirementId());
        return new ComposeValidationResult(executionPackage, diagnostics);
    }

    private void validateGenerationJobBindings(
            CompositionModels.EngineComposePlanRequest request,
            List<ContractModels.Diagnostic> diagnostics) {
        CompositionModels.GenerationJob job = request.generationJob();
        ContractModels.LockedPptSpecification specification = request.specification();
        ContractModels.ConfirmedTemplateProfile profile = request.templateProfile();
        CompositionModels.ApprovedAssetManifest manifest = request.approvedAssetManifest();

        if (!safeEquals(job.jobBindingChecksum(), checksumService.computeGenerationJobBinding(job))) {
            diagnostics.add(DiagnosticFactory.gateError(
                    ContractTypes.DiagnosticCode.GENERATION_JOB_BINDING_CHECKSUM_MISMATCH,
                    "generationJob.bindingChecksumMismatch", null, null, null, null,
                    null, null, Map.of("binding", "generationJob")));
        }
        if (!COMPOSE_CONTRACT_V2.equals(job.composeContractVersion())
                || !COMPOSITION_PLAN_V2.equals(job.planContractVersion())) {
            diagnostics.add(DiagnosticFactory.gateError(
                    ContractTypes.DiagnosticCode.GENERATION_JOB_CONTRACT_VERSION_MISMATCH,
                    "generationJob.contractVersionMismatch", null, null, null, null,
                    null, null, Map.of("binding", "composeOrPlanContract")));
        }
        if (!bindingMatches(job.specificationBinding(), specification.specificationId(),
                specification.version(), specification.checksum())) {
            diagnostics.add(DiagnosticFactory.gateError(
                    ContractTypes.DiagnosticCode.GENERATION_JOB_SPECIFICATION_BINDING_MISMATCH,
                    "generationJob.specificationBindingMismatch", null, null, null, null,
                    null, null, Map.of("binding", "specification")));
        }
        if (!bindingMatches(job.templateProfileBinding(), profile.profileId(),
                profile.profileVersion(), checksumService.computeProfile(profile))) {
            Map<String, String> bindingDetails = new HashMap<>();
            bindingDetails.put("binding", "templateProfile");
            bindingDetails.put("expectedChecksum", checksumService.computeProfile(profile));
            bindingDetails.put("observedChecksum", job.templateProfileBinding() == null
                    ? "null" : job.templateProfileBinding().inputChecksum());
            diagnostics.add(DiagnosticFactory.gateError(
                    ContractTypes.DiagnosticCode.GENERATION_JOB_TEMPLATE_PROFILE_BINDING_MISMATCH,
                    "generationJob.templateProfileBindingMismatch", null, null, null, null,
                    null, null, bindingDetails));
        }
        if (!bindingMatches(job.approvedAssetManifestBinding(), manifest.manifestId(),
                manifest.manifestVersion(), manifest.manifestChecksum())) {
            diagnostics.add(DiagnosticFactory.gateError(
                    ContractTypes.DiagnosticCode.GENERATION_JOB_ASSET_MANIFEST_BINDING_MISMATCH,
                    "generationJob.assetManifestBindingMismatch", null, null, null, null,
                    null, null, Map.of("binding", "approvedAssetManifest")));
        }
        if (!safeEquals(job.projectId(), specification.projectId())
                || !safeEquals(job.projectId(), profile.projectId())
                || !safeEquals(job.projectId(), manifest.projectId())) {
            diagnostics.add(DiagnosticFactory.gateError(
                    ContractTypes.DiagnosticCode.GENERATION_JOB_PROJECT_BINDING_MISMATCH,
                    "generationJob.projectBindingMismatch", null, null, null, null,
                    null, null, Map.of("binding", "projectId")));
        }
        if (!safeEquals(job.ownerUserId(), profile.ownerUserId())
                || !safeEquals(job.ownerUserId(), manifest.ownerUserId())) {
            diagnostics.add(DiagnosticFactory.gateError(
                    ContractTypes.DiagnosticCode.GENERATION_JOB_OWNER_BINDING_MISMATCH,
                    "generationJob.ownerBindingMismatch", null, null, null, null,
                    null, null, Map.of("binding", "ownerUserId")));
        }
    }

    private boolean bindingMatches(
            CompositionModels.ExecutionInputBinding binding,
            String expectedId,
            int expectedVersion,
            String expectedChecksum) {
        return binding != null
                && safeEquals(expectedId, binding.inputId())
                && expectedVersion == binding.inputVersion()
                && safeEquals(expectedChecksum, binding.inputChecksum());
    }

    private boolean sameInstant(java.time.OffsetDateTime expected, String actual) {
        try {
            return java.time.Instant.parse(actual).equals(expected.toInstant());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ContractRejectedException invalidContract(JsonNode rawRequest, int violationCount) {
        return new ContractRejectedException(
                safeRequestId(rawRequest),
                DiagnosticFactory.gateError(
                        "CONTRACT_INVALID", "contract.invalid", null, null, null, null,
                        null, null, Map.of("violationCount", Integer.toString(violationCount))));
    }

    private boolean hasValidExecutionReadyProjection(JsonNode rawProfile) {
        if (rawProfile == null || !"READY".equals(rawProfile.path("status").asText())) {
            return true;
        }
        return "EXECUTION_READY".equals(rawProfile.path("executionStatus").asText())
                && rawProfile.path("sourceVersionId").canConvertToInt()
                && rawProfile.path("sourceVersionId").asInt() >= 1
                && rawProfile.path("sourceSha256").asText().matches("[0-9a-fA-F]{64}")
                && rawProfile.path("parserSnapshotChecksum").asText().matches("[0-9a-fA-F]{64}");
    }

    private String firstField(JsonNode rawRequest) {
        java.util.Iterator<String> fields = rawRequest.fieldNames();
        return fields.hasNext() ? fields.next() : "none";
    }

    public List<ContractModels.Diagnostic> validateSemantics(ContractModels.EnginePreflightRequest request) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        ContractModels.LockedPptSpecification specification = request.specification();
        ContractModels.ConfirmedTemplateProfile profile = request.templateProfile();

        if (!V1.equals(request.contractVersion())) {
            diagnostics.add(DiagnosticFactory.gateError(
                    "CONTRACT_INVALID", "contract.invalid", null, null, null, null,
                    null, null, Map.of("field", "contractVersion")));
        }
        if (specification.status() != LOCKED) {
            diagnostics.add(DiagnosticFactory.gateError(
                    "SPECIFICATION_NOT_LOCKED", "specification.notLocked", null, null, null, null,
                    null, null, Map.of("status", String.valueOf(specification.status()))));
        }
        if (profile.status() != CONFIRMED) {
            diagnostics.add(DiagnosticFactory.gateError(
                    "TEMPLATE_PROFILE_NOT_CONFIRMED", "templateProfile.notConfirmed", null, null, null, null,
                    null, null, Map.of("status", String.valueOf(profile.status()))));
        }
        if (!safeEquals(specification.templateProfileId(), profile.profileId())
                || specification.templateProfileVersion() != profile.profileVersion()) {
            diagnostics.add(DiagnosticFactory.gateError(
                    "TEMPLATE_PROFILE_MISMATCH", "templateProfile.mismatch", null, null, null, null,
                    null, null, Map.of("field", "templateProfileIdOrVersion")));
        }
        if (!checksumService.matches(specification)) {
            diagnostics.add(DiagnosticFactory.gateError(
                    "CHECKSUM_MISMATCH", "specification.checksumMismatch", null, null, null, null,
                    null, null, Map.of(
                            "providedChecksum", safeValue(specification.checksum()),
                            "expectedChecksum", checksumService.compute(specification))));
        }
        validateSlideCount(diagnostics, specification);
        validateSlides(diagnostics, specification);
        validateProfile(diagnostics, profile);
        return diagnostics;
    }

    private void validateSlideCount(List<ContractModels.Diagnostic> diagnostics,
                                    ContractModels.LockedPptSpecification specification) {
        int minimum = specification.targetSlideCount() - specification.slideCountTolerance();
        int maximum = specification.targetSlideCount() + specification.slideCountTolerance();
        if (specification.slides().size() < minimum || specification.slides().size() > maximum) {
            diagnostics.add(DiagnosticFactory.gateError(
                    "SLIDE_COUNT_MISMATCH", "specification.slideCountMismatch", null, null, null, null,
                    null, null, Map.of("actualCount", Integer.toString(specification.slides().size()))));
        }
    }

    private void validateSlides(
            List<ContractModels.Diagnostic> diagnostics,
            ContractModels.LockedPptSpecification specification) {
        Set<String> slideIds = new HashSet<>();
        Set<Integer> pageNumbers = new HashSet<>();
        Set<String> blockIds = new HashSet<>();
        Set<String> assetIds = new HashSet<>();
        int expectedPage = 1;
        for (ContractModels.LockedPptSlide slide : specification.slides()) {
            if (!slideIds.add(slide.slideId())) {
                diagnostics.add(DiagnosticFactory.gateError(
                        "DUPLICATE_ID", "id.duplicate", slide.slideId(), slide.pageNumber(), null, null,
                        null, null, Map.of("scope", "slide")));
            }
            if (!pageNumbers.add(slide.pageNumber()) || slide.pageNumber() != expectedPage) {
                diagnostics.add(DiagnosticFactory.gateError(
                        "PAGE_SEQUENCE_INVALID", "page.sequenceInvalid", slide.slideId(), slide.pageNumber(),
                        null, null, null, null, Map.of("expectedPage", Integer.toString(expectedPage))));
            }
            expectedPage++;
            if (slide.contentBlocks().size() > ResourceLimits.MAX_CONTENT_BLOCKS_PER_SLIDE
                    || slide.assetRequirements().size() > ResourceLimits.MAX_ASSETS_PER_SLIDE) {
                diagnostics.add(DiagnosticFactory.gateError(
                        "CONTRACT_INVALID", "resource.limitExceeded", slide.slideId(), slide.pageNumber(),
                        null, null, null, null, Map.of("scope", "slide")));
            }
            for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
                if (!blockIds.add(block.blockId())) {
                    diagnostics.add(DiagnosticFactory.gateError(
                            "DUPLICATE_ID", "id.duplicate", slide.slideId(), slide.pageNumber(), block.blockId(),
                            null, null, null, Map.of("scope", "block")));
                }
                if (!block.locked()) {
                    diagnostics.add(DiagnosticFactory.gateError(
                            "CONTRACT_INVALID", "contentBlock.notLocked", slide.slideId(), slide.pageNumber(),
                            block.blockId(), null, null, null, Map.of("field", "locked")));
                }
            }
            for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
                if (!assetIds.add(asset.assetId())) {
                    diagnostics.add(DiagnosticFactory.gateError(
                            "DUPLICATE_ID", "id.duplicate", slide.slideId(), slide.pageNumber(), null,
                            asset.assetId(), null, null, Map.of("scope", "asset")));
                }
                if (asset.required() && asset.approvalStatus() != APPROVED) {
                    diagnostics.add(DiagnosticFactory.gateError(
                            "IMAGE_NOT_APPROVED", "asset.requiredNotApproved", slide.slideId(), slide.pageNumber(),
                            null, asset.assetId(), null, null, Map.of("required", "true")));
                }
            }
        }
    }

    private void validateProfile(
            List<ContractModels.Diagnostic> diagnostics,
            ContractModels.ConfirmedTemplateProfile profile) {
        if (profile.components().size() > ResourceLimits.MAX_COMPONENTS) {
            diagnostics.add(DiagnosticFactory.gateError(
                    "CONTRACT_INVALID", "resource.limitExceeded", null, null, null, null,
                    null, null, Map.of("scope", "components")));
        }
        Set<String> componentIds = new HashSet<>();
        Set<String> slotIds = new HashSet<>();
        Set<String> pageReferenceIds = new HashSet<>();
        Set<Integer> sourceSlides = new HashSet<>();
        Map<String, ContractModels.TemplateComponent> components = new HashMap<>();
        Map<Integer, Set<String>> objectIdsBySlide = new HashMap<>();
        for (ContractModels.TemplatePageReference page : profile.templatePageReferences()) {
            if (!pageReferenceIds.add(page.pageReferenceId())) {
                diagnostics.add(DiagnosticFactory.gateError(
                        "DUPLICATE_ID", "id.duplicate", null, null, null, null,
                        null, null, Map.of("scope", "templatePageReference")));
            }
            sourceSlides.add(page.sourceSlide());
            objectIdsBySlide.computeIfAbsent(page.sourceSlide(), ignored -> new HashSet<>())
                    .addAll(page.objectIds());
        }
        for (ContractModels.TemplateComponent component : profile.components()) {
            components.put(component.componentId(), component);
            if (!componentIds.add(component.componentId())) {
                diagnostics.add(DiagnosticFactory.gateError(
                        "DUPLICATE_ID", "id.duplicate", null, null, null, null,
                        component.componentId(), null, Map.of("scope", "component")));
            }
            if (!sourceSlides.contains(component.sourceSlide())) {
                diagnostics.add(DiagnosticFactory.gateError(
                        "CONTRACT_INVALID", "reference.templatePageMissing", null, null, null, null,
                        component.componentId(), null, Map.of("reference", "sourceSlide")));
            }
            if (component.slots().size() > ResourceLimits.MAX_SLOTS_PER_COMPONENT) {
                diagnostics.add(DiagnosticFactory.gateError(
                        "CONTRACT_INVALID", "resource.limitExceeded", null, null, null, null,
                        component.componentId(), null, Map.of("scope", "slots")));
            }
            for (ContractModels.StableObjectReference shape : component.shapeRefs()) {
                if (!objectIdsBySlide.getOrDefault(component.sourceSlide(), Set.of()).contains(shape.objectId())) {
                    diagnostics.add(DiagnosticFactory.gateError(
                            "CONTRACT_INVALID", "reference.shapeMissing", null, null, null, null,
                            component.componentId(), null, Map.of("reference", "shape")));
                }
            }
            for (ContractModels.ComponentSlot slot : component.slots()) {
                if (!slotIds.add(slot.slotId())) {
                    diagnostics.add(DiagnosticFactory.gateError(
                            "DUPLICATE_ID", "id.duplicate", null, null, null, null,
                            component.componentId(), slot.slotId(), Map.of("scope", "slot")));
                }
            }
            for (String childId : component.childComponentIds()) {
                if (!components.containsKey(childId) && profile.components().stream()
                        .noneMatch(item -> item.componentId().equals(childId))) {
                    diagnostics.add(DiagnosticFactory.gateError(
                            "CONTRACT_INVALID", "reference.childComponentMissing", null, null, null, null,
                            component.componentId(), null, Map.of("reference", "childComponent")));
                }
            }
        }
        detectComponentCycles(diagnostics, profile.components(), components);
        detectNestingDepth(diagnostics, profile.components(), components);
    }

    private void detectComponentCycles(
            List<ContractModels.Diagnostic> diagnostics,
            List<ContractModels.TemplateComponent> all,
            Map<String, ContractModels.TemplateComponent> components) {
        Map<String, Integer> color = new HashMap<>();
        for (ContractModels.TemplateComponent component : all) {
            if (color.getOrDefault(component.componentId(), 0) == 0) {
                visit(component.componentId(), components, color, diagnostics);
            }
        }
    }

    private void visit(
            String componentId,
            Map<String, ContractModels.TemplateComponent> components,
            Map<String, Integer> color,
            List<ContractModels.Diagnostic> diagnostics) {
        color.put(componentId, 1);
        ContractModels.TemplateComponent component = components.get(componentId);
        if (component != null) {
            for (String childId : component.childComponentIds()) {
                int childColor = color.getOrDefault(childId, 0);
                if (childColor == 1) {
                    diagnostics.add(DiagnosticFactory.gateError(
                            "CONTRACT_INVALID", "reference.componentCycle", null, null, null, null,
                            componentId, null, Map.of("reference", "childComponent")));
                } else if (childColor == 0 && components.containsKey(childId)) {
                    visit(childId, components, color, diagnostics);
                }
            }
        }
        color.put(componentId, 2);
    }

    private void detectNestingDepth(
            List<ContractModels.Diagnostic> diagnostics,
            List<ContractModels.TemplateComponent> all,
            Map<String, ContractModels.TemplateComponent> components) {
        for (ContractModels.TemplateComponent component : all) {
            int depth = depth(component.componentId(), components, new HashSet<>());
            if (depth > ResourceLimits.MAX_NESTING_DEPTH) {
                diagnostics.add(DiagnosticFactory.gateError(
                        "CONTRACT_INVALID", "resource.nestingTooDeep", null, null, null, null,
                        component.componentId(), null, Map.of("maxDepth", Integer.toString(ResourceLimits.MAX_NESTING_DEPTH))));
            }
        }
    }

    private int depth(
            String componentId,
            Map<String, ContractModels.TemplateComponent> components,
            Set<String> path) {
        if (!path.add(componentId)) {
            return ResourceLimits.MAX_NESTING_DEPTH + 1;
        }
        ContractModels.TemplateComponent component = components.get(componentId);
        int deepestChild = 0;
        if (component != null) {
            for (String childId : component.childComponentIds()) {
                deepestChild = Math.max(deepestChild, depth(childId, components, path));
            }
        }
        path.remove(componentId);
        return 1 + deepestChild;
    }

    public static boolean hasErrors(List<ContractModels.Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(item -> item.severity() == ERROR);
    }

    public record ComposeValidationResult(
            CompositionModels.ValidatedExecutionPackage executionPackage,
            List<ContractModels.Diagnostic> diagnostics) {
        public ComposeValidationResult {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private String safeRequestId(JsonNode rawRequest) {
        if (rawRequest == null) {
            return "unknown";
        }
        JsonNode requestId = rawRequest.get("requestId");
        return requestId != null && requestId.isTextual() && requestId.textValue().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                ? requestId.textValue()
                : "unknown";
    }

    private boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }

    private String safeValue(String value) {
        return value == null ? "missing" : value;
    }
}
