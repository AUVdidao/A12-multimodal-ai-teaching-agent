package com.auvdidao.a12.pptengine.executor;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Complete Executor transform matrix: six policies x text/shape/picture x three outcomes. */
class PptxTransformPolicyTest {

    private static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final ContractModels.Bounds CURRENT = new ContractModels.Bounds(10, 20, 100, 50);
    private static final Map<String, String> OBJECT_XML = Map.of(
            "text", "<p:sp><p:txBody/><p:spPr><a:xfrm><a:off x=\"10\" y=\"20\"/><a:ext cx=\"100\" cy=\"50\"/></a:xfrm></p:spPr></p:sp>",
            "shape", "<p:sp><p:spPr><a:xfrm><a:off x=\"10\" y=\"20\"/><a:ext cx=\"100\" cy=\"50\"/></a:xfrm></p:spPr></p:sp>",
            "picture", "<p:pic><p:spPr><a:xfrm><a:off x=\"10\" y=\"20\"/><a:ext cx=\"100\" cy=\"50\"/></a:xfrm></p:spPr></p:pic>");

    @Test
    void sixStrategiesCoverTextShapePictureLegalOutOfRangeAndMissing() {
        for (ContractTypes.TransformConstraint strategy : ContractTypes.TransformConstraint.values()) {
            for (String objectKind : OBJECT_XML.keySet()) {
                ContractTypes.TransformConstraint matrixStrategy = strategy;
                String matrixObjectKind = objectKind;
                Element legalObject = object(objectKind);
                ContractModels.Bounds legal = legalBounds(strategy);
                SamePackagePowerPointExecutor.applyTransform(legalObject, legal, matrixStrategy, matrixStrategy);
                assertThat(geometry(roundTrip(legalObject))).isEqualTo(expectedGeometry(matrixStrategy, legal));

                if (strategy == ContractTypes.TransformConstraint.RESPONSIVE) {
                    SamePackagePowerPointExecutor.applyTransform(
                            object(matrixObjectKind), outOfRange(matrixStrategy), matrixStrategy, matrixStrategy);
                } else {
                    assertThatThrownBy(() -> SamePackagePowerPointExecutor.applyTransform(
                            object(matrixObjectKind), outOfRange(matrixStrategy), matrixStrategy, matrixStrategy))
                            .isInstanceOf(TransformConstraintException.class);
                }
                assertThatThrownBy(() -> SamePackagePowerPointExecutor.applyTransform(
                        object(matrixObjectKind), null, matrixStrategy, matrixStrategy))
                        .isInstanceOf(TransformConstraintException.class);
            }
        }
    }

    @Test
    void declarationMismatchIsRejectedForEveryObjectKind() {
        for (String objectKind : OBJECT_XML.keySet()) {
            assertThatThrownBy(() -> SamePackagePowerPointExecutor.applyTransform(
                    object(objectKind), CURRENT, ContractTypes.TransformConstraint.FIXED,
                    ContractTypes.TransformConstraint.TRANSLATE_ONLY))
                    .isInstanceOf(TransformConstraintException.class);
        }
    }

    private ContractModels.Bounds legalBounds(ContractTypes.TransformConstraint strategy) {
        return switch (strategy) {
            case FIXED -> CURRENT;
            case TRANSLATE_ONLY -> new ContractModels.Bounds(40, 60, 100, 50);
            case UNIFORM_SCALE -> new ContractModels.Bounds(15, 25, 200, 100);
            case STRETCH_X -> new ContractModels.Bounds(10, 20, 200, 50);
            case STRETCH_Y -> new ContractModels.Bounds(10, 20, 100, 100);
            case RESPONSIVE -> new ContractModels.Bounds(40, 60, 200, 100);
        };
    }

    private ContractModels.Bounds outOfRange(ContractTypes.TransformConstraint strategy) {
        return switch (strategy) {
            case FIXED -> new ContractModels.Bounds(11, 20, 100, 50);
            case TRANSLATE_ONLY -> new ContractModels.Bounds(10, 20, 101, 50);
            case UNIFORM_SCALE -> new ContractModels.Bounds(10, 20, 150, 100);
            case STRETCH_X -> new ContractModels.Bounds(10, 21, 200, 50);
            case STRETCH_Y -> new ContractModels.Bounds(11, 20, 100, 100);
            case RESPONSIVE -> new ContractModels.Bounds(11, 21, 101, 51);
        };
    }

    private ContractModels.Bounds expectedGeometry(
            ContractTypes.TransformConstraint strategy, ContractModels.Bounds requested) {
        return switch (strategy) {
            case FIXED -> CURRENT;
            case TRANSLATE_ONLY -> new ContractModels.Bounds(requested.leftEmu(), requested.topEmu(), 100, 50);
            case UNIFORM_SCALE -> requested;
            case STRETCH_X -> new ContractModels.Bounds(requested.leftEmu(), 20, requested.widthEmu(), 50);
            case STRETCH_Y -> new ContractModels.Bounds(10, requested.topEmu(), 100, requested.heightEmu());
            case RESPONSIVE -> requested;
        };
    }

    private Element object(String objectKind) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream((
                    "<root xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
                            + "xmlns:a=\"" + A_NS + "\">" + OBJECT_XML.get(objectKind) + "</root>")
                    .getBytes(StandardCharsets.UTF_8)));
            return (Element) document.getDocumentElement().getFirstChild();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ContractModels.Bounds geometry(Element object) {
        Element xfrm = PptxPackage.descendants(object, A_NS, "xfrm").get(0);
        Element off = PptxPackage.child(xfrm, A_NS, "off");
        Element ext = PptxPackage.child(xfrm, A_NS, "ext");
        return new ContractModels.Bounds(Integer.parseInt(off.getAttribute("x")), Integer.parseInt(off.getAttribute("y")),
                Integer.parseInt(ext.getAttribute("cx")), Integer.parseInt(ext.getAttribute("cy")));
    }

    private Element roundTrip(Element object) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.transform(new DOMSource(object), new StreamResult(output));
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(("<root xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
                            + "xmlns:a=\"" + A_NS + "\">" + output.toString(StandardCharsets.UTF_8)
                            + "</root>").getBytes(StandardCharsets.UTF_8)));
            return (Element) document.getDocumentElement().getFirstChild();
        } catch (Exception exception) {
            throw new IllegalStateException("executor transform XML round-trip failed", exception);
        }
    }
}
