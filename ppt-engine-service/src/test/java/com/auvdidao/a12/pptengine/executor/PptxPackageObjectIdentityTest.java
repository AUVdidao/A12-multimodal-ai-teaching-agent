package com.auvdidao.a12.pptengine.executor;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PptxPackageObjectIdentityTest {

    @Test
    void resolvesOnlyExactObjectIdAndNeverNameOnly() throws Exception {
        Document slide = slide("""
                <p:sp><p:nvSpPr><p:cNvPr id="7" name="target"/></p:nvSpPr><p:spPr/></p:sp>
                <p:pic><p:nvPicPr><p:cNvPr id="8" name="7"/></p:nvPicPr><p:spPr/></p:pic>""");

        assertThat(PptxPackage.findObject(slide, "7").getLocalName()).isEqualTo("sp");
        assertThat(PptxPackage.findObject(slide, "target")).isNull();
    }

    @Test
    void duplicateExactObjectIdFailsClosedInsteadOfPickingFirst() throws Exception {
        Document slide = slide("""
                <p:sp><p:nvSpPr><p:cNvPr id="7" name="one"/></p:nvSpPr><p:spPr/></p:sp>
                <p:sp><p:nvSpPr><p:cNvPr id="7" name="two"/></p:nvSpPr><p:spPr/></p:sp>""");

        assertThat(PptxPackage.findObject(slide, "7")).isNull();
    }

    @Test
    void resolvesStableParserPathToNativeObjectTreeWithoutNameMatching() throws Exception {
        Document slide = slide("""
                <p:sp><p:nvSpPr><p:cNvPr id="41" name="first"/></p:nvSpPr><p:spPr/></p:sp>
                <p:grpSp><p:nvGrpSpPr><p:cNvPr id="42" name="group"/></p:nvGrpSpPr><p:grpSpPr/>
                    <p:sp><p:nvSpPr><p:cNvPr id="43" name="nested"/></p:nvSpPr><p:spPr/></p:sp>
                </p:grpSp>
                <p:pic><p:nvPicPr><p:cNvPr id="44" name="last"/></p:nvPicPr><p:spPr/></p:pic>""");

        assertThat(PptxPackage.findObject(slide, "slide-1.shape-2").getLocalName()).isEqualTo("grpSp");
        assertThat(PptxPackage.findObject(slide, "slide-1.shape-2.shape-1").getLocalName()).isEqualTo("sp");
        assertThat(PptxPackage.findObject(slide, "slide-1.shape-2.shape-1")
                .getElementsByTagNameNS("*", "cNvPr").item(0)
                .getAttributes().getNamedItem("id").getNodeValue()).isEqualTo("43");
        assertThat(PptxPackage.findObject(slide, "slide-1.shape-9")).isNull();
    }

    private Document slide(String children) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream((
                "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                        + "<p:cSld><p:spTree>" + children + "</p:spTree></p:cSld></p:sld>")
                .getBytes(StandardCharsets.UTF_8)));
    }

}
