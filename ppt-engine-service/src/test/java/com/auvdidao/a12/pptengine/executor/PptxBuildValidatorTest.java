package com.auvdidao.a12.pptengine.executor;

import com.auvdidao.a12.pptengine.contract.ContractTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PptxBuildValidatorTest {

    @TempDir
    Path temp;

    @Test
    void acceptsOneNormalizedDefaultAndOverride() throws Exception {
        List<ExecutorModels.ExecutorDiagnostic> diagnostics = validate("""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="PNG" ContentType="image/png"/>
                  <Override PartName="/ppt/slides/slide1.xml" ContentType="application/xml"/>
                </Types>""", List.of("ppt/slides/slide1.xml"));

        assertThat(diagnostics).isEmpty();
    }

    @Test
    void rejectsDuplicateDefaultEvenWhenContentTypeIsIdentical() throws Exception {
        List<ExecutorModels.ExecutorDiagnostic> diagnostics = validate("""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="png" ContentType="image/png"/>
                  <Default Extension="PNG" ContentType="image/png"/>
                </Types>""", List.of("image/a.png"));

        assertThat(diagnostics).extracting(ExecutorModels.ExecutorDiagnostic::code)
                .containsOnly(ContractTypes.DiagnosticCode.PPTX_CONTENT_TYPES_INVALID.name());
    }

    @Test
    void rejectsConflictingDefaultAndDuplicateOverride() throws Exception {
        List<ExecutorModels.ExecutorDiagnostic> diagnostics = validate("""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="png" ContentType="image/png"/>
                  <Default Extension="PNG" ContentType="application/octet-stream"/>
                  <Override PartName="/ppt/slides/slide1.xml" ContentType="application/xml"/>
                  <Override PartName="/ppt/slides/slide1.xml" ContentType="text/xml"/>
                </Types>""", List.of("ppt/slides/slide1.xml", "image/a.png"));

        assertThat(diagnostics).hasSize(2);
        assertThat(diagnostics).allMatch(item -> item.impact() == ContractTypes.DiagnosticImpact.JOB_BLOCKING);
    }

    @Test
    void rejectsIllegalOpcPartNameCharactersEncodingAndLength() throws Exception {
        for (String partName : List.of(
                "/ppt/slides/slide%31.xml",
                "/ppt/slides/slide#1.xml",
                "/ppt/slides/slide?x.xml",
                "/ppt/slides/bad:name.xml",
                "/ppt/slides/slide1.xml.",
                "/ppt/slides/" + "x".repeat(250) + ".xml")) {
            List<ExecutorModels.ExecutorDiagnostic> diagnostics = validate("""
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Override PartName="%s" ContentType="application/xml"/>
                    </Types>""".formatted(partName), List.of("ppt/slides/slide1.xml"));
            assertThat(diagnostics).extracting(ExecutorModels.ExecutorDiagnostic::code)
                    .contains(ContractTypes.DiagnosticCode.PPTX_CONTENT_TYPES_INVALID.name());
        }
    }

    @Test
    void relationshipTargetCannotEscapePackageRoot() {
        assertThatThrownBy(() -> PptxPackage.resolveTarget(
                "ppt/slides/slide1.xml", "../../../../outside.xml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes package root");
    }

    private List<ExecutorModels.ExecutorDiagnostic> validate(String contentTypes, List<String> entries)
            throws Exception {
        Path artifact = temp.resolve("fixture.pptx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(artifact))) {
            entry(zip, "[Content_Types].xml", contentTypes);
            for (String entry : entries) {
                entry(zip, entry, "content");
            }
        }
        PptxPackage pack = PptxPackage.read(artifact);
        List<ExecutorModels.ExecutorDiagnostic> diagnostics = new ArrayList<>();
        new PptxBuildValidator().validateContentTypes(pack, diagnostics);
        return diagnostics;
    }

    private void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
