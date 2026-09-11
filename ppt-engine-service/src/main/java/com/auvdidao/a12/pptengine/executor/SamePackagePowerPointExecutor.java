package com.auvdidao.a12.pptengine.executor;

import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.CompositionDiagnosticClassifier;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractTypes.ComponentObjectAction;
import com.auvdidao.a12.pptengine.contract.ContractTypes.CompositionOperationType;
import com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticImpact;
import com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity;
import com.auvdidao.a12.pptengine.contract.ContractTypes.ObjectType;
import com.auvdidao.a12.pptengine.composition.CompositionPlanValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode;

/** Executes only operations that remain inside one teacher-selected PPTX package. */
@Service
public class SamePackagePowerPointExecutor {

    private static final String IMAGE_REL_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image";
    private static final String IMAGE_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private final ChecksumService checksumService;
    private final ContractGate contractGate;
    private final CompositionPlanValidator compositionPlanValidator;
    private final PptxBuildValidator buildValidator = new PptxBuildValidator();
    private final Path outputRoot;
    private final Path inputRoot;
    private final boolean allowLegacyAbsolutePaths;
    private final Runnable beforeArtifactCommitHook;

    @Autowired
    public SamePackagePowerPointExecutor(
            ChecksumService checksumService,
            ContractGate contractGate,
            CompositionPlanValidator compositionPlanValidator,
            @Value("${ppt.engine.executor.output-root:target/executor-output}") String outputRoot,
            @Value("${ppt.engine.executor.input-root:target/engine-input}") String inputRoot,
            @Value("${ppt.engine.executor.allow-legacy-absolute-paths:false}") boolean allowLegacyAbsolutePaths) {
        this(checksumService, contractGate, compositionPlanValidator, outputRoot, () -> { }, inputRoot,
                allowLegacyAbsolutePaths);
    }

    public SamePackagePowerPointExecutor(
            ChecksumService checksumService,
            ContractGate contractGate,
            CompositionPlanValidator compositionPlanValidator,
            String outputRoot,
            Runnable beforeArtifactCommitHook) {
        this(checksumService, contractGate, compositionPlanValidator, outputRoot, beforeArtifactCommitHook, null);
    }

    public SamePackagePowerPointExecutor(
            ChecksumService checksumService,
            ContractGate contractGate,
            CompositionPlanValidator compositionPlanValidator,
            String outputRoot) {
        this(checksumService, contractGate, compositionPlanValidator, outputRoot, () -> { }, null);
    }

    public SamePackagePowerPointExecutor(
            ChecksumService checksumService,
            ContractGate contractGate,
            CompositionPlanValidator compositionPlanValidator,
            String outputRoot,
            Runnable beforeArtifactCommitHook,
            String inputRoot) {
        this(checksumService, contractGate, compositionPlanValidator, outputRoot, beforeArtifactCommitHook,
                inputRoot, true);
    }

