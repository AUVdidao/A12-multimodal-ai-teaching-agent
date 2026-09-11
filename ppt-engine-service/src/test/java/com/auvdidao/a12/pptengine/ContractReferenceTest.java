package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ObjectType.GROUP;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TemplateProfileStatus.CONFIRMED;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.TransformConstraint.TRANSLATE_ONLY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.V1;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ContractReferenceTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContractGate contractGate;

    @Test
    void crossPageShapeReferenceIsRejectedEvenWhenObjectExistsOnAnotherPage() {
        ContractModels.ConfirmedTemplateProfile profile = profile(
                List.of(
                        page("page-1", 1, "shape-on-slide-1"),
                        page("page-2", 2, "shape-on-slide-2")),
                List.of(component("component-cross-page", 1, "shape-on-slide-2", List.of())));

        List<ContractModels.Diagnostic> diagnostics = validate(profile);

        assertThat(diagnostics).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("CONTRACT_INVALID");
            assertThat(diagnostic.messageKey()).isEqualTo("reference.shapeMissing");
            assertThat(diagnostic.componentId()).isEqualTo("component-cross-page");
        });
    }

    @Test
    void danglingShapeReferenceIsRejected() {
        ContractModels.ConfirmedTemplateProfile profile = profile(
                List.of(page("page-1", 1, "shape-known")),
                List.of(component("component-dangling-shape", 1, "shape-missing", List.of())));

        List<ContractModels.Diagnostic> diagnostics = validate(profile);

        assertThat(diagnostics).anySatisfy(diagnostic -> {
            assertThat(diagnostic.messageKey()).isEqualTo("reference.shapeMissing");
            assertThat(diagnostic.componentId()).isEqualTo("component-dangling-shape");
        });
    }

    @Test
    void danglingChildComponentIdIsRejected() {
        ContractModels.ConfirmedTemplateProfile profile = profile(
                List.of(page("page-1", 1, "shape-parent")),
                List.of(component("component-parent", 1, "shape-parent", List.of("component-missing"))));

        List<ContractModels.Diagnostic> diagnostics = validate(profile);

        assertThat(diagnostics).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("CONTRACT_INVALID");
            assertThat(diagnostic.messageKey()).isEqualTo("reference.childComponentMissing");
            assertThat(diagnostic.componentId()).isEqualTo("component-parent");
        });
    }

    private List<ContractModels.Diagnostic> validate(ContractModels.ConfirmedTemplateProfile profile) {
        return contractGate.validateSemantics(new ContractModels.EnginePreflightRequest(
                V1, "req-reference", ContractFixtures.specification(objectMapper), profile));
    }

    private ContractModels.TemplatePageReference page(
            String pageReferenceId,
            int sourceSlide,
            String objectId) {
        return new ContractModels.TemplatePageReference(
                pageReferenceId, sourceSlide, "BODY", List.of(objectId));
    }

    private ContractModels.TemplateComponent component(
            String componentId,
            int sourceSlide,
            String objectId,
            List<String> childComponentIds) {
        return new ContractModels.TemplateComponent(
                componentId, componentId, "BODY", sourceSlide,
                List.of(new ContractModels.StableObjectReference(GROUP, objectId)), childComponentIds,
                List.of(new ContractModels.ComponentSlot(
                        "slot-" + componentId, "BODY", List.of(BODY),
                        new ContractModels.Bounds(1, 1, 100, 100), true,
                        new ContractModels.CapacityConstraint(100, 1))),
                TRANSLATE_ONLY,
                new ContractModels.FixedStyle("style-1", "font-1", "color-1", true),
                true, 0.9, true);
    }

    private ContractModels.ConfirmedTemplateProfile profile(
            List<ContractModels.TemplatePageReference> pages,
            List<ContractModels.TemplateComponent> components) {
        return new ContractModels.ConfirmedTemplateProfile(
                V1, "profile-001", "template-reference", 1, 1, CONFIRMED,
                new ContractModels.PageSize(1000, 1000),
                new ContractModels.SpatialProfile(0, 0, 0, 0), pages, components);
    }
}
