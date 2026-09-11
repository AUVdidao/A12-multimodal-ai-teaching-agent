package com.auvdidao.a12.pptengine.composition;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.CONTENT_OVERFLOW;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.PLAN_VALIDATOR;

/**
 * Pure deterministic planner. It receives measurements; it never measures,
 * rewrites text, splits slides, or delegates fitting to PowerPoint.
 */
@Component
public class TextFitPlanner {

    private final TextFitPolicyGate policyGate;

    public TextFitPlanner() {
        this(new TextFitPolicyGate());
    }

    @Autowired
    public TextFitPlanner(TextFitPolicyGate policyGate) {
        this.policyGate = policyGate;
    }

    public PlanningResult plan(PlanningRequest request) {
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        List<ParameterChange> changes = new ArrayList<>();
        if (request == null || request.policy() == null
                || request.lockedContentSha256() == null
                || request.lockedContentSha256().isBlank()) {
            diagnostics.add(unavailable("missingInput"));
            return new PlanningResult(List.of(), List.copyOf(diagnostics));
        }
        ContractModels.TextFitPolicy policy = request.policy();
        List<ContractModels.Diagnostic> policyDiagnostics = policyGate.validateForPlanner(
                policy, request.fontEnvironmentVersion());
        if (!policyDiagnostics.isEmpty()) {
            diagnostics.addAll(policyDiagnostics);
            return new PlanningResult(List.of(), List.copyOf(diagnostics));
        }
        Measurement measurement = request.measurement();
        if (policy.mode() == ContractTypes.TextFitMode.NO_ADJUSTMENT_PROFILE_V1) {
            if (measurement != null && measurement.overflow()) {
                diagnostics.add(overflow("noAdjustmentPolicy"));
            }
            return new PlanningResult(List.of(), List.copyOf(diagnostics));
        }
        if (measurement == null || !measurement.available()) {
            diagnostics.add(unavailable("measurementUnavailable"));
            return new PlanningResult(List.of(), List.copyOf(diagnostics));
        }
        String measurementError = validateMeasurements(policy, measurement);
        if (measurementError != null) {
            diagnostics.add(unavailable(measurementError));
            return new PlanningResult(List.of(), List.copyOf(diagnostics));
        }
        if (!measurement.overflow()) {
            return new PlanningResult(List.of(), List.of());
        }
        boolean invalidCandidate = false;
        for (String adjustment : policy.adjustmentOrder()) {
            if ("FONT_SIZE".equals(adjustment)
                    && measurement.fontSizePt() != null
                    && measurement.fontSizePt() > policy.minimumFontSizePt()) {
                invalidCandidate |= !addBounded(changes, "FONT_SIZE",
                        measurement.fontSizePt() - policy.fontSizeStepPt(),
                        policy.minimumFontSizePt(), policy.maximumFontSizePt());
            } else if ("LINE_SPACING".equals(adjustment)
                    && measurement.lineSpacingPct() != null
                    && measurement.lineSpacingPct() > policy.minimumLineSpacingPct()) {
                invalidCandidate |= !addBounded(changes, "LINE_SPACING",
                        measurement.lineSpacingPct() - policy.lineSpacingStepPct(),
                        policy.minimumLineSpacingPct(), policy.maximumLineSpacingPct());
            } else if ("TEXT_BOX_GROWTH".equals(adjustment)
                    && measurement.growthWidthEmu() != null
                    && measurement.growthHeightEmu() != null) {
                invalidCandidate |= !addBounded(changes, "TEXT_BOX_GROWTH_WIDTH",
                        measurement.growthWidthEmu(), 0, policy.maxTextBoxGrowthWidthEmu());
                invalidCandidate |= !addBounded(changes, "TEXT_BOX_GROWTH_HEIGHT",
                        measurement.growthHeightEmu(), 0, policy.maxTextBoxGrowthHeightEmu());
            } else if ("PARAGRAPH_SPACING".equals(adjustment)
                    && measurement.paragraphSpacingPt() != null
                    && measurement.paragraphSpacingPt() > policy.minimumParagraphSpacingPt()) {
                invalidCandidate |= !addBounded(changes, "PARAGRAPH_SPACING",
                        measurement.paragraphSpacingPt() - policy.paragraphSpacingStepPt(),
                        policy.minimumParagraphSpacingPt(), policy.maximumParagraphSpacingPt());
            }
        }
        if (invalidCandidate || changes.isEmpty()) {
            diagnostics.add(overflow("policyBoundsExceeded"));
            changes.clear();
        }
        return new PlanningResult(List.copyOf(changes), List.copyOf(diagnostics));
    }

