package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.material.chunk.ChunkSplitter;
import com.auvdidao.a12teachingagent.material.chunk.TextCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexServiceTest {

    private static final Long MATERIAL_ID = 41L;
    private static final Long PROJECT_ID = 7L;

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private ParseResultRepository parseResultRepository;

    @Mock
    private MaterialPurposeRepository purposeRepository;

    private UploadedMaterial material;

    @BeforeEach
    void setUp() {
        material = new UploadedMaterial();
        material.setId(MATERIAL_ID);
        material.setProjectId(PROJECT_ID);
        material.setOriginalFileName("photosynthesis.md");

        MaterialPurpose purpose = new MaterialPurpose();
        purpose.setPurposeType(PurposeType.TEXTBOOK_BASIS);
        when(purposeRepository.findByMaterialIdOrderByIdAsc(MATERIAL_ID)).thenReturn(List.of(purpose));
        when(chunkRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void oversizedSectionIsSplitIntoBoundedChunks() {
        ParseResult parseResult = parsedResult(
                List.of("SECTION-A " + "a".repeat(150)),
                "fallback text"
        );

        index(parseResult, 40, 8, 5);
        List<KnowledgeChunk> chunks = capturedChunks();

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getContent()).isNotBlank();
            assertThat(chunk.getContent().length()).isLessThanOrEqualTo(40);
        });
        assertThat(chunks).extracting(KnowledgeChunk::getChunkNo)
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void multipleSectionsPreserveOrderWhileEachSectionIsSplit() {
        ParseResult parseResult = parsedResult(
                List.of("SECTION-A " + "a".repeat(90), "SECTION-B " + "b".repeat(90)),
                "fallback text"
        );

        index(parseResult, 40, 8, 5);
        List<KnowledgeChunk> chunks = capturedChunks();
        List<String> contents = chunks.stream().map(KnowledgeChunk::getContent).toList();
        int firstSectionB = contents.indexOf(contents.stream()
                .filter(content -> content.startsWith("SECTION-B"))
                .findFirst()
                .orElseThrow());

        assertThat(firstSectionB).isGreaterThan(0);
        assertThat(contents.subList(0, firstSectionB)).allMatch(content -> !content.contains("SECTION-B"));
        assertThat(contents.subList(firstSectionB, contents.size()))
                .allMatch(content -> !content.contains("SECTION-A"));
        assertThat(chunks).extracting(KnowledgeChunk::getChunkNo)
                .containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void emptySectionsFallBackToExtractedText() {
        ParseResult parseResult = parsedResult(
                List.of(),
                "FALLBACK-TEXT " + "x".repeat(90)
        );

        index(parseResult, 40, 8, 5);

        assertThat(capturedChunks().get(0).getContent()).startsWith("FALLBACK-TEXT");
    }

    @Test
    void blankSectionsAreFilteredWithoutSyntheticContent() {
        ParseResult parseResult = mock(ParseResult.class);
        when(parseResult.getSections()).thenReturn(Arrays.asList(null, "  ", "REAL-SECTION"));
        when(parseResult.getExtractedText()).thenReturn("fallback text");
        when(parseResult.getParseStatus()).thenReturn(MaterialParseStatus.SUCCEEDED);
        when(parseResult.getKeywords()).thenReturn(List.of());

        index(parseResult, 40, 8, 5);

        List<KnowledgeChunk> chunks = capturedChunks();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).isEqualTo("REAL-SECTION");
        assertThat(chunks.get(0).getContent())
                .doesNotContain("核心摘要:", "教学应用:", "目标关联:");
    }

    @Test
    void repeatedIndexingReplacesChunksWithContinuousNumbers() {
        ParseResult parseResult = parsedResult(
                List.of("SECTION-A " + "a".repeat(90)),
                "fallback text"
        );

        index(parseResult, 40, 8, 5);
        index(parseResult, 40, 8, 5);

        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository, times(2)).saveAll(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
        for (List<KnowledgeChunk> batch : captor.getAllValues()) {
            assertThat(batch).extracting(KnowledgeChunk::getChunkNo)
                    .containsExactly(1, 2, 3);
        }
        verify(chunkRepository, times(2)).deleteByMaterialId(MATERIAL_ID);
    }

    private List<KnowledgeChunk> capturedChunks() {
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private void index(ParseResult parseResult, int maxChars, int overlapChars, int minUsefulChars) {
        when(parseResultRepository.findFirstByMaterialIdOrderByCreatedAtDescIdDesc(MATERIAL_ID))
                .thenReturn(Optional.of(parseResult));
        new KnowledgeIndexService(
                chunkRepository,
                parseResultRepository,
                purposeRepository,
                new ChunkSplitter(new TextCleaner(), maxChars, overlapChars, minUsefulChars)
        ).index(material);
    }

    private ParseResult parsedResult(List<String> sections, String extractedText) {
        ParseResult result = new ParseResult();
        result.setMaterialId(MATERIAL_ID);
        result.setParseStatus(MaterialParseStatus.SUCCEEDED);
        result.setExtractedText(extractedText);
        result.setSections(sections);
        result.setKeywords(List.of());
        return result;
    }
}
