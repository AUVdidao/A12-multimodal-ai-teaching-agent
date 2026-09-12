package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Converts the first page of the Office-rendered PDF into a short-lived PNG
 * under the same canonical shared-volume namespace consumed by Go. The PDF
 * remains the durable render artifact; the PNG is only the bounded provider
 * input and is deleted after the one-use model lease completes.
 */
@Component
public final class TemplatePreviewImageService {
    private static final Pattern PDF_REFERENCE = Pattern.compile(
            "(?i)^template-renders/[a-z0-9][a-z0-9._-]{0,239}\\.pdf$");
    private static final Pattern PNG_REFERENCE = Pattern.compile(
            "(?i)^template-renders/preview-[a-f0-9-]{36}\\.png$");
    private static final int RENDER_DPI = 96;
    private static final int MAX_DIMENSION = 4096;

    private final Path outputRoot;
    private final long maxSourceBytes;

    public TemplatePreviewImageService(
            StorageProperties storageProperties,
            @Value("${a12.template.renderer.output-dir:}") String outputDirectory
    ) {
        String root = outputDirectory == null || outputDirectory.isBlank()
                ? storageProperties.getUploadDir() : outputDirectory;
        this.outputRoot = Path.of(root).toAbsolutePath().normalize();
        this.maxSourceBytes = Math.max(1, storageProperties.getMaxFileSize());
    }

    public PreviewImage create(String previewReference) {
        if (previewReference == null || !PDF_REFERENCE.matcher(previewReference).matches()) {
            throw new IllegalArgumentException("ANALYZER_PREVIEW_REFERENCE_INVALID");
        }
        Path pdf = outputRoot.resolve(previewReference).normalize();
        if (!pdf.startsWith(outputRoot) || !Files.isRegularFile(pdf)) {
            throw new IllegalArgumentException("ANALYZER_PREVIEW_NOT_FOUND");
        }
        try {
            if (Files.size(pdf) <= 0 || Files.size(pdf) > maxSourceBytes) {
                throw new IllegalArgumentException("ANALYZER_PREVIEW_SIZE_INVALID");
            }
            Path directory = outputRoot.resolve("template-renders").normalize();
            Files.createDirectories(directory);
            String name = "preview-" + UUID.randomUUID() + ".png";
            Path temporary = Files.createTempFile(directory, ".a12-template-preview-", ".tmp");
            Path published = directory.resolve(name).normalize();
            try {
                try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                    if (document.getNumberOfPages() <= 0) {
                        throw new IllegalArgumentException("ANALYZER_PREVIEW_PAGE_COUNT_INVALID");
                    }
                    BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, RENDER_DPI, ImageType.RGB);
                    if (image.getWidth() <= 0 || image.getHeight() <= 0
                            || image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
                        throw new IllegalArgumentException("ANALYZER_PREVIEW_DIMENSIONS_INVALID");
                    }
                    if (!ImageIO.write(image, "png", temporary.toFile())) {
                        throw new IllegalArgumentException("ANALYZER_PREVIEW_PNG_UNAVAILABLE");
                    }
                }
                moveIntoPlace(temporary, published);
                long size = Files.size(published);
                if (size <= 0 || size > 8L * 1024 * 1024) {
                    throw new IllegalArgumentException("ANALYZER_PREVIEW_PNG_SIZE_INVALID");
                }
                return new PreviewImage("template-renders/" + name, sha256(published), size, "image/png");
            } catch (RuntimeException | IOException exception) {
                Files.deleteIfExists(temporary);
                Files.deleteIfExists(published);
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalArgumentException("ANALYZER_PREVIEW_RENDER_FAILED", exception);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("ANALYZER_PREVIEW_RENDER_FAILED", exception);
        }
    }

    public void delete(PreviewImage preview) {
        if (preview == null || preview.reference() == null || !PNG_REFERENCE.matcher(preview.reference()).matches()) return;
        Path path = outputRoot.resolve(preview.reference()).normalize();
        if (!path.startsWith(outputRoot)) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The Go-side SHA/size gate prevents an untrusted leftover from
            // being used; a later controlled cleanup may remove it.
        }
    }

    private static void moveIntoPlace(Path temporary, Path published) throws IOException {
        try {
            Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, published, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record PreviewImage(String reference, String sha256, long sizeBytes, String mediaType) { }
}
