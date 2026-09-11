package com.auvdidao.a12.pptengine.service;

import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import com.auvdidao.a12.pptengine.resolver.ComponentResolver;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.FeedbackOutcome.PARTIAL;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.FeedbackOutcome.REJECTED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.FeedbackOutcome.SUCCESS;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.V1;

@Service
public class PreflightService {

    private final ContractGate contractGate;
    private final ComponentResolver componentResolver;

    public PreflightService(ContractGate contractGate, ComponentResolver componentResolver) {
        this.contractGate = contractGate;
        this.componentResolver = componentResolver;
    }

    public PreflightResult execute(ContractModels.EnginePreflightRequest request) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>(contractGate.validateSemantics(request));
        if (ContractGate.hasErrors(diagnostics)) {
            return rejected(request, diagnostics);
        }

        List<ContractModels.ResolvedSlidePlan> slidePlans = new ArrayList<>();
        for (ContractModels.LockedPptSlide slide : request.specification().slides()) {
            ComponentResolver.ResolverResult result = componentResolver.resolve(slide, request.templateProfile());
            slidePlans.add(result.plan());
            diagnostics.addAll(result.diagnostics());
        }
        if (ContractGate.hasErrors(diagnostics)) {
            return rejected(request, diagnostics);
        }
        ContractModels.PreflightExecutionPlan plan = new ContractModels.PreflightExecutionPlan(
                request.requestId(), slidePlans);
        ContractModels.GenerationFeedback feedback = feedback(
                request,
                diagnostics.isEmpty() ? SUCCESS : PARTIAL,
                diagnostics);
        return new PreflightResult(200, plan, feedback);
    }

    private PreflightResult rejected(
            ContractModels.EnginePreflightRequest request,
            List<ContractModels.Diagnostic> diagnostics) {
        return new PreflightResult(422, null, feedback(request, REJECTED, diagnostics));
    }

    public ContractModels.GenerationFeedback invalidRequest(
            String requestId,
            ContractModels.Diagnostic diagnostic) {
        return new ContractModels.GenerationFeedback(
                V1, requestId, null, null, null, null, null, REJECTED,
                List.of(diagnostic), OffsetDateTime.now(ZoneOffset.UTC));
    }

    private ContractModels.GenerationFeedback feedback(
            ContractModels.EnginePreflightRequest request,
            com.auvdidao.a12.pptengine.contract.ContractTypes.FeedbackOutcome outcome,
            List<ContractModels.Diagnostic> diagnostics) {
        ContractModels.LockedPptSpecification specification = request.specification();
        ContractModels.ConfirmedTemplateProfile profile = request.templateProfile();
        return new ContractModels.GenerationFeedback(
                V1,
                request.requestId(),
                specification.specificationId(),
                specification.version(),
                specification.checksum(),
                profile.profileId(),
                profile.profileVersion(),
                outcome,
                diagnostics,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    public record PreflightResult(
            int httpStatus,
            ContractModels.PreflightExecutionPlan plan,
            ContractModels.GenerationFeedback feedback) {
    }
}
