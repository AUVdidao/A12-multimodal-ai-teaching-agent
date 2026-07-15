package com.auvdidao.a12.generator;

import com.auvdidao.a12.generator.GeneratorDtos.PackageEntry;
import com.auvdidao.a12.generator.GeneratorDtos.PackageRequest;
import com.auvdidao.a12.generator.GeneratorDtos.RenderRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GeneratorControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void createsParseableOfficeFilesAndReadableInteractivePackage() throws Exception {
        RenderRequest ppt = new RenderRequest("PPT", 1, "AI foundations", "AI", "Core", "AI deck",
                "{\"deckTitle\":\"AI deck\",\"slides\":[{\"title\":\"Core concepts\",\"points\":[\"Machine learning\"]}]}" );
        byte[] pptx = mockMvc.perform(post("/internal/file-generator/pptx").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(ppt))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(pptx).startsWith((byte) 'P', (byte) 'K');
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(pptx))) { assertThat(show.getSlides()).hasSize(1); }

        RenderRequest doc = new RenderRequest("DOCX", 1, "AI foundations", "AI", "Core", "AI plan",
                "{\"title\":\"AI plan\",\"sections\":[{\"title\":\"Goals\",\"paragraphs\":[\"Explain AI\"]}]}" );
        byte[] docx = mockMvc.perform(post("/internal/file-generator/docx").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(doc))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(docx).startsWith((byte) 'P', (byte) 'K');
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) { assertThat(document.getParagraphs()).isNotEmpty(); }

        RenderRequest interaction = new RenderRequest("INTERACTION", 1, "AI foundations", "AI", "Core", "Quiz",
                "{\"title\":\"Quiz\",\"instructions\":\"Choose one\",\"questions\":[{\"question\":\"AI?\",\"options\":[\"A\",\"B\"],\"correctOption\":0}]}" );
        byte[] html = mockMvc.perform(post("/internal/file-generator/interactive-html").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(interaction))).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML)).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(html)).contains("Quiz", "questions");

        PackageRequest packageRequest = new PackageRequest(List.of(new PackageEntry("lesson.html", "HTML", interaction)));
        byte[] zip = mockMvc.perform(post("/internal/file-generator/package").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(packageRequest))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) { assertThat(input.getNextEntry().getName()).isEqualTo("lesson.html"); }
    }
}
