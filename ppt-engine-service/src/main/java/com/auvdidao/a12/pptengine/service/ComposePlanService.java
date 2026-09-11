package com.auvdidao.a12.pptengine.service;

import com.auvdidao.a12.pptengine.composition.CompositionPlanValidator;
import com.auvdidao.a12.pptengine.composition.PlanBuildValidator;
import com.auvdidao.a12.pptengine.composition.SlideComposer;
import com.auvdidao.a12.pptengine.composition.TextFitPolicyGate;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.CompositionDiagnosticClassifier;
import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import com.auvdidao.a12.pptengine.layout.LayoutResolver;
import com.auvdidao.a12.pptengine.layout.TemplatePageResolver;
import com.auvdidao.a12.pptengine.resolver.ComponentResolver;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.COMPOSE_CONTRACT_V2;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.COMPOSITION_FEEDBACK_V2;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity.ERROR;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.LAYOUT_RESOLVER;
@Service
public class ComposePlanService {

    private final ContractGate contractGate;
    private final ComponentResolver componentResolver;
    private final TemplatePageResolver templatePageResolver;
    private final LayoutResolver layoutResolver;
    private final SlideComposer slideComposer;
    private final PlanBuildValidator planBuildValidator;
    private final TextFitPolicyGate textFitPolicyGate;

    public ComposePlanService(
            ContractGate contractGate,
            ComponentResolver componentResolver,
            TemplatePageResolver templatePageResolver,
            LayoutResolver layoutResolver,
            SlideComposer slideComposer,
            PlanBuildValidator planBuildValidator,
            TextFitPolicyGate textFitPolicyGate) {
        this.contractGate = contractGate;
        this.componentResolver = componentResolver;
        this.templatePageResolver = templatePageResolver;
        this.layoutResolver = layoutResolver;
        this.slideComposer = slideComposer;
        this.planBuildValidator = planBuildValidator;
        this.textFitPolicyGate = textFitPolicyGate;
    }

    public ComposeResult execute(CompositionModels.EngineComposePlanRequest request) {
        ContractGate.ComposeValidationResult gateResult = contractGate.validateCompose(request);
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>(gateResult.diagnostics());
        if (gateResult.executionPackage() == null) {
            return failed(request, diagnostics);
        }
        CompositionModels.ValidatedExecutionPackage executionPackage = gateResult.executionPackage();
        appendApprovedOmissionDiagnostics(executionPackage, diagnostics);
        List<ContractModels.Diagnostic> textFitDiagnostics = textFitPolicyGate.validate(executionPackage);
        diagnostics.addAll(textFitDiagnostics);
        if (!textFitDiagnostics.isEmpty()) {
            return failed(request, diagnostics);
        }

        Set<String> incompleteBlockIds = new HashSet<>();
        Set<String> incompleteAssetRequirementIds = new HashSet<>();
        boolean pageStructureBlocking = false;
        List<CompositionModels.ResolvedLayoutPlan> layouts = new ArrayList<>();
        for (ContractModels.LockedPptSlide slide : executionPackage.specification().slides()) {
            TemplatePageResolver.SelectionResult pageResult =
                    templatePageResolver.resolve(slide, executionPackage.templateProfile());
            diagnostics.addAll(pageResult.diagnostics());
            if (pageResult.selection() == null) {
                pageStructureBlocking = true;
                continue;
            }

            ComponentResolver.ResolverResult result = componentResolver.resolveForComposition(
                    slide, executionPackage.templateProfile(),
                    executionPackage.manifestEntriesByRequirementId(), pageResult.selection(),
                    pageResult.assignment());
            diagnostics.addAll(result.diagnostics());
            incompleteBlockIds.addAll(result.plan().unresolvedBlockIds());
            incompleteAssetRequirementIds.addAll(result.plan().unresolvedAssetIds());
            collectIncompleteBindings(
                    result.diagnostics(), incompleteBlockIds, incompleteAssetRequirementIds);

            LayoutResolver.LayoutResult layoutResult = layoutResolver.resolveForComposition(
                    slide, executionPackage.templateProfile(), result, pageResult.selection());
            diagnostics.addAll(layoutResult.diagnostics());
            collectIncompleteBindings(
                    layoutResult.diagnostics(), incompleteBlockIds, incompleteAssetRequirementIds);
            boolean layoutError = layoutResult.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.source() == LAYOUT_RESOLVER
                            && diagnostic.severity() == ERROR);
            if (layoutResult.plan() != null && !layoutError) {
                layouts.add(layoutResult.plan());
            } else {
                pageStructureBlocking = true;
                if (!layoutError) {
                    diagnostics.add(DiagnosticFactory.stageError(
                            ContractTypes.DiagnosticSource.PLAN_VALIDATOR,
                            ContractTypes.DiagnosticCode.COMPOSITION_REFERENCE_INVALID,
                            "layout.pageStructureUnavailable",
                            slide.slideId(), slide.pageNumber(), null, null, null, null,
                            java.util.Map.of("reason", "pageStructureUnavailable")));
                }
            }
        }

