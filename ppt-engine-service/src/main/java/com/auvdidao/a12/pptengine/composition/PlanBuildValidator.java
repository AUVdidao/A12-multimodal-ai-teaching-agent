package com.auvdidao.a12.pptengine.composition;

import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Independent post-composer gate before a future Executor may consume a plan. */
@Component
public class PlanBuildValidator {

    private final CompositionPlanValidator compatibilityValidator;
    private final TextFitPolicyGate textFitPolicyGate;

    public PlanBuildValidator(
            CompositionPlanValidator compatibilityValidator,
            TextFitPolicyGate textFitPolicyGate) {
        this.compatibilityValidator = compatibilityValidator;
        this.textFitPolicyGate = textFitPolicyGate;
    }

    public List<ContractModels.Diagnostic> validate(
            CompositionModels.ValidatedExecutionPackage executionPackage,
            CompositionModels.ComposedPresentationPlan plan,
            CompositionPlanValidator.PartialPlanContext partialContext) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>(
                textFitPolicyGate.validate(executionPackage));
        diagnostics.addAll(compatibilityValidator.validate(executionPackage, plan, partialContext));
        return List.copyOf(diagnostics);
    }
}
