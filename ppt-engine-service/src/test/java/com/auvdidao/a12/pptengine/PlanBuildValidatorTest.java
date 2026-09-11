package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.composition.CompositionPlanValidator;
import com.auvdidao.a12.pptengine.composition.PlanBuildValidator;
import com.auvdidao.a12.pptengine.composition.TextFitPolicyGate;
import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanBuildValidatorTest {

    @Test
    void textFitDiagnosticPrecedesCompatibilityDiagnosticsInStableOrder() {
        TextFitPolicyGate policyGate = mock(TextFitPolicyGate.class);
        CompositionPlanValidator compatibilityValidator = mock(CompositionPlanValidator.class);
        PlanBuildValidator validator = new PlanBuildValidator(compatibilityValidator, policyGate);
        ContractModels.Diagnostic policyDiagnostic = diagnostic(
                ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE);
        ContractModels.Diagnostic planDiagnostic = diagnostic(
                ContractTypes.DiagnosticCode.COMPOSITION_REFERENCE_INVALID);
        CompositionPlanValidator.PartialPlanContext context =
                CompositionPlanValidator.PartialPlanContext.complete();
        when(policyGate.validate((CompositionModels.ValidatedExecutionPackage) null))
                .thenReturn(List.of(policyDiagnostic));
        when(compatibilityValidator.validate(null, null, context))
                .thenReturn(List.of(planDiagnostic));

        List<ContractModels.Diagnostic> result = validator.validate(null, null, context);

        assertThat(result).extracting(ContractModels.Diagnostic::code)
                .containsExactly(
                        ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE.name(),
                        ContractTypes.DiagnosticCode.COMPOSITION_REFERENCE_INVALID.name());
        var order = inOrder(policyGate, compatibilityValidator);
        order.verify(policyGate).validate((CompositionModels.ValidatedExecutionPackage) null);
        order.verify(compatibilityValidator).validate(null, null, context);
    }

    private ContractModels.Diagnostic diagnostic(ContractTypes.DiagnosticCode code) {
        return new ContractModels.Diagnostic(
                code.name(), ContractTypes.DiagnosticSeverity.ERROR,
                ContractTypes.DiagnosticSource.PLAN_VALIDATOR, false,
                null, null, null, null, null, null, "test", Map.of());
    }
}
