package com.auvdidao.a12teachingagent.template;

import org.springframework.core.io.Resource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class UnavailableTemplateRendererAdapter implements TemplateRenderer {
    private final String executable;
    private final long timeoutSeconds;
    private final Path outputRoot;
    private final TemplateParser parser;
    private final boolean fixtureEnabled;
    private final String activeProfile;
    private final MoveOperation moveOperation;

    @FunctionalInterface
    interface MoveOperation {
        Path move(Path source, Path target, java.nio.file.CopyOption... options) throws IOException;
    }

    @Autowired
    public UnavailableTemplateRendererAdapter(
            @Value("${a12.template.renderer.executable:}") String executable,
            @Value("${a12.template.renderer.timeout-seconds:120}") long timeoutSeconds,
            com.auvdidao.a12teachingagent.material.storage.StorageProperties storageProperties,
            TemplateParser parser,
            @Value("${a12.template.renderer.fixture-enabled:false}") boolean fixtureEnabled,
            @Value("${spring.profiles.active:}") String activeProfile
    ) {
        this(executable, timeoutSeconds, storageProperties, parser, fixtureEnabled, activeProfile, Files::move);
    }

    UnavailableTemplateRendererAdapter(
            String executable,
            long timeoutSeconds,
            com.auvdidao.a12teachingagent.material.storage.StorageProperties storageProperties,
            TemplateParser parser,
            boolean fixtureEnabled,
            String activeProfile,
            MoveOperation moveOperation
    ) {
        this.executable = executable == null ? "" : executable.strip();
        this.timeoutSeconds = Math.max(1, Math.min(timeoutSeconds, 900));
        this.outputRoot = Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize().resolve("template-renders");
        this.parser = parser;
        this.fixtureEnabled = fixtureEnabled;
        this.activeProfile = activeProfile == null ? "" : activeProfile.strip();
        this.moveOperation = moveOperation == null ? Files::move : moveOperation;
    }

    @Override
    public RenderResult render(Resource source) {
        if (!executable.isBlank()) {
            return renderWithConfiguredOffice(source);
        }
        if (fixtureEnabled && "dev".equalsIgnoreCase(activeProfile)) {
            return renderDevelopmentFixture(source);
        }
        return RenderResult.notImplemented(
                "renderer-not-configured",
                "真实 PPTX 页面渲染适配器尚未配置；未生成预览，也未伪造渲染成功。"
        );
    }

    private RenderResult renderDevelopmentFixture(Resource source) {
        Path work = null;
        try {
            TemplateParser.ParseResult parsed = parser.parse(source);
            if (parsed.slideCount() <= 0 || parsed.checksum() == null) {
                return RenderResult.notImplemented("development-fixture-invalid-input",
                        "AUTO_RENDERER=NOT_READY; development fixture requires a valid Parser snapshot.");
            }
            Files.createDirectories(outputRoot);
            // Keep the generated PDF on the controlled output volume. This makes
            // the normal publish move same-volume even when the container's
            // default temp directory is a different filesystem.
            work = Files.createTempDirectory(outputRoot, ".a12-template-render-fixture-");
            Path input = work.resolve("source.pptx");
            try (InputStream stream = source.getInputStream()) {
                Files.copy(stream, input, StandardCopyOption.REPLACE_EXISTING);
            }
            String sourceSha256 = sha256(input);
            Path pdf = work.resolve("source.pdf");
            try (PDDocument document = new PDDocument()) {
                for (int i = 0; i < parsed.slideCount(); i++) document.addPage(new PDPage());
                document.save(pdf.toFile());
            }
            String filename = "fixture-" + UUID.randomUUID() + ".pdf";
            Path published = outputRoot.resolve(filename).normalize();
            if (!published.startsWith(outputRoot)) {
                return RenderResult.notImplemented("development-fixture-output-path-invalid",
                        "AUTO_RENDERER=NOT_READY; fixture output path failed the storage boundary.");
            }
            publishFixture(pdf, published);
            return new RenderResult(true, "development-fixture", "v1-AUTO_RENDERER_NOT_READY",
                    "template-renders/" + filename, parsed.slideCount(), sourceSha256, sha256(published),
                    Files.size(published), "AUTO_RENDERER=NOT_READY; synthetic preview only.");
        } catch (IOException exception) {
            return RenderResult.notImplemented("development-fixture-io-failed",
                    "AUTO_RENDERER=NOT_READY; development fixture I/O failed ("
                            + exception.getClass().getSimpleName() + "); no preview was published.");
        } finally {
            if (work != null) deleteTree(work);
        }
    }

    private void publishFixture(Path pdf, Path published) throws IOException {
        try {
            moveOperation.move(pdf, published, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            // The source is created below outputRoot, so this fallback remains
            // on the controlled volume and cannot publish outside the boundary.
            moveOperation.move(pdf, published, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private RenderResult renderWithConfiguredOffice(Resource source) {
        Path work = null;
        try {
            work = Files.createTempDirectory("a12-template-render-");
            Path input = work.resolve("source.pptx");
            try (InputStream stream = source.getInputStream()) {
                Files.copy(stream, input, StandardCopyOption.REPLACE_EXISTING);
            }
            String sourceSha256 = sha256(input);
            Process process = new ProcessBuilder(List.of(executable, "--headless", "--convert-to", "pdf",
                    "--outdir", work.toString(), input.toString()))
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return RenderResult.notImplemented("renderer-timeout", "Configured PPTX renderer timed out; no preview was published.");
            }
            if (process.exitValue() != 0) {
                return RenderResult.notImplemented("renderer-process-failed", "Configured PPTX renderer failed; no preview was published.");
            }
            Path pdf = work.resolve("source.pdf");
            if (!Files.isRegularFile(pdf) || Files.size(pdf) <= 0) {
                return RenderResult.notImplemented("renderer-output-missing", "Configured PPTX renderer produced no usable PDF preview.");
            }
            int slideCount;
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                slideCount = document.getNumberOfPages();
            }
            if (slideCount <= 0) {
                return RenderResult.notImplemented("renderer-page-count-invalid", "Configured PPTX renderer produced an empty preview.");
            }
            Files.createDirectories(outputRoot);
            String filename = UUID.randomUUID() + ".pdf";
            Path published = outputRoot.resolve(filename).normalize();
            if (!published.startsWith(outputRoot)) {
                return RenderResult.notImplemented("renderer-output-path-invalid", "Renderer output path failed the storage boundary.");
            }
            Files.move(pdf, published, StandardCopyOption.ATOMIC_MOVE);
            long size = Files.size(published);
            String outputSha256 = sha256(published);
            return new RenderResult(true, "libreoffice-cli", "v1", "template-renders/" + filename,
                    slideCount, sourceSha256, outputSha256, size, null);
        } catch (IOException exception) {
            return RenderResult.notImplemented("renderer-io-failed", "Configured PPTX renderer could not be executed safely.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return RenderResult.notImplemented("renderer-interrupted", "Configured PPTX renderer was interrupted safely.");
        } finally {
            if (work != null) {
                deleteTree(work);
            }
        }
    }

    private String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
