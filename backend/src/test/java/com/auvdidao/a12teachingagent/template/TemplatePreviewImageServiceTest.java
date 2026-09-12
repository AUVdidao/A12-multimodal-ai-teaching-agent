package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatePreviewImageServiceTest {
    @Test
    void rendersAndDeletesBoundedPngInCanonicalTemplateNamespace() throws Exception {
        Path root = Files.createTempDirectory("lessonforge-preview-test-");
        Path renders = root.resolve("template-renders");
        Files.createDirectories(renders);
        Path pdf = renders.resolve("rendered.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        StorageProperties properties = new StorageProperties();
        properties.setUploadDir(root.toString());
        TemplatePreviewImageService service = new TemplatePreviewImageService(properties, root.toString());
        TemplatePreviewImageService.PreviewImage preview = service.create("template-renders/rendered.pdf");

        assertNotNull(preview.sha256());
        assertTrue(preview.reference().matches("template-renders/preview-[a-f0-9-]{36}\\.png"));
        assertTrue(preview.sizeBytes() > 0);
        assertNotNull(ImageIO.read(root.resolve(preview.reference()).toFile()));

        service.delete(preview);
        assertFalse(Files.exists(root.resolve(preview.reference())));
    }
}