    private String validateMeasurements(
            ContractModels.TextFitPolicy policy,
            Measurement measurement) {
        if (measurement.fontSizePt() != null
                && !within(measurement.fontSizePt(), policy.minimumFontSizePt(), policy.maximumFontSizePt())) {
            return "fontSizeMeasurementOutsidePolicyBounds";
        }
        if (measurement.lineSpacingPct() != null
                && !within(measurement.lineSpacingPct(), policy.minimumLineSpacingPct(), policy.maximumLineSpacingPct())) {
            return "lineSpacingMeasurementOutsidePolicyBounds";
        }
        if (measurement.paragraphSpacingPt() != null
                && !within(measurement.paragraphSpacingPt(),
                policy.minimumParagraphSpacingPt(), policy.maximumParagraphSpacingPt())) {
            return "paragraphSpacingMeasurementOutsidePolicyBounds";
        }
        if ((measurement.growthWidthEmu() == null) != (measurement.growthHeightEmu() == null)) {
            return "textBoxGrowthMeasurementIncomplete";
        }
        if (measurement.growthWidthEmu() != null
                && (!within(measurement.growthWidthEmu(), 0, policy.maxTextBoxGrowthWidthEmu())
                || !within(measurement.growthHeightEmu(), 0, policy.maxTextBoxGrowthHeightEmu()))) {
            return "textBoxGrowthMeasurementOutsidePolicyBounds";
        }
        for (String adjustment : policy.adjustmentOrder()) {
            if ("FONT_SIZE".equals(adjustment)
                    && measurement.fontSizePt() == null) {
                return "fontSizeMeasurementOutsidePolicyBounds";
            }
            if ("LINE_SPACING".equals(adjustment)
                    && measurement.lineSpacingPct() == null) {
                return "lineSpacingMeasurementOutsidePolicyBounds";
            }
            if ("TEXT_BOX_GROWTH".equals(adjustment)) {
                if (measurement.growthWidthEmu() == null || measurement.growthHeightEmu() == null) {
                    return "textBoxGrowthMeasurementIncomplete";
                }
            }
            if ("PARAGRAPH_SPACING".equals(adjustment)
                    && measurement.paragraphSpacingPt() == null) {
                return "paragraphSpacingMeasurementOutsidePolicyBounds";
            }
        }
        return null;
    }

    private boolean within(Integer value, int minimum, int maximum) {
        return value != null && value >= minimum && value <= maximum;
    }

    private boolean addBounded(
            List<ParameterChange> changes,
            String parameter,
            int value,
            int minimum,
            int maximum) {
        if (value < minimum || value > maximum) {
            return false;
        }
        changes.add(new ParameterChange(parameter, value));
        return true;
    }

    private ContractModels.Diagnostic unavailable(String reason) {
        return DiagnosticFactory.stageError(PLAN_VALIDATOR, TEXT_FIT_POLICY_UNAVAILABLE,
                "textFit.policyUnavailable", null, null, null, null, null, null,
                Map.of("reason", reason));
    }

    private ContractModels.Diagnostic overflow(String reason) {
        return DiagnosticFactory.stageError(PLAN_VALIDATOR, CONTENT_OVERFLOW,
                "textFit.contentOverflow", null, null, null, null, null, null,
                Map.of("reason", reason));
    }

    public record PlanningRequest(
            String lockedContentSha256,
            ContractModels.TextFitPolicy policy,
            String fontEnvironmentVersion,
            Measurement measurement) {
    }

    public record Measurement(
            boolean available,
            boolean overflow,
            Integer fontSizePt,
            Integer lineSpacingPct,
            Integer growthWidthEmu,
            Integer growthHeightEmu,
            Integer paragraphSpacingPt) {
    }

    public record ParameterChange(String parameter, int value) {
    }

    public record PlanningResult(
            List<ParameterChange> changes,
            List<ContractModels.Diagnostic> diagnostics) {
    }
}