        if (pageStructureBlocking) {
            return failed(request, diagnostics);
        }

        SlideComposer.ComposerResult composerResult = slideComposer.compose(executionPackage, layouts);
        diagnostics.addAll(composerResult.diagnostics());
        collectIncompleteBindings(
                composerResult.diagnostics(), incompleteBlockIds, incompleteAssetRequirementIds);

        CompositionPlanValidator.PartialPlanContext partialContext =
                new CompositionPlanValidator.PartialPlanContext(
                        incompleteBlockIds, incompleteAssetRequirementIds);
        List<ContractModels.Diagnostic> validatorDiagnostics =
                planBuildValidator.validate(executionPackage, composerResult.plan(), partialContext);
        diagnostics.addAll(validatorDiagnostics);
        if (!validatorDiagnostics.isEmpty()) {
            return failed(request, diagnostics);
        }
        CompositionModels.CompositionFeedback feedback = feedback(request, diagnostics);
        return new ComposeResult(200, composerResult.plan(), feedback);
    }

    private void collectIncompleteBindings(
            List<ContractModels.Diagnostic> diagnostics,
            Set<String> blockIds,
            Set<String> assetRequirementIds) {
        diagnostics.stream()
                .filter(item -> item.severity() == ContractTypes.DiagnosticSeverity.ERROR)
                .forEach(item -> {
                    if (item.blockId() != null) {
                        blockIds.add(item.blockId());
                    }
                    if (item.assetId() != null) {
                        assetRequirementIds.add(item.assetId());
                    }
                });
    }

    private void appendApprovedOmissionDiagnostics(
            CompositionModels.ValidatedExecutionPackage executionPackage,
            List<ContractModels.Diagnostic> diagnostics) {
        for (ContractModels.LockedPptSlide slide : executionPackage.specification().slides()) {
            for (ContractModels.LockedPptAssetReference requirement : slide.assetRequirements()) {
                CompositionModels.ApprovedAssetManifestEntry entry =
                        executionPackage.manifestEntriesByRequirementId().get(requirement.assetId());
                if (entry != null && entry.resolution() == ContractTypes.AssetResolution.APPROVED_OMISSION) {
                    diagnostics.add(DiagnosticFactory.stageWarning(
                            ContractTypes.DiagnosticSource.ASSET_MANIFEST_GATE,
                            ContractTypes.DiagnosticCode.ASSET_APPROVED_OMISSION,
                            "assetManifest.approvedOmission",
                            slide.slideId(), slide.pageNumber(), null, requirement.assetId(),
                            null, null, java.util.Map.of("resolution", "APPROVED_OMISSION")));
                }
            }
        }
    }

    private ComposeResult failed(
            CompositionModels.EngineComposePlanRequest request,
            List<ContractModels.Diagnostic> diagnostics) {
        return new ComposeResult(422, null, feedback(request, diagnostics));
    }

    private CompositionModels.CompositionFeedback feedback(
            CompositionModels.EngineComposePlanRequest request,
            List<ContractModels.Diagnostic> diagnostics) {
        ContractModels.LockedPptSpecification specification = request.specification();
        ContractModels.ConfirmedTemplateProfile profile = request.templateProfile();
        List<CompositionModels.CompositionDiagnostic> compositionDiagnostics =
                CompositionDiagnosticClassifier.classify(diagnostics);
        return new CompositionModels.CompositionFeedback(
                COMPOSE_CONTRACT_V2,
                COMPOSITION_FEEDBACK_V2,
                request.requestId(),
                request.generationJob().generationJobId(),
                request.generationJob().executionAttemptId(),
                specification.specificationId(),
                specification.version(),
                specification.checksum(),
                profile.profileId(),
                profile.profileVersion(),
                CompositionDiagnosticClassifier.status(compositionDiagnostics),
                compositionDiagnostics,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    public record ComposeResult(
            int httpStatus,
            CompositionModels.ComposedPresentationPlan plan,
            CompositionModels.CompositionFeedback feedback) {
    }
}
