package com.auvdidao.a12.pptengine.composition;

import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticCode.TEXT_FIT_POLICY_UNAVAILABLE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity.ERROR;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSource.PLAN_VALIDATOR;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TextFitMode.NO_ADJUSTMENT_PROFILE_V1;

/** Fail-closed semantic gate for the teacher-confirmed TextFit policy. */
@Component
public class TextFitPolicyGate {

    public List<ContractModels.Diagnostic> validate(
            CompositionModels.ValidatedExecutionPackage executionPackage) {
        return validate(executionPackage.templateProfile().textFitPolicy(),
                executionPackage.generationJob().fontEnvironmentVersion());
    }

    public List<ContractModels.Diagnostic> validate(
            ContractModels.TextFitPolicy policy,
            String jobFontEnvironmentVersion) {
        return validateInternal(policy, jobFontEnvironmentVersion, false);
    }

    /** Planner shape check; constrained measurement capability is supplied by the caller. */
    public List<ContractModels.Diagnostic> validateForPlanner(
            ContractModels.TextFitPolicy policy,
            String jobFontEnvironmentVersion) {
        return validateInternal(policy, jobFontEnvironmentVersion, true);
    }

    private List<ContractModels.Diagnostic> validateInternal(
            ContractModels.TextFitPolicy policy,
            String jobFontEnvironmentVersion,
            boolean plannerMeasurementMayBeAvailable) {
        ContractModels.TextFitPolicy effective = policy == null
                ? ContractModels.TextFitPolicy.noAdjustmentDefault() : policy;
        List<ContractModels.Diagnostic> diagnostics = new ArrayList<>();
        boolean version = ContractTypes.TEXT_FIT_BOUNDARY_V1.equals(effective.policyVersion());
        boolean ranges = validRanges(effective);
        boolean mode = effective.mode() == NO_ADJUSTMENT_PROFILE_V1
                || effective.mode() == ContractTypes.TextFitMode.PROFILE_CONSTRAINED;
        boolean noAdjustment = effective.mode() == NO_ADJUSTMENT_PROFILE_V1;
        boolean font = jobFontEnvironmentVersion != null && !jobFontEnvironmentVersion.isBlank()
                && ((noAdjustment && "UNBOUND".equals(effective.fontEnvironmentVersion()))
                || jobFontEnvironmentVersion.equals(effective.fontEnvironmentVersion()));
        boolean safeFlags = effective.adjustmentOrder().stream().allMatch(this::knownAdjustment);
        if (!version || !font || !ranges || !mode || !safeFlags) {
            diagnostics.add(unavailable("invalidOrMismatchedPolicy"));
            return List.copyOf(diagnostics);
        }
        if (effective.mode() == NO_ADJUSTMENT_PROFILE_V1) {
            if (!effective.adjustmentOrder().isEmpty()
                    || effective.maxTextBoxGrowthWidthEmu() != 0
                    || effective.maxTextBoxGrowthHeightEmu() != 0) {
                diagnostics.add(unavailable("noAdjustmentProfileAllowsNoChangesOnly"));
            }
            return List.copyOf(diagnostics);
        }
        if (!plannerMeasurementMayBeAvailable) {
            // Real font measurement is explicitly unavailable in the Compose service.
            diagnostics.add(unavailable("measurementCapabilityNotAvailable"));
        }
        return List.copyOf(diagnostics);
    }

    private boolean validRanges(ContractModels.TextFitPolicy policy) {
        return policy.minimumFontSizePt() > 0
                && policy.minimumFontSizePt() <= policy.defaultFontSizePt()
                && policy.defaultFontSizePt() <= policy.maximumFontSizePt()
                && policy.maximumFontSizePt() <= 1000
                && policy.fontSizeStepPt() > 0
                && policy.fontSizeStepPt() <= 100
                && policy.minimumLineSpacingPct() > 0
                && policy.minimumLineSpacingPct() <= policy.defaultLineSpacingPct()
                && policy.defaultLineSpacingPct() <= policy.maximumLineSpacingPct()
                && policy.maximumLineSpacingPct() <= 1000
                && policy.lineSpacingStepPct() > 0
                && policy.lineSpacingStepPct() <= 100
                && policy.maxTextBoxGrowthWidthEmu() >= 0
                && policy.maxTextBoxGrowthWidthEmu() <= 100000000
                && policy.maxTextBoxGrowthHeightEmu() >= 0
                && policy.maxTextBoxGrowthHeightEmu() <= 100000000
                && policy.minimumParagraphSpacingPt() >= 0
                && policy.minimumParagraphSpacingPt() <= policy.defaultParagraphSpacingPt()
                && policy.defaultParagraphSpacingPt() <= policy.maximumParagraphSpacingPt()
                && policy.maximumParagraphSpacingPt() <= 1000
                && policy.paragraphSpacingStepPt() > 0
                && policy.paragraphSpacingStepPt() <= 100
                && policy.adjustmentOrder().size() <= 4
                && policy.adjustmentOrder().stream().distinct().count()
                == policy.adjustmentOrder().size();
    }

    private boolean knownAdjustment(String value) {
        return "FONT_SIZE".equals(value)
                || "LINE_SPACING".equals(value)
                || "TEXT_BOX_GROWTH".equals(value)
                || "PARAGRAPH_SPACING".equals(value);
    }

    private ContractModels.Diagnostic unavailable(String reason) {
        return DiagnosticFactory.stageError(
                PLAN_VALIDATOR, TEXT_FIT_POLICY_UNAVAILABLE,
                "textFit.policyUnavailable", null, null, null, null, null, null,
                Map.of("reason", reason));
    }
}
