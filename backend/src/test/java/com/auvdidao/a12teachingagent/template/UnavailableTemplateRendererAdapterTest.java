package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnavailableTemplateRendererAdapterTest {
    @Test
    void developmentFixturePublishesOnControlledOutputVolumeAndCleansWorkDirectory() throws Exception {
        Path root = Files.createTempDirectory("renderer-fixture-test-");
        try {
            UnavailableTemplateRendererAdapter adapter = adapter(root, Files::move);
            TemplateRenderer.RenderResult result = adapter.render(source());
            assertTrue(result.implemented());
            assertEquals(2, result.slideCount());
            assertTrue(result.previewReference().startsWith("template-renders/"));
            Path published = root.resolve("template-renders")
                    .resolve(Path.of(result.previewReference()).getFileName().toString());
            assertTrue(Files.isRegularFile(published));
            assertTrue(Files.size(published) > 0);
            try (var children = Files.list(root.resolve("template-renders"))) {
                assertEquals(1, children.count());
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void unsupportedAtomicMoveFallsBackToSameVolumeMove() throws Exception {
        Path root = Files.createTempDirectory("renderer-fixture-fallback-test-");
        AtomicBoolean firstMove = new AtomicBoolean(true);
        try {
            UnavailableTemplateRendererAdapter.MoveOperation move = (source, target, options) -> {
                if (firstMove.getAndSet(false) && options.length == 1
                        && options[0] == StandardCopyOption.ATOMIC_MOVE) {
                    throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
                }
                return Files.move(source, target, options);
            };
            TemplateRenderer.RenderResult result = adapter(root, move).render(source());
            assertTrue(result.implemented());
            assertFalse(firstMove.get());
            assertTrue(Files.exists(root.resolve("template-renders")
                    .resolve(Path.of(result.previewReference()).getFileName().toString())));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void failedPublishPreservesSafeCauseTypeAndCleansTemporaryDirectory() throws Exception {
        Path root = Files.createTempDirectory("renderer-fixture-failure-test-");
        try {
            UnavailableTemplateRendererAdapter.MoveOperation move = (source, target, options) -> {
                throw new IOException("hidden path must not be returned");
            };
            TemplateRenderer.RenderResult result = adapter(root, move).render(source());
            assertFalse(result.implemented());
            assertEquals("development-fixture-io-failed", result.adapter());
            assertTrue(result.message().contains("IOException"));
            assertFalse(result.message().contains(root.toString()));
            Path output = root.resolve("template-renders");
            if (Files.exists(output)) {
                try (var children = Files.list(output)) {
                    assertEquals(0, children.count());
                }
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void fixtureRemainsClosedOutsideDev() throws Exception {
        Path root = Files.createTempDirectory("renderer-fixture-profile-test-");
        try {
            TemplateRenderer.RenderResult result = new UnavailableTemplateRendererAdapter(
                    "", 10, properties(root), parser(), true, "test", Files::move
            ).render(source());
            assertFalse(result.implemented());
            assertEquals("renderer-not-configured", result.adapter());
        } finally {
            deleteTree(root);
        }
    }

    private UnavailableTemplateRendererAdapter adapter(Path root, UnavailableTemplateRendererAdapter.MoveOperation move) {
        return new UnavailableTemplateRendererAdapter("", 10, properties(root), parser(), true, "dev", move);
    }

    private StorageProperties properties(Path root) {
        StorageProperties properties = new StorageProperties();
        properties.setUploadDir(root.toString());
        return properties;
    }

    private TemplateParser parser() {
        return resource -> new TemplateParser.ParseResult("{\"slideCount\":2}", "snapshot-sha", 2);
    }

    private ByteArrayResource source() {
        return new ByteArrayResource("not-a-real-pptx-test-input".getBytes());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }
}
