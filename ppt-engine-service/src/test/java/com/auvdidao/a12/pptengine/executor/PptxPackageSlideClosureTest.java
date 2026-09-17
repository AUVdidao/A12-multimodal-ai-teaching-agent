package com.auvdidao.a12.pptengine.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PptxPackageSlideClosureTest {

    @TempDir
    Path temp;

    @Test
    void materializeMultiPageThenWriteKeepsPhysicalAndPresentationSlideClosureEqual() throws Exception {
        Path input = temp.resolve("source.pptx");
        writePackage(input);

        PptxPackage pack = PptxPackage.read(input);
        List<String> targetSlides = pack.materializeSlides(List.of(1, 1, 1));
        pack.retainOnlySlides(targetSlides);
        Path output = temp.resolve("output.pptx");
        pack.write(output);

        PptxPackage roundTrip = PptxPackage.read(output);
        assertThat(roundTrip.physicalSlidePaths()).containsExactlyInAnyOrderElementsOf(targetSlides);
        assertThat(roundTrip.slidePaths()).containsExactlyElementsOf(targetSlides);
        assertThat(roundTrip.entryNames())
                .doesNotContain("ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/slides/slide3.xml",
                        "ppt/slides/_rels/slide1.xml.rels", "ppt/slides/_rels/slide2.xml.rels",
                        "ppt/slides/_rels/slide3.xml.rels");
        String contentTypes = new String(roundTrip.bytes("[Content_Types].xml"), StandardCharsets.UTF_8);
        assertThat(contentTypes).doesNotContain("/ppt/slides/slide1.xml", "/ppt/slides/slide2.xml",
                "/ppt/slides/slide3.xml");
    }

    private void writePackage(Path path) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                      <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                      <Override PartName="/ppt/slides/slide2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                      <Override PartName="/ppt/slides/slide3.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                    </Types>""");
            entry(zip, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/presentation.xml", """
                    <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <p:sldIdLst><p:sldId id="1" r:id="rId1"/></p:sldIdLst>
                    </p:presentation>""");
            entry(zip, "ppt/_rels/presentation.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                    </Relationships>""");
            for (int index = 1; index <= 3; index++) {
                entry(zip, "ppt/slides/slide" + index + ".xml",
                        "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>");
                entry(zip, "ppt/slides/_rels/slide" + index + ".xml.rels",
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>");
            }
        }
    }

    private void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
