package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.composition.TextFitPlanner;
import com.auvdidao.a12.pptengine.composition.TextFitPolicyGate;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextFitPolicyTest {

    private final TextFitPolicyGate gate = new TextFitPolicyGate();
    private final TextFitPlanner planner = new TextFitPlanner();

    @Test
    void defaultNoAdjustmentPolicyIsSafeAndVersioned() {
        List<ContractModels.Diagnostic> diagnostics = gate.validate(
                ContractModels.TextFitPolicy.noAdjustmentDefault(), "font-env-v1");

        assertThat(diagnostics).isEmpty();
    }

    @Test
    void missingConstrainedMeasurementFailsClosedWithoutChanges() {
        ContractModels.TextFitPolicy policy = constrainedPolicy(List.of("FONT_SIZE"));

        TextFitPlanner.PlanningResult result = planner.plan(new TextFitPlanner.PlanningRequest(
                "locked-content-sha256", policy, "font-env-v1", null));

        assertThat(result.changes()).isEmpty();
        assertThat(result.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly(ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE.name());
    }

    @Test
    void constrainedPlannerIsDeterministicAndOnlyReturnsTypographyChanges() {
        ContractModels.TextFitPolicy policy = constrainedPolicy(List.of("FONT_SIZE", "LINE_SPACING"));
        TextFitPlanner.Measurement measurement = new TextFitPlanner.Measurement(
                true, true, 20, 120, null, null, null);

        TextFitPlanner.PlanningResult first = planner.plan(new TextFitPlanner.PlanningRequest(
                "locked-content-sha256", policy, "font-env-v1", measurement));
        TextFitPlanner.PlanningResult second = planner.plan(new TextFitPlanner.PlanningRequest(
                "locked-content-sha256", policy, "font-env-v1", measurement));

        assertThat(first).isEqualTo(second);
        assertThat(first.changes()).extracting(TextFitPlanner.ParameterChange::parameter)
                .containsExactly("FONT_SIZE", "LINE_SPACING");
        assertThat(first.diagnostics()).isEmpty();
    }

    @Test
    void overflowDoesNotRewriteOrSplitContent() {
        ContractModels.TextFitPolicy policy = ContractModels.TextFitPolicy.noAdjustmentDefault();
        TextFitPlanner.PlanningResult result = planner.plan(new TextFitPlanner.PlanningRequest(
                "locked-content-sha256", policy, "font-env-v1",
                new TextFitPlanner.Measurement(true, true, 1, 100, null, null, null)));

        assertThat(result.changes()).isEmpty();
        assertThat(result.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly(ContractTypes.DiagnosticCode.CONTENT_OVERFLOW.name());
    }

    @Test
    void measurementAboveMaximumFailsClosedWithoutOutOfBoundsChange() {
        TextFitPlanner.PlanningResult result = planner.plan(new TextFitPlanner.PlanningRequest(
                "locked-content-sha256", constrainedPolicy(List.of("FONT_SIZE")), "font-env-v1",
                new TextFitPlanner.Measurement(true, true, 40, 100, null, null, null)));

        assertThat(result.changes()).isEmpty();
        assertThat(result.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly(ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE.name());
    }

    @Test
    void textBoxGrowthRequiresAndBoundsBothWidthAndHeight() {
        ContractModels.TextFitPolicy policy = constrainedPolicy(List.of("TEXT_BOX_GROWTH"));
        TextFitPlanner.PlanningResult missingHeight = planner.plan(new TextFitPlanner.PlanningRequest(
                "locked-content-sha256", policy, "font-env-v1",
                new TextFitPlanner.Measurement(true, true, null, null, 1000, null, null)));
        TextFitPlanner.PlanningResult valid = planner.plan(new TextFitPlanner.PlanningRequest(
                "locked-content-sha256", policy, "font-env-v1",
                new TextFitPlanner.Measurement(true, true, null, null, 1000, 1000, null)));
        TextFitPlanner.PlanningResult tooHigh = planner.plan(new TextFitPlanner.PlanningRequest(
                "locked-content-sha256", policy, "font-env-v1",
                new TextFitPlanner.Measurement(true, true, null, null, 1000, 1001, null)));

        assertThat(missingHeight.changes()).isEmpty();
        assertThat(missingHeight.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly(ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE.name());
        assertThat(valid.changes()).extracting(TextFitPlanner.ParameterChange::parameter)
                .containsExactly("TEXT_BOX_GROWTH_WIDTH", "TEXT_BOX_GROWTH_HEIGHT");
        assertThat(tooHigh.changes()).isEmpty();
        assertThat(tooHigh.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly(ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE.name());
    }

    @Test
    void lineAndParagraphMeasurementsCannotEscapeTheirPolicyRanges() {
        ContractModels.TextFitPolicy policy = constrainedPolicy(
                List.of("LINE_SPACING", "PARAGRAPH_SPACING"));
        TextFitPlanner.PlanningResult invalidLine = planner.plan(new TextFitPlanner.PlanningRequest(
                "locked-content-sha256", policy, "font-env-v1",
                new TextFitPlanner.Measurement(true, true, null, 141, null, null, 6)));
        TextFitPlanner.PlanningResult invalidParagraph = planner.plan(new TextFitPlanner.PlanningRequest(
                "locked-content-sha256", policy, "font-env-v1",
                new TextFitPlanner.Measurement(true, true, null, 100, null, null, -1)));

        assertThat(invalidLine.changes()).isEmpty();
        assertThat(invalidLine.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly(ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE.name());
        assertThat(invalidParagraph.changes()).isEmpty();
        assertThat(invalidParagraph.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .containsExactly(ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE.name());
    }

    @Test
    void unboundIsOnlyAcceptedForNoAdjustmentAndConstrainedNeedsRealBinding() {
        ContractModels.TextFitPolicy constrainedUnbound = new ContractModels.TextFitPolicy(
                ContractTypes.TEXT_FIT_BOUNDARY_V1,
                ContractTypes.TextFitMode.PROFILE_CONSTRAINED,
                "UNBOUND",
                12, 20, 32, 1, 80, 100, 140, 5,
                1000, 1000, 0, 6, 18, 1, List.of("FONT_SIZE"));

        assertThat(gate.validate(ContractModels.TextFitPolicy.noAdjustmentDefault(), "font-env-v1"))
                .isEmpty();
        assertThat(gate.validate(constrainedUnbound, "font-env-v1"))
                .extracting(ContractModels.Diagnostic::code)
                .containsExactly(ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE.name());
    }

    @Test
    void invalidPolicyShapeIsRejectedBeforePlanning() {
        ContractModels.TextFitPolicy invalid = new ContractModels.TextFitPolicy(
                "0.9.0", ContractTypes.TextFitMode.NO_ADJUSTMENT_PROFILE_V1,
                "UNBOUND", 32, 20, 12, 0, 140, 100, 80, 0,
                1, 1, 18, 6, 0, 0, List.of("FONT_SIZE"));

        assertThat(gate.validate(invalid, "font-env-v1"))
                .extracting(ContractModels.Diagnostic::code)
                .containsExactly(ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE.name());
    }

    private ContractModels.TextFitPolicy constrainedPolicy(List<String> order) {
        return new ContractModels.TextFitPolicy(
                ContractTypes.TEXT_FIT_BOUNDARY_V1,
                ContractTypes.TextFitMode.PROFILE_CONSTRAINED,
                "font-env-v1",
                12, 20, 32, 1,
                80, 100, 140, 5,
                1000, 1000,
                0, 6, 18, 1,
                order);
    }
}
