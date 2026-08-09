package com.auvdidao.a12.fileparser;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileParserServiceBoundaryTest {

    private static final int MAX_EXTRACTED_CHARACTERS = 1_000_000;
    private static final int ANALYSIS_TEXT_LIMIT = 32_000;

    private final FileParserService service = new FileParserService(
            20L * 1024 * 1024,
            MAX_EXTRACTED_CHARACTERS
    );

    @Test
    void preservesMoreThanFiveHundredThousandCharactersWhileBoundingAnalysisText() {
        String content = "HEAD_MARKER\n"
                + "a".repeat(220_000)
                + "\nCHAPTER_3_RAG_MARKER\n"
                + "b".repeat(320_000)
                + "\nTAIL_MARKER";

        FileParserService.ParseResponse response = service.parse(
                file("lesson.txt", content),
                "TXT",
                "Photosynthesis",
                List.of()
        );

        assertThat(response.extractedText())
                .hasSizeGreaterThan(500_000)
                .contains("HEAD_MARKER", "CHAPTER_3_RAG_MARKER", "TAIL_MARKER");
        assertThat(response.analysisText()).hasSizeLessThanOrEqualTo(ANALYSIS_TEXT_LIMIT);
    }

    @Test
    void keepsTheOneMillionCharacterBoundaryWithoutSplittingASurrogatePair() {
        String content = "H".repeat(MAX_EXTRACTED_CHARACTERS - 1)
                + "\uD83D\uDE00"
                + "TAIL_MARKER";

        FileParserService.ParseResponse response = service.parse(
                file("lesson.txt", content),
                "TXT",
                "Photosynthesis",
                List.of()
        );

        assertThat(response.extractedText()).hasSize(MAX_EXTRACTED_CHARACTERS - 1);
        assertThat(response.extractedText()).doesNotContain("TAIL_MARKER");
        assertThat(response.extractedText()).doesNotContain("\uD83D");
        assertThat(response.extractedText()).doesNotContain("\uDE00");
        assertThat(response.analysisText()).hasSizeLessThanOrEqualTo(ANALYSIS_TEXT_LIMIT);
    }

    @Test
    void appliesTheSameBoundToTxtAndMarkdown() {
        String body = "HEAD_MARKER\n" + "x".repeat(550_000) + "\nTAIL_MARKER";

        FileParserService.ParseResponse txt = service.parse(
                file("lesson.txt", body),
                "TXT",
                "Photosynthesis",
                List.of()
        );
        FileParserService.ParseResponse markdown = service.parse(
                file("lesson.md", "# Photosynthesis\n\n" + body),
                "MD",
                "Photosynthesis",
                List.of()
        );

        assertThat(txt.extractedText()).hasSizeGreaterThan(500_000).contains("TAIL_MARKER");
        assertThat(markdown.extractedText()).hasSizeGreaterThan(500_000).contains("TAIL_MARKER");
        assertThat(txt.extractedText()).hasSizeLessThanOrEqualTo(MAX_EXTRACTED_CHARACTERS);
        assertThat(markdown.extractedText()).hasSizeLessThanOrEqualTo(MAX_EXTRACTED_CHARACTERS);
        assertThat(txt.analysisText()).hasSizeLessThanOrEqualTo(ANALYSIS_TEXT_LIMIT);
        assertThat(markdown.analysisText()).hasSizeLessThanOrEqualTo(ANALYSIS_TEXT_LIMIT);
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile(
                "file",
                name,
                name.endsWith(".md") ? "text/markdown" : "text/plain",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
