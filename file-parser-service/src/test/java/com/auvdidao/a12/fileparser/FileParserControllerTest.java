package com.auvdidao.a12.fileparser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FileParserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void parsesMarkdownBytesWithoutReceivingAFilePath() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "lesson.md", "text/markdown", "# 光合作用\n真实 Markdown 正文".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/internal/file-parser/parse")
                        .file(file)
                        .param("fileType", "MD")
                        .param("topic", "光合作用")
                        .param("usageTypes", "TEXTBOOK_BASIS", "CASE_MATERIAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary", containsString("Markdown UTF-8")))
                .andExpect(jsonPath("$.summary", containsString("真实 Markdown 正文")))
                .andExpect(jsonPath("$.teachingStages", contains("概念讲解", "课堂导入", "案例分析")));
    }

    @Test
    void extractsPdfText() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "lesson.pdf", "application/pdf", pdf("PdfServiceMarker"));

        mockMvc.perform(multipart("/internal/file-parser/parse").file(file).param("fileType", "PDF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary", containsString("PDFBox PDF 文本")))
                .andExpect(jsonPath("$.summary", containsString("PdfServiceMarker")));
    }

    @Test
    void returnsAnExplicitErrorCodeForInvalidText() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "bad.txt", "text/plain", new byte[]{(byte) 0xC3, 0x28});

        mockMvc.perform(multipart("/internal/file-parser/parse").file(file).param("fileType", "TXT"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UTF8"));
    }

    private static byte[] pdf(String marker) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(0))) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(marker);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