    public SamePackagePowerPointExecutor(
            ChecksumService checksumService,
            ContractGate contractGate,
            CompositionPlanValidator compositionPlanValidator,
            String outputRoot,
            Runnable beforeArtifactCommitHook,
            String inputRoot,
            boolean allowLegacyAbsolutePaths) {
        this.checksumService = checksumService;
        this.contractGate = contractGate;
        this.compositionPlanValidator = compositionPlanValidator;
        this.outputRoot = Path.of(outputRoot).toAbsolutePath().normalize();
        if (inputRoot == null || inputRoot.isBlank()) {
            if (!allowLegacyAbsolutePaths) {
                throw new IllegalStateException("PPT_ENGINE_INPUT_ROOT must be configured and non-empty");
            }
            this.inputRoot = null;
        } else {
            this.inputRoot = Path.of(inputRoot).toAbsolutePath().normalize();
            try {
                Files.createDirectories(this.inputRoot);
                if (!Files.isDirectory(this.inputRoot, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isReadable(this.inputRoot)) {
                    throw new IllegalStateException("PPT_ENGINE_INPUT_ROOT is not a readable directory");
                }
            } catch (IOException exception) {
                throw new IllegalStateException("PPT_ENGINE_INPUT_ROOT cannot be initialized", exception);
            }
        }
        this.allowLegacyAbsolutePaths = allowLegacyAbsolutePaths;
        this.beforeArtifactCommitHook = beforeArtifactCommitHook == null ? () -> { } : beforeArtifactCommitHook;
    }

    public ExecutionResult execute(ExecutorModels.ExecuteRequest request) {
        List<ExecutorModels.ExecutorDiagnostic> diagnostics = new ArrayList<>();
        if (request == null
                || !(ContractTypes.EXECUTOR_CONTRACT_V1.equals(request.contractVersion())
                || ContractTypes.EXECUTOR_CONTRACT_V2.equals(request.contractVersion()))) {
            diagnostics.add(error(DiagnosticCode.EXECUTOR_INPUT_INVALID,
                    "executor.contractVersionInvalid", null, null, null, null, null, Map.of()));
            return failed(diagnostics);
        }

        CompositionModels.EngineComposePlanRequest composeRequest = new CompositionModels.EngineComposePlanRequest(
                ContractTypes.COMPOSE_CONTRACT_V2, request.requestId(), request.generationJob(),
                request.specification(), request.templateProfile(), request.approvedAssetManifest());
        ContractGateResult gate = validateComposeInputs(composeRequest, diagnostics);
        diagnostics.addAll(gate.diagnostics());
        if (gate.executionPackage() == null) {
            return failed(diagnostics);
        }
        if (!diagnostics.isEmpty()) {
            return failed(diagnostics);
        }
        CompositionPlanValidator.PartialPlanContext partialContext = inferPartialPlanContext(request);
        addMissingPlanDiagnostics(request, partialContext, diagnostics);
        List<com.auvdidao.a12.pptengine.contract.ContractModels.Diagnostic> planDiagnostics =
                compositionPlanValidator.validate(gate.executionPackage(), request.plan(), partialContext);
        if (!planDiagnostics.isEmpty()) {
            diagnostics.addAll(planDiagnostics.stream().map(this::fromPlanDiagnostic).toList());
            return failed(diagnostics);
        }
        if (!validatePlanBinding(request, diagnostics)) {
            return failed(diagnostics);
        }

        FileIdentity source = validateSource(request.templateSource(), request.templateProfile(), diagnostics);
        if (source == null) {
            return failed(diagnostics);
        }
        Map<String, FileIdentity> assets = validateAssets(request, diagnostics);
        if (!diagnostics.isEmpty()) {
            return failed(diagnostics);
        }

        String executionId = safeExecutionId(request.generationJob().executionAttemptId());
        if (executionId == null) {
            diagnostics.add(error(DiagnosticCode.EXECUTOR_INPUT_INVALID,
                    "executor.executionAttemptIdInvalid", null, null, null, null, null, Map.of()));
            return failed(diagnostics);
        }
        AttemptDirectory attempt = prepareAttemptDirectory(request, source, assets, executionId, diagnostics);
        if (attempt == null) {
            return failed(diagnostics);
        }
        if (!attempt.created()) {
            return idempotentResult(attempt.directory(), diagnostics);
        }
        Path executionDirectory = attempt.directory();
        Path workingCopy = executionDirectory.resolve("working-copy.pptx");
        Path temporaryOutput = executionDirectory.resolve("artifact.tmp");
        Path artifact = executionDirectory.resolve("generation-result.pptx");
        Set<String> applied = new HashSet<>();
        Set<String> skipped = new HashSet<>();
        Map<String, String> assetParts = new HashMap<>();
        try {
            Files.copy(source.path(), workingCopy);
            FileIdentity copiedSource = verifySourceIdentity(source, diagnostics, "executor.sourceChangedAfterCopy");
            if (copiedSource == null || !sameIdentity(copiedSource, source)
                    || !samePayload(identity(workingCopy), source)) {
                diagnostics.add(error(DiagnosticCode.TEMPLATE_SOURCE_HASH_MISMATCH,
                        "executor.workingCopyIdentityMismatch", null, null, null, null, null, Map.of()));
                return failedAndCleanup(diagnostics, executionDirectory, true);
            }
            writeAttemptIdentity(executionDirectory, source, workingCopy);
            PptxPackage pack = PptxPackage.read(workingCopy);
            List<String> templateSlides = pack.slidePaths();
            if (!validateTemplateSourceStructure(request, templateSlides, diagnostics)) {
                return failedAndCleanup(diagnostics, executionDirectory, true);
            }
            List<String> targetSlides;
            if (templateSlides.size() == 1 && request.plan().slides().size() == 1
                    && request.plan().slides().get(0).layout().templatePage().sourceSlide() == 1) {
                // Preserve the historical single-slide artifact path.  The
                // multi-slide path below always materializes isolated slide
                // parts before any operation can mutate a source part.
                targetSlides = List.of(templateSlides.get(0));
            } else {
                targetSlides = pack.materializeSlides(request.plan().slides().stream()
                        .map(slide -> slide.layout().templatePage().sourceSlide()).toList());
            }
            executeOperations(request, pack, templateSlides, targetSlides, assets,
                    applied, skipped, assetParts, diagnostics);
            if (hasBlocking(diagnostics)) {
                return failedAndCleanup(diagnostics, executionDirectory, true);
            }
            beforeArtifactCommitHook.run();
            if (verifySourceIdentity(source, diagnostics, "executor.sourceChangedDuringExecution") == null) {
                return failedAndCleanup(diagnostics, executionDirectory, true);
            }
            pack.write(temporaryOutput);
            if (verifySourceIdentity(source, diagnostics, "executor.sourceChangedBeforeArtifactCommit") == null) {
                return failedAndCleanup(diagnostics, executionDirectory, true);
            }
            Files.move(temporaryOutput, artifact, StandardCopyOption.ATOMIC_MOVE);
            PptxBuildValidator.Result validation = buildValidator.validate(
                    artifact, request.plan(), applied, skipped, assetParts);
            diagnostics.addAll(validation.diagnostics());
            if (hasBlocking(diagnostics)) {
                return failedAndCleanup(diagnostics, executionDirectory, true);
            }
            FileIdentity artifactIdentity = identity(artifact);
            if (!artifactIdentity.sha256().equals(validation.artifactSha256())
                    || artifactIdentity.size() != validation.artifactSize()
                    || verifySourceIdentity(source, diagnostics, "executor.sourceChangedAfterArtifact") == null) {
                diagnostics.add(error(DiagnosticCode.PPTX_CHECKSUM_MISMATCH,
                        "executor.finalArtifactIdentityMismatch", null, null, null, null, null, Map.of()));
                return failedAndCleanup(diagnostics, executionDirectory, true);
            }
            writeArtifactManifest(executionDirectory, status(diagnostics), artifactIdentity, validation.slideCount());
            ExecutorModels.ArtifactReference artifactReference = new ExecutorModels.ArtifactReference(
                    artifact.toAbsolutePath().toString(), validation.artifactSha256(), validation.artifactSize(), validation.slideCount());
            return new ExecutionResult(
                    status(diagnostics), artifactReference,
                    new ExecutorModels.PreviewReference(false, "PREVIEW_NOT_RUN"), diagnostics,
                    new ExecutorModels.BuildValidationResult(
                            validation.diagnostics().isEmpty(), validation.slideCount(),
                            validation.artifactSha256(), validation.artifactSize()));
        } catch (Exception exception) {
            diagnostics.add(error(DiagnosticCode.PPTX_PACKAGE_INVALID,
                    "executor.executionFailed", null, null, null, null, null,
                    Map.of("reason", safeReason(exception.getMessage()))));
            return failedAndCleanup(diagnostics, executionDirectory, true);
        } finally {
            try {
                Files.deleteIfExists(workingCopy);
                Files.deleteIfExists(temporaryOutput);
            } catch (IOException ignored) {
                // The final artifact remains auditable; failed cleanup is not hidden in feedback details.
            }
        }
    }

    private ContractGateResult validateComposeInputs(
            CompositionModels.EngineComposePlanRequest request,
            List<ExecutorModels.ExecutorDiagnostic> target) {
        // The composition gate is injected through the package validator's already validated API below.
        // This local structural check prevents the executor from accepting a plan with a different input set.
        List<ExecutorModels.ExecutorDiagnostic> diagnostics = new ArrayList<>();
        if (request.specification() == null || request.templateProfile() == null
                || request.approvedAssetManifest() == null || request.generationJob() == null) {
            diagnostics.add(error(DiagnosticCode.EXECUTOR_INPUT_INVALID,
                    "executor.requiredInputMissing", null, null, null, null, null, Map.of()));
            target.addAll(diagnostics);
            return new ContractGateResult(null, diagnostics);
        }
        // Reconstruct the immutable package only after the checksum and status checks below.
        ContractGate.ComposeValidationResult composeResult = contractGate.validateCompose(request);
        List<ExecutorModels.ExecutorDiagnostic> gateDiagnostics = composeResult.diagnostics().stream()
                .map(this::fromPlanDiagnostic).toList();
        return new ContractGateResult(composeResult.executionPackage(), gateDiagnostics);
    }

    private CompositionPlanValidator.PartialPlanContext inferPartialPlanContext(
            ExecutorModels.ExecuteRequest request) {
        Set<String> incompleteBlocks = new HashSet<>();
        Set<String> incompleteAssets = new HashSet<>();
        Set<String> textOperations = new HashSet<>();
        Set<String> assetOperations = new HashSet<>();
        if (request == null || request.plan() == null) {
            return new CompositionPlanValidator.PartialPlanContext(incompleteBlocks, incompleteAssets);
        }
        for (CompositionModels.ComposedSlidePlan slide : request.plan().slides()) {
            for (CompositionModels.CompositionOperation operation : slide.operations()) {
                if (operation.operationType() == CompositionOperationType.FILL_TEXT_SLOT
                        && operation.blockId() != null) {
                    textOperations.add(operation.blockId());
                }
                if (operation.operationType() == CompositionOperationType.FILL_ASSET_SLOT
                        && operation.assetRequirementId() != null) {
                    assetOperations.add(operation.assetRequirementId());
                }
            }
        }
        for (ContractModels.LockedPptSlide slide : request.specification().slides()) {
            for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
                if (!textOperations.contains(block.blockId())) {
                    incompleteBlocks.add(block.blockId());
                }
            }
            for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
                CompositionModels.ApprovedAssetManifestEntry entry = request.approvedAssetManifest()
                        .entries().stream()
                        .filter(item -> asset.assetId().equals(item.assetRequirementId()))
                        .findFirst().orElse(null);
                if (entry != null && entry.resolution() == ContractTypes.AssetResolution.APPROVED_ASSET
                        && !assetOperations.contains(asset.assetId())) {
                    incompleteAssets.add(asset.assetId());
                }
            }
        }
        return new CompositionPlanValidator.PartialPlanContext(incompleteBlocks, incompleteAssets);
    }

    private void addMissingPlanDiagnostics(
            ExecutorModels.ExecuteRequest request,
            CompositionPlanValidator.PartialPlanContext partialContext,
            List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        for (ContractModels.LockedPptSlide slide : request.specification().slides()) {
            for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
                if (partialContext.incompleteBlockIds().contains(block.blockId())) {
                    diagnostics.add(new ExecutorModels.ExecutorDiagnostic(
                            DiagnosticCode.COMPONENT_MISSING.name(), DiagnosticSeverity.ERROR,
                            DiagnosticImpact.ARTIFACT_INCOMPLETE, null, slide.slideId(), slide.pageNumber(),
                            null, null, null, "executor.textSlotLeftBlank",
                            Map.of("blockId", block.blockId(), "whatWasGenerated", "none",
                                    "whatWasLeftBlank", "textSlot")));
                }
            }
            for (ContractModels.LockedPptAssetReference asset : slide.assetRequirements()) {
                if (partialContext.incompleteAssetRequirementIds().contains(asset.assetId())) {
                    diagnostics.add(new ExecutorModels.ExecutorDiagnostic(
                            DiagnosticCode.ASSET_MANIFEST_REQUIREMENT_MISSING.name(), DiagnosticSeverity.ERROR,
                            DiagnosticImpact.ARTIFACT_INCOMPLETE, null, slide.slideId(), slide.pageNumber(),
                            asset.assetId(), null, null, "executor.assetSlotLeftBlank",
                            Map.of("assetRequirementId", asset.assetId(), "whatWasGenerated", "none",
                                    "whatWasLeftBlank", "assetSlot")));
                }
            }
        }
    }

    private boolean validatePlanBinding(ExecutorModels.ExecuteRequest request,
                                        List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        CompositionModels.ComposedPresentationPlan plan = request.plan();
        if (plan == null || plan.planChecksum() == null
                || !plan.planChecksum().equals(checksumService.computePlan(plan))) {
            diagnostics.add(error(DiagnosticCode.PPTX_CHECKSUM_MISMATCH,
                    "executor.planChecksumMismatch", null, null, null, null, null, Map.of()));
            return false;
        }
        boolean sameJob = plan.generationJobReference() != null
                && request.generationJob().generationJobId().equals(plan.generationJobReference().generationJobId())
                && request.generationJob().executionAttemptId().equals(plan.generationJobReference().executionAttemptId())
                && request.generationJob().jobBindingChecksum().equals(plan.generationJobReference().jobBindingChecksum())
                && request.generationJob().engineBuildVersion().equals(plan.generationJobReference().engineBuildVersion())
                && request.generationJob().executorAdapterVersion().equals(plan.generationJobReference().executorAdapterVersion())
                && request.generationJob().fontEnvironmentVersion().equals(plan.generationJobReference().fontEnvironmentVersion());
        boolean sameSpec = plan.specificationReference() != null
                && request.specification().specificationId().equals(plan.specificationReference().specificationId())
                && request.specification().version() == plan.specificationReference().specificationVersion()
                && request.specification().checksum().equals(plan.specificationReference().specificationChecksum());
        boolean sameManifest = plan.approvedAssetManifestReference() != null
                && request.approvedAssetManifest().manifestId().equals(plan.approvedAssetManifestReference().manifestId())
                && request.approvedAssetManifest().manifestVersion() == plan.approvedAssetManifestReference().manifestVersion()
                && request.approvedAssetManifest().manifestChecksum().equals(plan.approvedAssetManifestReference().manifestChecksum());
        if (!sameJob || !sameSpec || !sameManifest) {
            diagnostics.add(error(DiagnosticCode.EXECUTOR_INPUT_INVALID,
                    "executor.planInputBindingMismatch", null, null, null, null, null, Map.of()));
        }
        return sameJob && sameSpec && sameManifest;
    }

    private FileIdentity validateSource(
            ExecutorModels.TemplateSourceBinding binding,
            ContractModels.ConfirmedTemplateProfile profile,
            List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        try {
            if (binding == null || binding.absolutePath() == null) {
                throw new IllegalArgumentException("missing path");
            }
            Path source = resolveInputPath(binding.absolutePath());
            if (!Files.isRegularFile(source) || !source.getFileName().toString().toLowerCase().endsWith(".pptx")) {
                throw new IllegalArgumentException("source file invalid");
            }
            if (!source.equals(source.toRealPath())) {
                throw new IllegalArgumentException("source symlink");
            }
            FileIdentity actual = identity(source);
            if (actual.size() != binding.size() || !actual.sha256().equals(binding.sha256())) {
                diagnostics.add(error(DiagnosticCode.TEMPLATE_SOURCE_HASH_MISMATCH,
                        "executor.sourceHashMismatch", null, null, null, null, null, Map.of()));
                return null;
            }
            if (binding.templateId() != null && !binding.templateId().equals(profile.templateId())
                    || binding.templateVersion() != null && binding.templateVersion() != profile.templateVersion()
                    || binding.sourceVersionId() != null && !binding.sourceVersionId().equals(profile.sourceVersionId())
                    || profile.sourceSha256() != null && !profile.sourceSha256().equalsIgnoreCase(actual.sha256())
                    || profile.sourceVersionId() != null && (binding.sourceVersionId() == null
                    || binding.templateId() == null || binding.templateVersion() == null)) {
                diagnostics.add(error(DiagnosticCode.TEMPLATE_SOURCE_HASH_MISMATCH,
                        "executor.templateSourceVersionMismatch", null, null, null, null, null, Map.of()));
                return null;
            }
            if (!actual.lastModified().toInstant().equals(Instant.parse(binding.lastModifiedUtc()))) {
                diagnostics.add(error(DiagnosticCode.TEMPLATE_SOURCE_METADATA_MISMATCH,
                        "executor.sourceMetadataMismatch", null, null, null, null, null, Map.of()));
                return null;
            }
            return actual;
        } catch (Exception exception) {
            diagnostics.add(error(DiagnosticCode.TEMPLATE_SOURCE_INVALID,
                    "executor.sourcePathInvalid", null, null, null, null, null,
                    Map.of("reason", safeReason(exception.getMessage()))));
            return null;
        }
    }

    private Map<String, FileIdentity> validateAssets(ExecutorModels.ExecuteRequest request,
                                                       List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        Map<String, FileIdentity> result = new HashMap<>();
        for (ExecutorModels.ApprovedAssetFile file : request.approvedAssetFiles()) {
            try {
                Path path = resolveInputPath(file.absolutePath());
                if (!Files.isRegularFile(path)
                        || !path.equals(path.toRealPath())) {
                    diagnostics.add(error(DiagnosticCode.APPROVED_ASSET_FILE_HASH_MISMATCH,
                            "executor.assetFileHashMismatch", null, null, file.assetRequirementId(), null, null, Map.of()));
                    continue;
                }
                FileIdentity actual = identity(path);
                if (actual.size() != file.size()
                        || !actual.sha256().equalsIgnoreCase(file.sha256())
                        || !actual.lastModified().toInstant().equals(Instant.parse(file.lastModifiedUtc()))) {
                    diagnostics.add(error(DiagnosticCode.APPROVED_ASSET_FILE_HASH_MISMATCH,
                            "executor.assetFileMetadataMismatch", null, null, file.assetRequirementId(), null, null, Map.of()));
                    continue;
                }
                CompositionModels.ApprovedAssetManifestEntry entry = request.approvedAssetManifest().entries().stream()
                        .filter(item -> file.assetRequirementId().equals(item.assetRequirementId())
                                && file.approvedAssetId().equals(item.approvedAssetId()))
                        .findFirst().orElse(null);
                if (entry == null
                        || !file.sha256().equalsIgnoreCase(entry.contentSha256())
                        || !file.absolutePath().equals(entry.storageKey())
                        || file.size() != entry.fileSize()
                        || entry.lastModifiedUtc() == null
                        || !Instant.parse(file.lastModifiedUtc()).equals(entry.lastModifiedUtc().toInstant())) {
                    diagnostics.add(error(DiagnosticCode.APPROVED_ASSET_FILE_INVALID,
                            "executor.assetFileBindingMismatch", null, null, file.assetRequirementId(), null, null, Map.of()));
                    continue;
                }
                if (result.putIfAbsent(file.assetRequirementId(),
                        actual) != null) {
                    diagnostics.add(error(DiagnosticCode.APPROVED_ASSET_FILE_INVALID,
                            "executor.assetFileDuplicate", null, null, file.assetRequirementId(), null, null, Map.of()));
                }
            } catch (Exception exception) {
                diagnostics.add(error(DiagnosticCode.APPROVED_ASSET_FILE_INVALID,
                        "executor.assetFileInvalid", null, null, file.assetRequirementId(), null, null, Map.of()));
            }
        }
        return result;
    }

    private Path resolveInputPath(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storage key is missing");
        }
        Path requested = Path.of(storageKey);
        if (inputRoot == null) {
            if (!allowLegacyAbsolutePaths || !requested.isAbsolute()
                    || !requested.equals(requested.normalize())) {
                throw new IllegalArgumentException("controlled input root is not configured");
            }
            return requested.toAbsolutePath().normalize();
        }
        if (requested.isAbsolute() && allowLegacyAbsolutePaths) {
            return requested.toAbsolutePath().normalize();
        }
        if (requested.isAbsolute() || storageKey.indexOf('\\') >= 0
                || storageKey.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("storage key must be relative");
        }
        Path resolved = inputRoot.resolve(requested).normalize();
        if (!resolved.startsWith(inputRoot)) {
            throw new IllegalArgumentException("storage key escaped controlled input root");
        }
        return resolved;
    }

    private boolean validateTemplateSourceStructure(
            ExecutorModels.ExecuteRequest request,
            List<String> templateSlides,
            List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        int sourceCount = templateSlides.size();
        for (CompositionModels.ComposedSlidePlan slide : request.plan().slides()) {
            int selectedSourceSlide = slide.layout().templatePage().sourceSlide();
            if (selectedSourceSlide < 1 || selectedSourceSlide > sourceCount) {
                diagnostics.add(error(DiagnosticCode.SAME_PACKAGE_REFERENCE_INVALID,
                        "executor.templatePageSourceSlideMissing", slide.slideId(), slide.pageNumber(),
                        null, null, null, Map.of("sourceSlide", Integer.toString(selectedSourceSlide))));
            }
        }
        for (ContractModels.TemplateComponent component : request.templateProfile().components()) {
            if (component.sourceSlide() < 1 || component.sourceSlide() > sourceCount) {
                diagnostics.add(error(DiagnosticCode.SAME_PACKAGE_REFERENCE_INVALID,
                        "executor.componentSourceSlideMissing", null, null, null,
                        component.componentId(), null,
                        Map.of("sourceSlide", Integer.toString(component.sourceSlide()))));
            }
        }
        return !hasBlocking(diagnostics);
    }

    private void executeOperations(
            ExecutorModels.ExecuteRequest request,
            PptxPackage pack,
            List<String> templateSlidePaths,
            List<String> targetSlidePaths,
            Map<String, FileIdentity> assets,
            Set<String> applied,
            Set<String> skipped,
            Map<String, String> assetParts,
            List<ExecutorModels.ExecutorDiagnostic> diagnostics) throws IOException {
        Map<String, ContractModels.TemplateComponent> components = new HashMap<>();
        for (ContractModels.TemplateComponent component : request.templateProfile().components()) {
            components.put(component.componentId(), component);
        }
        Map<String, ContractModels.LockedPptContentBlock> blocks = new HashMap<>();
        for (ContractModels.LockedPptSlide slide : request.specification().slides()) {
            for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
                blocks.put(block.blockId(), block);
            }
        }
        if (targetSlidePaths.size() != request.plan().slides().size()) {
            throw new IllegalStateException("materialized slide count");
        }
        for (CompositionModels.ComposedSlidePlan plannedSlide : request.plan().slides()) {
            int targetIndex = plannedSlide.pageNumber() - 1;
            if (targetIndex < 0 || targetIndex >= targetSlidePaths.size()) {
                throw new IllegalStateException("plan page number");
            }
            String targetPath = targetSlidePaths.get(targetIndex);
            Document target = pack.document(targetPath);
            for (CompositionModels.CompositionOperation operation : plannedSlide.operations()) {
                try {
                    boolean handled = executeOperation(request, pack, templateSlidePaths, targetPath, target,
                            operation, components, blocks, assets, assetParts, diagnostics);
                    if (handled) {
                        applied.add(operation.operationId());
                    } else {
                        skipped.add(operation.operationId());
                    }
                } catch (UnsupportedOperationException unsupported) {
                    skipped.add(operation.operationId());
                    diagnostics.add(localFailure(request, operation, blocks, components,
                            DiagnosticCode.NATIVE_OBJECT_NOT_SUPPORTED, "executor.nativeObjectNotSupported"));
                } catch (ContentTypesException contentTypesException) {
                    skipped.add(operation.operationId());
                    diagnostics.add(error(DiagnosticCode.PPTX_CONTENT_TYPES_INVALID,
                            "executor.contentTypesInvalid", operation,
                            Map.of("reason", safeReason(contentTypesException.getMessage()))));
                } catch (Exception exception) {
                    skipped.add(operation.operationId());
                    DiagnosticCode code = exception instanceof TransformConstraintException
                            ? DiagnosticCode.TRANSFORM_NOT_ALLOWED
                            : DiagnosticCode.SAME_PACKAGE_REFERENCE_INVALID;
                    diagnostics.add(localFailure(request, operation, blocks, components, code,
                            "executor.operationFailed",
                            Map.of("exceptionType", exception.getClass().getSimpleName(),
                                    "reason", safeReason(exception.getMessage()))));
                }
            }
            pack.saveDocument(targetPath, target);
        }
    }

    private boolean executeOperation(
            ExecutorModels.ExecuteRequest request,
            PptxPackage pack,
            List<String> templateSlidePaths,
            String targetPath,
            Document target,
            CompositionModels.CompositionOperation operation,
            Map<String, ContractModels.TemplateComponent> components,
            Map<String, ContractModels.LockedPptContentBlock> blocks,
            Map<String, FileIdentity> assets,
            Map<String, String> assetParts,
            List<ExecutorModels.ExecutorDiagnostic> diagnostics) throws Exception {
        CompositionOperationType type = operation.operationType();
        if (type == CompositionOperationType.PRESERVE_BASE_OBJECT) {
            requireReference(request, pack, templateSlidePaths, target, targetPath, operation);
            return true;
        }
        ContractModels.TemplateComponent component = components.get(operation.componentId());
        if (component == null || component.shapeRefs().isEmpty()) {
            throw new UnsupportedOperationException("component");
        }
        ContractModels.StableObjectReference objectRef;
        if (type == CompositionOperationType.USE_OR_CLONE_COMPONENT_OBJECT) {
            validateNativeReference(request, pack, templateSlidePaths, target, targetPath, operation, component);
            CompositionModels.StableNativeObjectReference reference = operation.nativeObjectReference();
            objectRef = new ContractModels.StableObjectReference(reference.objectType(), reference.objectId());
        } else {
            objectRef = resolveSlotObjectReference(pack, target, component, type);
        }
        Element object = findOrCloneObject(request, pack, templateSlidePaths, target, targetPath, operation,
                objectRef, component.sourceSlide());
        if (type == CompositionOperationType.USE_OR_CLONE_COMPONENT_OBJECT) {
            if (operation.nativeObjectReference() == null || operation.nativeObjectReference().objectType() == ObjectType.UNKNOWN) {
                throw new UnsupportedOperationException("unknown object type");
            }
            if (operation.componentObjectAction() == ComponentObjectAction.USE_EXISTING_OBJECT
                    && object == null) {
                throw new IllegalStateException("native object not found");
            }
            if (object != null && (operation.bounds() != null
                    || operation.requestedTransform() != null
                    || operation.allowedTransform() != null)) {
                applyTransform(object, operation.bounds(), operation.requestedTransform(), operation.allowedTransform());
            }
            return object != null;
        }
        if (object == null) {
            throw new IllegalStateException("slot object not found");
        }
        if (type == CompositionOperationType.FILL_TEXT_SLOT) {
            ContractModels.LockedPptContentBlock block = blocks.get(operation.blockId());
            if (block == null || !operation.contentSha256().equals(checksumService.sha256Utf8(block.content()))) {
                throw new IllegalStateException("text binding");
            }
            if (!"sp".equals(object.getLocalName())) {
                throw new UnsupportedOperationException("text object");
            }
            List<Element> textNodes = PptxPackage.descendants(object, IMAGE_NS, "t");
            if (textNodes.isEmpty()) {
                throw new UnsupportedOperationException("text node");
            }
            textNodes.get(0).setTextContent(block.content());
            for (int index = 1; index < textNodes.size(); index++) {
                textNodes.get(index).setTextContent("");
            }
            applyTransform(object, operation.bounds(), operation.requestedTransform(), operation.allowedTransform());
            return true;
        }
        if (type == CompositionOperationType.FILL_ASSET_SLOT) {
            FileIdentity asset = assets.get(operation.assetRequirementId());
            if (asset == null || object.getLocalName() == null || !"pic".equals(object.getLocalName())) {
                throw new UnsupportedOperationException("picture slot");
            }
            if (operation.assetType() != ContractTypes.AssetType.IMAGE) {
                throw new UnsupportedOperationException("asset type");
            }
            byte[] assetBytes = readVerifiedAsset(asset);
            String partName = "ppt/media/a12-approved-" + safeId(operation.approvedAssetId())
                    + "-" + operation.contentSha256().substring(0, 12)
                    + "." + PptxPackage.localExtension(asset.path().getFileName().toString());
            if (pack.entryNames().contains(partName)
                    && !java.util.Arrays.equals(pack.bytes(partName), assetBytes)) {
                throw new IllegalStateException("asset package collision");
            }
            pack.put(partName, assetBytes);
            String relsPath = pack.relsPath(targetPath);
            Document rels = pack.document(relsPath);
            String relationshipId = PptxPackage.ensureRelationship(
                    rels, IMAGE_REL_TYPE,
                    PptxPackage.relativeTarget(targetPath, partName), null, null);
            pack.saveDocument(relsPath, rels);
            Element blip = PptxPackage.descendants(object, IMAGE_NS, "blip").stream().findFirst().orElseThrow();
            blip.setAttributeNS(PptxPackage.R_NS, "r:embed", relationshipId);
            addContentType(pack, partName, PptxPackage.localExtension(asset.path().getFileName().toString()));
            assetParts.put(operation.assetRequirementId(), partName);
            applyTransform(object, operation.bounds(), operation.requestedTransform(), operation.allowedTransform());
            return true;
        }
        throw new UnsupportedOperationException("operation");
    }

    private ContractModels.StableObjectReference resolveSlotObjectReference(
            PptxPackage pack, Document target, ContractModels.TemplateComponent component,
            CompositionOperationType type) {
        String expectedLocalName = type == CompositionOperationType.FILL_TEXT_SLOT ? "sp" : "pic";
        List<ContractModels.StableObjectReference> matches = component.shapeRefs().stream()
                .filter(reference -> {
                    Element object = pack.findObject(target, reference.objectId());
                    boolean expectedType = type == CompositionOperationType.FILL_TEXT_SLOT
                            ? reference.objectType() == ObjectType.TEXT || reference.objectType() == ObjectType.SHAPE
                            : reference.objectType() == ObjectType.PICTURE;
                    return expectedType && object != null && expectedLocalName.equals(object.getLocalName());
                })
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("slot native object is not uniquely identified");
        }
        return matches.get(0);
    }

    private Element findOrCloneObject(
            ExecutorModels.ExecuteRequest request,
            PptxPackage pack,
            List<String> templateSlidePaths,
            Document target,
            String targetPath,
            CompositionModels.CompositionOperation operation,
            ContractModels.StableObjectReference objectRef,
            int sourceSlide) throws Exception {
        if (operation.componentObjectAction() == null) {
            Element slotObject = pack.findObject(target, objectRef.objectId());
            if (slotObject != null && matchesObjectType(slotObject, objectRef.objectType())) {
                return slotObject;
            }
            throw new IllegalStateException("slot native object identity mismatch");
        }
        if (operation.componentObjectAction() == ComponentObjectAction.USE_EXISTING_OBJECT) {
            Element existing = pack.findObject(target, objectRef.objectId());
            if (existing != null && matchesObjectType(existing, objectRef.objectType())) {
                return existing;
            }
            throw new IllegalStateException("native target object identity mismatch");
        }
        if (operation.nativeObjectReference() == null
                || operation.componentObjectAction() != ComponentObjectAction.CLONE_FROM_SOURCE) {
            throw new UnsupportedOperationException("clone type");
        }
        if (sourceSlide < 1 || sourceSlide > templateSlidePaths.size()) {
            throw new IllegalStateException("source slide");
        }
        String sourcePath = templateSlidePaths.get(sourceSlide - 1);
        Document source = pack.document(sourcePath);
        Element sourceObject = pack.findObject(source, objectRef.objectId());
        if (sourceObject == null || !matchesObjectType(sourceObject, objectRef.objectType())) {
            throw new UnsupportedOperationException("source object");
        }
        Node imported = target.importNode(sourceObject, true);
        Element clone = (Element) imported;
        Element cNvPr = PptxPackage.descendants(clone, "*", "cNvPr").stream().findFirst().orElseThrow();
        if (PptxPackage.findObject(target, cNvPr.getAttribute("id")) != null) {
            throw new IllegalStateException("cloned native object ID collides with target");
        }
        remapNestedNativeIds(target, clone, cNvPr);
        copyRelationships(pack, sourcePath, targetPath, sourceObject, clone);
        cNvPr.setAttribute("name", cNvPr.getAttribute("name") + " (same-package clone)");
        Element tree = PptxPackage.elements(target, "http://schemas.openxmlformats.org/presentationml/2006/main", "spTree")
                .stream().findFirst().orElseThrow();
        tree.appendChild(clone);
        return clone;
    }

    private void requireReference(
            ExecutorModels.ExecuteRequest request,
            PptxPackage pack,
            List<String> templateSlidePaths,
            Document target,
            String targetPath,
            CompositionModels.CompositionOperation operation) throws IOException {
        CompositionModels.StableNativeObjectReference reference = operation.nativeObjectReference();
        if (reference == null || !ContractTypes.STABLE_NATIVE_OBJECT_REFERENCE_V1.equals(reference.referenceVersion())
                || !request.templateProfile().templateId().equals(reference.templateId())
                || request.templateProfile().templateVersion() != reference.templateVersion()
                || reference.scope() != ContractTypes.NativeObjectScope.SLIDE
                || reference.objectId() == null || reference.objectId().isBlank()) {
            throw new IllegalStateException("same-package reference");
        }
        if (reference.sourceSlide() < 1 || reference.sourceSlide() > templateSlidePaths.size()
                || pack.findObject(pack.document(templateSlidePaths.get(reference.sourceSlide() - 1)),
                reference.objectId()) == null
                || pack.findObject(target, reference.objectId()) == null) {
            throw new IllegalStateException("native object missing");
        }
    }

    private void validateNativeReference(
            ExecutorModels.ExecuteRequest request,
            PptxPackage pack,
            List<String> templateSlidePaths,
            Document target,
            String targetPath,
            CompositionModels.CompositionOperation operation,
            ContractModels.TemplateComponent component) throws IOException {
        CompositionModels.StableNativeObjectReference reference = operation.nativeObjectReference();
        if (reference == null || !ContractTypes.STABLE_NATIVE_OBJECT_REFERENCE_V1.equals(reference.referenceVersion())
                || !request.templateProfile().templateId().equals(reference.templateId())
                || request.templateProfile().templateVersion() != reference.templateVersion()
                || reference.scope() != ContractTypes.NativeObjectScope.SLIDE
                || reference.sourceSlide() < 1
                || reference.objectId() == null || reference.objectId().isBlank()
                || reference.objectType() == null || reference.objectType() == ObjectType.UNKNOWN
                || component.sourceSlide() != reference.sourceSlide()
                || component.shapeRefs().stream().noneMatch(shape ->
                shape.objectId().equals(reference.objectId()) && shape.objectType() == reference.objectType())) {
            throw new IllegalStateException("cross-package native reference");
        }
        if (reference.sourceSlide() < 1 || reference.sourceSlide() > templateSlidePaths.size()) {
            throw new IllegalStateException("native source slide");
        }
        Document source = pack.document(templateSlidePaths.get(reference.sourceSlide() - 1));
        Element sourceObject = pack.findObject(source, reference.objectId());
        if (sourceObject == null || !matchesObjectType(sourceObject, reference.objectType())) {
            throw new IllegalStateException("native object identity mismatch");
        }
        if (operation.componentObjectAction() == ComponentObjectAction.USE_EXISTING_OBJECT) {
            Element targetObject = pack.findObject(target, reference.objectId());
            if (targetObject == null || !matchesObjectType(targetObject, reference.objectType())) {
                throw new IllegalStateException("native target object identity mismatch");
            }
        }
    }

    /** Keep the source object's stable top-level ID so later slot-fill operations
     * can address the clone through the Profile's confirmed shape reference. */
    private void remapNestedNativeIds(Document target, Element clone, Element topLevelObjectProperties) {
        Set<String> used = new HashSet<>();
        for (Element existing : PptxPackage.elements(target, "*", "cNvPr")) {
            used.add(existing.getAttribute("id"));
        }
        String topId = topLevelObjectProperties.getAttribute("id");
        used.add(topId);
        long nextNumericId = 0;
        for (String id : used) {
            try {
                nextNumericId = Math.max(nextNumericId, Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                // Real PowerPoint IDs are numeric; synthetic fixtures may use
                // readable IDs which do not affect fresh numeric allocation.
            }
        }
        boolean first = true;
        for (Element cNvPr : PptxPackage.descendants(clone, "*", "cNvPr")) {
            if (first) {
                first = false;
                continue;
            }
            String id = cNvPr.getAttribute("id");
            if (id.isBlank() || !used.add(id)) {
                String replacement;
                do {
                    replacement = Long.toString(++nextNumericId);
                } while (!used.add(replacement));
                cNvPr.setAttribute("id", replacement);
            }
        }
    }

    /**
     * Rebinds every relationship attribute carried by a cloned native object.
     * A copied XML subtree cannot retain the source slide's rId values: those
     * IDs are scoped to the target slide relationship part.  Internal targets
     * remain inside this one OPC package; external relationships are preserved
     * as external relationships already present on the teacher template.
     */
    private void copyRelationships(
            PptxPackage pack,
            String sourcePath,
            String targetPath,
            Element sourceObject,
            Element clone) throws IOException {
        Document sourceRels = pack.document(pack.relsPath(sourcePath));
        Document targetRels = pack.document(pack.relsPath(targetPath));
        rebindRelationshipAttributes(pack, sourceRels, targetRels, sourcePath, targetPath, sourceObject, clone);
        pack.saveDocument(pack.relsPath(targetPath), targetRels);
    }

    private void rebindRelationshipAttributes(
            PptxPackage pack,
            Document sourceRels,
            Document targetRels,
            String sourcePath,
            String targetPath,
            Node sourceNode,
            Node targetNode) throws IOException {
        if (sourceNode.getNodeType() == Node.ELEMENT_NODE) {
            var attributes = sourceNode.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                var sourceAttribute = attributes.item(index);
                if (!PptxPackage.R_NS.equals(sourceAttribute.getNamespaceURI())
                        || sourceAttribute.getNodeValue() == null
                        || sourceAttribute.getNodeValue().isBlank()) {
                    continue;
                }
                Element relationship = PptxPackage.relationship(sourceRels, sourceAttribute.getNodeValue());
                if (relationship == null) {
                    throw new IOException("native object relationship is missing");
                }
                String targetValue = relationship.getAttribute("Target");
                String reboundTarget = targetValue;
                if (!"External".equals(relationship.getAttribute("TargetMode"))) {
                    String packageTarget;
                    try {
                        packageTarget = PptxPackage.resolveTarget(sourcePath, targetValue);
                    } catch (IllegalArgumentException exception) {
                        throw new IOException("native object relationship target is unsafe", exception);
                    }
                    if (!pack.entryNames().contains(packageTarget)) {
                        throw new IOException("native object relationship target is missing");
                    }
                    reboundTarget = PptxPackage.relativeTarget(targetPath, packageTarget);
                }
                String newRelationshipId = PptxPackage.ensureRelationship(
                        targetRels, relationship.getAttribute("Type"), reboundTarget,
                        relationship.getAttribute("TargetMode"), relationship);
                ((Element) targetNode).setAttributeNS(
                        sourceAttribute.getNamespaceURI(), sourceAttribute.getNodeName(), newRelationshipId);
            }
        }
        Node sourceChild = sourceNode.getFirstChild();
        Node targetChild = targetNode.getFirstChild();
        while (sourceChild != null && targetChild != null) {
            rebindRelationshipAttributes(pack, sourceRels, targetRels, sourcePath, targetPath,
                    sourceChild, targetChild);
            sourceChild = sourceChild.getNextSibling();
            targetChild = targetChild.getNextSibling();
        }
    }

    private boolean matchesObjectType(Element object, ObjectType type) {
        return switch (type) {
            case SHAPE -> "sp".equals(object.getLocalName());
            case TEXT -> "sp".equals(object.getLocalName())
                    && !PptxPackage.descendants(object, IMAGE_NS, "t").isEmpty();
            case PICTURE -> "pic".equals(object.getLocalName());
            case GROUP -> "grpSp".equals(object.getLocalName());
            case CHART, TABLE -> "graphicFrame".equals(object.getLocalName());
            case UNKNOWN -> false;
        };
    }

    static void applyTransform(Element object, ContractModels.Bounds bounds,
                                 ContractTypes.TransformConstraint requested,
                                 ContractTypes.TransformConstraint allowed) {
        PptxTransformPolicy.apply(object, bounds, requested, allowed);
    }

    private void addContentType(PptxPackage pack, String partName, String extension) throws Exception {
        String path = "[Content_Types].xml";
        Document contentTypes = pack.document(path);
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        String contentType = switch (normalizedExtension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> throw new ContentTypesException("image extension");
        };
        for (Element override : PptxPackage.elements(contentTypes, PptxPackage.CT_NS, "Override")) {
            if (partName.equals(override.getAttribute("PartName").replaceFirst("^/", ""))) {
                if (!contentType.equals(override.getAttribute("ContentType"))) {
                    throw new ContentTypesException("part content type conflict");
                }
                return;
            }
        }
        List<Element> defaults = PptxPackage.elements(contentTypes, PptxPackage.CT_NS, "Default");
        Element matching = null;
        for (Element defaultNode : defaults) {
            if (normalizedExtension.equals(defaultNode.getAttribute("Extension").trim().toLowerCase(Locale.ROOT))) {
                if (matching != null || !contentType.equals(defaultNode.getAttribute("ContentType"))) {
                    throw new ContentTypesException("duplicate or conflicting default");
                }
                matching = defaultNode;
            }
        }
        if (matching == null) {
            Element defaultNode = contentTypes.createElementNS(PptxPackage.CT_NS, "Default");
            defaultNode.setAttribute("Extension", normalizedExtension);
            defaultNode.setAttribute("ContentType", contentType);
            contentTypes.getDocumentElement().appendChild(defaultNode);
        }
        pack.saveDocument(path, contentTypes);
    }

    private AttemptDirectory prepareAttemptDirectory(
            ExecutorModels.ExecuteRequest request,
            FileIdentity source,
            Map<String, FileIdentity> assets,
            String executionId,
            List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        try {
            Path root = safeOutputRoot();
            Path directory = root.resolve(executionId).normalize();
            if (!root.equals(directory.getParent())) {
                throw new IOException("attempt path escaped output root");
            }
            String binding = attemptBinding(request, source, assets);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new AttemptConflictException("attempt directory is not a real directory");
                }
                Path bindingFile = directory.resolve("attempt-binding.txt");
                if (!Files.isRegularFile(bindingFile, LinkOption.NOFOLLOW_LINKS)
                        || !binding.equals(Files.readString(bindingFile))) {
                    throw new AttemptConflictException("attempt binding conflict");
                }
                Path artifact = directory.resolve("generation-result.pptx");
                Path manifest = directory.resolve("artifact-manifest.txt");
                if (Files.isSymbolicLink(artifact) || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    throw new AttemptConflictException("existing attempt is incomplete");
                }
                ArtifactManifest saved = readArtifactManifest(manifest);
                FileIdentity actual = identity(artifact);
                if (!actual.sha256().equals(saved.sha256()) || actual.size() != saved.size()) {
                    throw new AttemptConflictException("existing artifact identity changed");
                }
                return new AttemptDirectory(directory, false, saved.status(), saved.slideCount());
            }
            Files.createDirectory(directory);
            Files.writeString(directory.resolve("attempt-binding.txt"), binding,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return new AttemptDirectory(directory, true, null, 0);
        } catch (AttemptConflictException conflict) {
            diagnostics.add(error(DiagnosticCode.EXECUTOR_INPUT_INVALID,
                    "executor.attemptConflict", null, null, null, null, null,
                    Map.of("reason", safeReason(conflict.getMessage()))));
            return null;
        } catch (Exception exception) {
            diagnostics.add(error(DiagnosticCode.EXECUTOR_INPUT_INVALID,
                    "executor.outputRootInvalid", null, null, null, null, null, Map.of(
                            "reason", safeReason(exception.getMessage()))));
            return null;
        }
    }

    private Path safeOutputRoot() throws IOException {
        if (Files.isSymbolicLink(outputRoot)) {
            throw new IOException("output root is a symbolic link");
        }
        Files.createDirectories(outputRoot);
        if (Files.isSymbolicLink(outputRoot) || !Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("output root is not a real directory");
        }
        Path real = outputRoot.toRealPath();
        if (!real.equals(outputRoot)) {
            throw new IOException("output root resolves outside its configured path");
        }
        return real;
    }

    private String attemptBinding(
        ExecutorModels.ExecuteRequest request,
            FileIdentity source,
            Map<String, FileIdentity> assets) {
        StringBuilder value = new StringBuilder();
        value.append(request.requestId()).append('\n')
                .append(request.generationJob().generationJobId()).append('\n')
                .append(request.generationJob().executionAttemptId()).append('\n')
                .append(request.generationJob().jobBindingChecksum()).append('\n')
                .append(request.plan().planChecksum()).append('\n')
                .append(request.approvedAssetManifest().manifestChecksum()).append('\n')
                .append(source.path()).append('|').append(source.size()).append('|')
                .append(source.sha256()).append('|').append(source.lastModified().toInstant()).append('\n');
        assets.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> value
                .append(entry.getKey()).append('|').append(entry.getValue().path()).append('|')
                .append(entry.getValue().size()).append('|').append(entry.getValue().sha256()).append('|')
                .append(entry.getValue().lastModified().toInstant()).append('\n'));
        return value.toString();
    }

    private void writeAttemptIdentity(Path directory, FileIdentity source, Path workingCopy) throws Exception {
        FileIdentity working = identity(workingCopy);
        String contents = "source=" + source.path() + "|" + source.size() + "|" + source.sha256() + "|"
                + source.lastModified().toInstant() + "\nworking-copy=" + working.path() + "|"
                + working.size() + "|" + working.sha256() + "|" + working.lastModified().toInstant() + "\n";
        Files.writeString(directory.resolve("input-identity.txt"), contents,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void writeArtifactManifest(
            Path directory, ContractTypes.GenerationJobStatus status,
            FileIdentity identity, int slideCount) throws IOException {
        String contents = "status=" + status.name() + "\nsha256=" + identity.sha256()
                + "\nsize=" + identity.size() + "\nslideCount=" + slideCount + "\n";
        Files.writeString(directory.resolve("artifact-manifest.txt"), contents,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private ArtifactManifest readArtifactManifest(Path path) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        try {
            return new ArtifactManifest(
                    ContractTypes.GenerationJobStatus.valueOf(values.get("status")),
                    values.get("sha256"), Long.parseLong(values.get("size")),
                    Integer.parseInt(values.get("slideCount")));
        } catch (Exception exception) {
            throw new IOException("invalid artifact manifest", exception);
        }
    }

    private ExecutionResult idempotentResult(
            Path directory, List<ExecutorModels.ExecutorDiagnostic> diagnostics) throws RuntimeException {
        try {
            Path artifact = directory.resolve("generation-result.pptx");
            ArtifactManifest manifest = readArtifactManifest(directory.resolve("artifact-manifest.txt"));
            return new ExecutionResult(manifest.status(),
                    new ExecutorModels.ArtifactReference(artifact.toAbsolutePath().toString(),
                            manifest.sha256(), manifest.size(), manifest.slideCount()),
                    new ExecutorModels.PreviewReference(false, "IDEMPOTENT_EXISTING_RESULT"), diagnostics,
                    new ExecutorModels.BuildValidationResult(
                            true, manifest.slideCount(), manifest.sha256(), manifest.size()));
        } catch (IOException exception) {
            diagnostics.add(error(DiagnosticCode.EXECUTOR_INPUT_INVALID,
                    "executor.attemptConflict", null, null, null, null, null, Map.of()));
            return failed(diagnostics);
        }
    }

    private FileIdentity verifySourceIdentity(
            FileIdentity expected, List<ExecutorModels.ExecutorDiagnostic> diagnostics, String messageKey) {
        try {
            FileIdentity actual = identity(expected.path());
            if (!sameIdentity(actual, expected)) {
                diagnostics.add(error(DiagnosticCode.TEMPLATE_SOURCE_HASH_MISMATCH,
                        messageKey, null, null, null, null, null, Map.of()));
                return null;
            }
            return actual;
        } catch (Exception exception) {
            diagnostics.add(error(DiagnosticCode.TEMPLATE_SOURCE_INVALID,
                    messageKey, null, null, null, null, null, Map.of(
                            "reason", safeReason(exception.getMessage()))));
            return null;
        }
    }

    private boolean sameIdentity(FileIdentity left, FileIdentity right) {
        return left.path().equals(right.path()) && left.size() == right.size()
                && left.sha256().equals(right.sha256())
                && left.lastModified().equals(right.lastModified());
    }

    private boolean samePayload(FileIdentity left, FileIdentity right) {
        return left.size() == right.size() && left.sha256().equals(right.sha256());
    }

    private FileIdentity identity(Path path) throws Exception {
        return new FileIdentity(path.toRealPath(), Files.size(path), sha256(path),
                Files.getLastModifiedTime(path));
    }

    private byte[] readVerifiedAsset(FileIdentity expected) throws Exception {
        if (!sameIdentity(identity(expected.path()), expected)) {
            throw new IllegalStateException("asset changed before read");
        }
        byte[] bytes = Files.readAllBytes(expected.path());
        if (!expected.sha256().equals(sha256(bytes))) {
            throw new IllegalStateException("asset bytes changed");
        }
        if (!sameIdentity(identity(expected.path()), expected)) {
            throw new IllegalStateException("asset changed after read");
        }
        return bytes;
    }

    private ExecutorModels.ExecutorDiagnostic localFailure(
            ExecutorModels.ExecuteRequest request,
            CompositionModels.CompositionOperation operation,
            Map<String, ContractModels.LockedPptContentBlock> blocks,
            Map<String, ContractModels.TemplateComponent> components,
            DiagnosticCode code, String key) {
        boolean required = operationRequired(request, operation, blocks, components);
        return required ? partial(operation, code, key)
                : new ExecutorModels.ExecutorDiagnostic(code.name(), DiagnosticSeverity.WARNING,
                DiagnosticImpact.NON_BLOCKING, operation.operationId(), null, null,
                operation.assetRequirementId(), operation.componentId(), operation.slotId(), key, Map.of());
    }

    private ExecutorModels.ExecutorDiagnostic localFailure(
            ExecutorModels.ExecuteRequest request,
            CompositionModels.CompositionOperation operation,
            Map<String, ContractModels.LockedPptContentBlock> blocks,
            Map<String, ContractModels.TemplateComponent> components,
            DiagnosticCode code, String key, Map<String, String> details) {
        boolean required = operationRequired(request, operation, blocks, components);
        if (code == DiagnosticCode.SAME_PACKAGE_REFERENCE_INVALID
                || code == DiagnosticCode.CROSS_PACKAGE_REFERENCE_FORBIDDEN) {
            return error(code, key, operation, details);
        }
        return required
                ? new ExecutorModels.ExecutorDiagnostic(code.name(), DiagnosticSeverity.ERROR,
                DiagnosticImpact.ARTIFACT_INCOMPLETE, operation.operationId(), null, null,
                operation.assetRequirementId(), operation.componentId(), operation.slotId(), key, details)
                : new ExecutorModels.ExecutorDiagnostic(code.name(), DiagnosticSeverity.WARNING,
                DiagnosticImpact.NON_BLOCKING, operation.operationId(), null, null,
                operation.assetRequirementId(), operation.componentId(), operation.slotId(), key, details);
    }

    private boolean operationRequired(
            ExecutorModels.ExecuteRequest request,
            CompositionModels.CompositionOperation operation,
            Map<String, ContractModels.LockedPptContentBlock> blocks,
            Map<String, ContractModels.TemplateComponent> components) {
        if (operation.operationType() == CompositionOperationType.PRESERVE_BASE_OBJECT) {
            return true;
        }
        ContractModels.TemplateComponent component = components.get(operation.componentId());
        if (component != null && operation.slotId() != null) {
            return component.slots().stream()
                    .filter(slot -> operation.slotId().equals(slot.slotId()))
                    .findFirst()
                    .map(ContractModels.ComponentSlot::required)
                    .orElse(true);
        }
        if (operation.operationType() == CompositionOperationType.FILL_TEXT_SLOT) {
            ContractModels.LockedPptContentBlock block = blocks.get(operation.blockId());
            return block == null || block.locked();
        }
        if (operation.operationType() == CompositionOperationType.FILL_ASSET_SLOT) {
            return request.specification().slides().stream()
                    .flatMap(slide -> slide.assetRequirements().stream())
                    .filter(asset -> operation.assetRequirementId().equals(asset.assetId()))
                    .findFirst().map(ContractModels.LockedPptAssetReference::required).orElse(true);
        }
        if (component == null) {
            return true;
        }
        return component.slots().stream()
                .filter(slot -> operation.slotId() != null && operation.slotId().equals(slot.slotId()))
                .findFirst().map(ContractModels.ComponentSlot::required)
                .orElseGet(() -> component.slots().stream().anyMatch(ContractModels.ComponentSlot::required));
    }

    private ExecutorModels.ExecutorDiagnostic fromPlanDiagnostic(ContractModels.Diagnostic diagnostic) {
        var classified = CompositionDiagnosticClassifier.classify(List.of(diagnostic)).get(0);
        return new ExecutorModels.ExecutorDiagnostic(
                classified.code(), classified.severity(), classified.impact(),
                null, classified.slideId(), classified.pageNumber(), classified.assetRequirementId(),
                classified.componentId(), classified.slotId(), classified.messageKey(), classified.safeDetails());
    }

    private ExecutorModels.ExecutorDiagnostic error(
            DiagnosticCode code, String key, String slideId, Integer page,
            String asset, String component, String slot, Map<String, String> details) {
        return new ExecutorModels.ExecutorDiagnostic(code.name(), DiagnosticSeverity.ERROR,
                DiagnosticImpact.JOB_BLOCKING, null, slideId, page, asset, component, slot, key, details);
    }

    private ExecutorModels.ExecutorDiagnostic error(
            DiagnosticCode code, String key, CompositionModels.CompositionOperation operation,
            Map<String, String> details) {
        return new ExecutorModels.ExecutorDiagnostic(code.name(), DiagnosticSeverity.ERROR,
                DiagnosticImpact.JOB_BLOCKING, operation.operationId(), null, null,
                operation.assetRequirementId(), operation.componentId(), operation.slotId(), key, details);
    }

    private ExecutorModels.ExecutorDiagnostic partial(
            CompositionModels.CompositionOperation operation, DiagnosticCode code, String key) {
        return new ExecutorModels.ExecutorDiagnostic(code.name(), DiagnosticSeverity.ERROR,
                DiagnosticImpact.ARTIFACT_INCOMPLETE, operation.operationId(), null, null,
                operation.assetRequirementId(), operation.componentId(), operation.slotId(), key, Map.of());
    }

    private ExecutionResult failed(List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        return new ExecutionResult(ContractTypes.GenerationJobStatus.FAILED, null,
                new ExecutorModels.PreviewReference(false, "NOT_AVAILABLE"), diagnostics, null);
    }

    private ExecutionResult failedAndCleanup(
            List<ExecutorModels.ExecutorDiagnostic> diagnostics, Path directory, boolean createdByThisAttempt) {
        if (!createdByThisAttempt || Files.isSymbolicLink(directory)) {
            return failed(diagnostics);
        }
        try {
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException ignored) {
        }
        return failed(diagnostics);
    }

    private boolean hasBlocking(List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(item -> item.impact() == DiagnosticImpact.JOB_BLOCKING);
    }

    private ContractTypes.GenerationJobStatus status(List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        if (hasBlocking(diagnostics)) {
            return ContractTypes.GenerationJobStatus.FAILED;
        }
        return diagnostics.stream().anyMatch(item -> item.impact() == DiagnosticImpact.ARTIFACT_INCOMPLETE)
                ? ContractTypes.GenerationJobStatus.PARTIAL
                : diagnostics.stream().anyMatch(item -> item.impact() == DiagnosticImpact.NON_BLOCKING)
                ? ContractTypes.GenerationJobStatus.SUCCEEDED_WITH_FEEDBACK
                : ContractTypes.GenerationJobStatus.SUCCEEDED;
    }

    private String safeExecutionId(String value) {
        if (value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            return value;
        }
        return null;
    }

    private String safeId(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String safeReason(String value) {
        if (value == null || value.isBlank()) {
            return "unspecified";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_").substring(0,
                Math.min(64, value.replaceAll("[^A-Za-z0-9._-]", "_").length()));
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] value = digest.digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte item : value) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }

    public record ExecutionResult(
            ContractTypes.GenerationJobStatus status,
            ExecutorModels.ArtifactReference artifact,
            ExecutorModels.PreviewReference preview,
            List<ExecutorModels.ExecutorDiagnostic> diagnostics,
            ExecutorModels.BuildValidationResult buildValidation) {
        public ExecutionResult {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private record ContractGateResult(
            CompositionModels.ValidatedExecutionPackage executionPackage,
            List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
    }

    private record FileIdentity(Path path, long size, String sha256, FileTime lastModified) {
    }

    private record AttemptDirectory(
            Path directory, boolean created, ContractTypes.GenerationJobStatus existingStatus, int existingSlideCount) {
    }

    private record ArtifactManifest(
            ContractTypes.GenerationJobStatus status, String sha256, long size, int slideCount) {
    }

    private static final class AttemptConflictException extends Exception {
        private AttemptConflictException(String message) {
            super(message);
        }
    }

    private static final class ContentTypesException extends Exception {
        private ContentTypesException(String message) {
            super(message);
        }
    }

}
