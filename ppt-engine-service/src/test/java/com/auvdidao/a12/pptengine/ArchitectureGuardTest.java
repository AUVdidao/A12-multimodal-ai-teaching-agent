package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ContractTypes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureGuardTest {

    private final Path projectRoot = Path.of("src", "main").toAbsolutePath().getParent().getParent();

    @Test
    void serviceDoesNotImportMainSystemProvidersOrPowerPointGenerators() throws IOException {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        String source;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            source = files.filter(Files::isRegularFile)
                    .filter(path -> !isExecutorSource(path))
                    .map(this::read)
                    .reduce("", String::concat);
        }
        assertThat(source)
                .doesNotContain("com.auvdidao.a12teachingagent")
                .doesNotContain("org.apache.poi")
                .doesNotContain("com.aspose")
                .doesNotContain("com.openai")
                .doesNotContain("moonshot")
                .doesNotContain("deepseek")
                .doesNotContain("zhipu")
                .doesNotContain("pptxgenjs")
                .doesNotContain("file-generator-service")
                .doesNotContain("jakarta.persistence")
                .doesNotContain("org.springframework.jdbc")
                .doesNotContain("java.nio.file.Path")
                .doesNotContain("java.nio.file.Files")
                .doesNotContain("java.io.FileInputStream")
                .doesNotContain("java.net.http.HttpClient")
                .doesNotContain("WebClient")
                .doesNotContain("RestClient")
                .doesNotContain("System.getenv")
                .doesNotContain("ProcessBuilder")
                .doesNotContain("Runtime.getRuntime")
                .doesNotContain("java.awt")
                .doesNotContain("javax.imageio")
                .doesNotContain("org.apache.batik")
                .doesNotContain("rasterize(")
                .doesNotContain("screenshot(");

        String pom = Files.readString(projectRoot.resolve("pom.xml"));
        assertThat(pom).doesNotContain("spring-boot-starter-data-jpa");
        assertThat(pom).doesNotContain("h2");
        assertThat(pom).doesNotContain("poi-ooxml");
        assertThat(pom).doesNotContain("aspose");
        assertThat(pom).doesNotContain("openai");
        assertThat(pom).doesNotContain("credential");
        assertThat(pom).doesNotContain("batik");
        assertThat(pom).doesNotContain("pdfbox");
        assertThat(pom).doesNotContain("imgscalr");
    }

    @Test
    void compositionOperationVocabularyHasNoRasterFallbackPath() {
        Set<String> operationTypes = Arrays.stream(ContractTypes.CompositionOperationType.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(operationTypes).containsExactlyInAnyOrder(
                "PRESERVE_BASE_OBJECT",
                "USE_OR_CLONE_COMPONENT_OBJECT",
                "FILL_TEXT_SLOT",
                "FILL_ASSET_SLOT");
        assertThat(operationTypes).noneMatch(name ->
                name.contains("RASTER") || name.contains("SCREENSHOT")
                        || name.contains("PNG") || name.contains("SVG")
                        || name.contains("FALLBACK"));
    }

    @Test
    void buildTreeContainsNoGeneratedPptx() throws IOException {
        Path target = projectRoot.resolve("target");
        if (Files.notExists(target)) {
            return;
        }
        try (Stream<Path> files = Files.walk(target)) {
            assertThat(files.noneMatch(path -> path.toString().toLowerCase().endsWith(".pptx"))).isTrue();
        }
    }

    private boolean isExecutorSource(Path path) {
        for (Path segment : path.normalize()) {
            if ("executor".equalsIgnoreCase(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
