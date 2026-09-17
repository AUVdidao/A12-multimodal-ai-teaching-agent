package com.auvdidao.a12.pptengine.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PptxBuildValidatorSlideClosureTest {

    @TempDir
    Path temp;

    @Test
    void rejectsPhysicalOrphanSlideNotReferencedByPresentation() throws Exception {
        Path artifact = temp.resolve("orphan-slide.pptx");
        writePackage(artifact);

        PptxPackage pack = PptxPackage.read(artifact);
        List<ExecutorModels.ExecutorDiagnostic> diagnostics = new ArrayList<>();
        new PptxBuildValidator().validateSlidePartClosure(pack, pack.slidePaths(), diagnostics);

        assertThat(diagnostics).extracting(ExecutorModels.ExecutorDiagnostic::code)
                .containsExactly("PPTX_SLIDE_COUNT_MISMATCH");
        assertThat(diagnostics.get(0).safeDetails())
                .containsEntry("physicalSlideCount", "2")
                .containsEntry("referencedSlideCount", "1")
                .containsEntry("orphanSlideCount", "1")
                .containsEntry("missingSlideCount", "0");
    }

    private void writePackage(Path path) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip, "[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                    </Types>""");
            entry(zip, "ppt/presentation.xml", """
                    <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <p:sldIdLst><p:sldId id="1" r:id="rId1"/></p:sldIdLst>
                    </p:presentation>""");
            entry(zip, "ppt/_rels/presentation.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/slides/slide1.xml", "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>");
            entry(zip, "ppt/slides/slide2.xml", "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>");
        }
    }

    private void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
