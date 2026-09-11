package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.service.ComposePlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentSourceType.MATERIAL;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.PreferredPosition.CENTER;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.TRANSLATE_ONLY;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ComposePlanPageInvariantTest {

    @Autowired
    private ComposePlanService composePlanService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void twoPagesWithSecondTemplatePageMissingAreJobBlockingWithoutPlan() {
        var request = CompositionTestSupport.request(
                objectMapper, "req-two-page-missing",
                List.of(slide("slide-001", 1, "BODY", "block-001"),
                        slide("slide-002", 2, "TITLE", "block-002")),
                ContractFixtures.profile());

        assertPageStructureFailure(composePlanService.execute(request), "slide-002", 2);
    }

    @Test
    void threePagesWithMiddleTemplatePageMissingAreJobBlockingWithoutPlan() {
        var request = CompositionTestSupport.request(
                objectMapper, "req-three-page-missing",
                List.of(slide("slide-001", 1, "BODY", "block-001"),
                        slide("slide-002", 2, "TITLE", "block-002"),
                        slide("slide-003", 3, "BODY", "block-003")),
                ContractFixtures.profile());

        assertPageStructureFailure(composePlanService.execute(request), "slide-002", 2);
    }

    private void assertPageStructureFailure(
            ComposePlanService.ComposeResult result, String slideId, int pageNumber) {
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.plan()).isNull();
        assertThat(result.feedback().status()).isEqualTo(ContractTypes.GenerationJobStatus.FAILED);
        assertThat(result.feedback().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("TEMPLATE_PAGE_MISSING");
                    assertThat(diagnostic.slideId()).isEqualTo(slideId);
                    assertThat(diagnostic.pageNumber()).isEqualTo(pageNumber);
                    assertThat(diagnostic.impact()).isEqualTo(ContractTypes.DiagnosticImpact.JOB_BLOCKING);
                });
    }

    private ContractModels.LockedPptSlide slide(
            String slideId, int pageNumber, String semanticRole, String blockId) {
        return new ContractModels.LockedPptSlide(
                slideId, pageNumber, "标题", "教学目标",
                List.of(new ContractModels.LockedPptContentBlock(
                        blockId, BODY, "锁定内容", MATERIAL, "material-" + blockId, true)),
                new ContractModels.SemanticLayout(
                        semanticRole,
                        List.of(new ContractModels.SemanticRegion(
                                "region-" + blockId, semanticRole, CENTER, 1)),
                        TRANSLATE_ONLY),
                List.of(), List.of(), "");
    }
}
