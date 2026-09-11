package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.CompositionDiagnosticClassifier;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompositionDiagnosticClassifierTest {

    @Test
    void layoutWarningRemainsNonBlockingAndDoesNotBecomePartial() {
        ContractModels.Diagnostic warning = DiagnosticFactory.stageWarning(
                ContractTypes.DiagnosticSource.LAYOUT_RESOLVER,
                ContractTypes.DiagnosticCode.COMPOSITION_REFERENCE_INVALID,
                "layout.warning", "slide-001", 1, null, null, null, null,
                Map.of("reason", "advisory"));

        var classified = CompositionDiagnosticClassifier.classify(List.of(warning));

        assertThat(classified.get(0).impact()).isEqualTo(ContractTypes.DiagnosticImpact.NON_BLOCKING);
        assertThat(CompositionDiagnosticClassifier.status(classified))
                .isEqualTo(ContractTypes.GenerationJobStatus.SUCCEEDED_WITH_FEEDBACK);
    }
}
