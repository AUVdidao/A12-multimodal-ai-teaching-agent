package com.auvdidao.a12.pptengine.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class PptxPackageEntryNameTest {

    @TempDir
    Path temp;

    @Test
    void acceptsStandardDirectoryEntriesWithoutTreatingThemAsPackageParts() throws Exception {
        Path input = temp.resolve("directories.pptx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            for (String directory : List.of("_rels/", "ppt/", "ppt/slides/")) {
                zip.putNextEntry(new ZipEntry(directory));
                zip.closeEntry();
            }
            entry(zip, "ppt/slides/slide1.xml");
        }
        PptxPackage pack = PptxPackage.read(input);
        assertThat(pack.entryNames()).containsExactly("ppt/slides/slide1.xml");
        assertThat(pack.bytes("ppt/slides/slide1.xml")).containsExactly((byte) 'x');
        Path output = temp.resolve("roundtrip.pptx");
        pack.write(output);
        assertThat(PptxPackage.read(output).entryNames()).isEqualTo(pack.entryNames());
        assertThatThrownBy(() -> pack.put("ppt/", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stillRejectsUnsafeDirectoryPathsAndDirectoryPayloads() {
        for (String name : List.of("/", "../", "ppt/../", "ppt//", "ppt/./", "C:/",
                "ppt\\slides/", "ppt/%2e%2e/", "ppt/" + "x".repeat(251) + "/")) {
            assertThatThrownBy(() -> readDirectory(name))
                    .isInstanceOfAny(IllegalArgumentException.class, java.io.IOException.class);
        }
        assertThatThrownBy(() -> readSingle("ppt/"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("directory entry has content");
    }

    @Test
    void rejectsDirectoryCaseAndFileDirectoryCollisionsInEitherOrder() throws Exception {
        int index = 0;
        for (List<String> names : List.of(List.of("ppt/", "PPT/"),
                List.of("ppt/", "ppt"), List.of("ppt", "ppt/"))) {
            Path input = temp.resolve("directory-collision-" + index++ + ".pptx");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
                for (String name : names) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.closeEntry();
                }
            }
            assertThatThrownBy(() -> PptxPackage.read(input))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("normalized duplicate");
        }
    }

    private void readDirectory(String name) throws Exception {
        Path input = temp.resolve("directory.pptx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry(name));
            zip.closeEntry();
        }
        PptxPackage.read(input);
    }

    @Test
    void rejectsAbsoluteTraversalControlReservedPercentAndOverlongEntries() {
        for (String name : List.of(
                "/absolute.xml", "ppt\\slides\\slide1.xml", "ppt//slide1.xml",
                "ppt/./slide1.xml", "ppt/../slide1.xml", "ppt/slide1.xml.",
                "ppt/slide1.xml ", "ppt/slide\u0001.xml", "ppt/slide 1.xml",
                "ppt/slide:1.xml", "ppt/slide%31.xml", "ppt/" + "x".repeat(250) + ".xml")) {
            assertThatThrownBy(() -> readSingle(name))
                    .isInstanceOfAny(IllegalArgumentException.class, java.io.IOException.class);
        }
    }

    @Test
    void rejectsNormalizedCaseCollisionInsteadOfKeepingTwoPaths() throws Exception {
        Path zipPath = temp.resolve("collision.pptx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            entry(zip, "ppt/slides/slide1.xml");
            entry(zip, "PPT/slides/slide1.xml");
        }

        assertThatThrownBy(() -> PptxPackage.read(zipPath))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("normalized duplicate");
    }

    @Test
    void rejectsExternalEntityXmlBeforeAnyDocumentIsTrusted() throws Exception {
        Path zipPath = temp.resolve("xxe.pptx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zip.putNextEntry(new ZipEntry("ppt/slides/slide1.xml"));
            zip.write("<!DOCTYPE sld [<!ENTITY xxe SYSTEM \"file:///secret\">]><sld>&xxe;</sld>"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        PptxPackage pack = PptxPackage.read(zipPath);

        assertThatThrownBy(() -> pack.document("ppt/slides/slide1.xml"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("invalid XML");
    }

    private void readSingle(String name) throws Exception {
        Path zipPath = temp.resolve("single-" + Math.abs(name.hashCode()) + ".pptx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            entry(zip, name);
        }
        PptxPackage.read(zipPath);
    }

    private void entry(ZipOutputStream zip, String name) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write('x');
        zip.closeEntry();
    }
}
