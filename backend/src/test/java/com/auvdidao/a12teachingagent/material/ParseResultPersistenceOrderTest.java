package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.knowledge.KnowledgeIndexService;
import com.auvdidao.a12teachingagent.material.chunk.ChunkSplitter;
import com.auvdidao.a12teachingagent.material.chunk.TextCleaner;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
class ParseResultPersistenceOrderTest {

    private static final Long MATERIAL_ID = 41L;
    private static final Long PROJECT_ID = 7L;
    private static final List<String> SECTIONS = List.of(
            "第一章 光合作用",
            "第二章 光能转换",
            "第三章 影响因素"
    );

    @Autowired
    private ParseResultRepository parseResultRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void sectionsKeepTheirOrderAfterPersistenceContextIsCleared() {
        ParseResult saved = parseResultRepository.saveAndFlush(parseResult(SECTIONS));
        entityManager.clear();

        ParseResult reloaded = parseResultRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getSections()).containsExactlyElementsOf(SECTIONS);
    }

    @Test
    void longSectionsSurvivePersistenceContextClearWithoutTruncation() {
        String longSection = "光合作用真实解析段落：".repeat(600);
        String secondSection = "第二段：光能转换与有机物合成。";
        List<String> sections = List.of(longSection, secondSection);

        ParseResult saved = parseResultRepository.saveAndFlush(parseResult(sections));
        entityManager.clear();

        ParseResult reloaded = parseResultRepository.findById(saved.getId()).orElseThrow();

        assertThat(longSection).hasSizeGreaterThan(4000);
        assertThat(reloaded.getSections()).containsExactly(longSection, secondSection);
        assertThat(reloaded.getSections().get(0)).hasSize(longSection.length());
    }

    @Test
    void reindexUsesPersistedSectionOrderForContinuousChunkNumbers() {
        ParseResult saved = parseResultRepository.saveAndFlush(parseResult(SECTIONS));
        entityManager.clear();
        ParseResult reloaded = parseResultRepository.findById(saved.getId()).orElseThrow();

        KnowledgeChunkRepository chunkRepository = mock(KnowledgeChunkRepository.class);
        ParseResultRepository indexParseResultRepository = mock(ParseResultRepository.class);
        MaterialPurposeRepository purposeRepository = mock(MaterialPurposeRepository.class);
        UploadedMaterial material = material();
        MaterialPurpose purpose = new MaterialPurpose();
        purpose.setPurposeType(PurposeType.TEXTBOOK_BASIS);

        when(indexParseResultRepository.findFirstByMaterialIdOrderByCreatedAtDescIdDesc(MATERIAL_ID))
                .thenReturn(Optional.of(reloaded));
        when(purposeRepository.findByMaterialIdOrderByIdAsc(MATERIAL_ID)).thenReturn(List.of(purpose));
        when(chunkRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        new KnowledgeIndexService(
                chunkRepository,
                indexParseResultRepository,
                purposeRepository,
                new ChunkSplitter(new TextCleaner(), 1000, 120, 1)
        ).index(material);

        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(chunkRepository).saveAll(captor.capture());
        List<KnowledgeChunk> chunks = captor.getValue();

        assertThat(chunks).extracting(KnowledgeChunk::getChunkNo).containsExactly(1, 2, 3);
        assertThat(chunks).extracting(KnowledgeChunk::getContent).containsExactlyElementsOf(SECTIONS);
    }

    private ParseResult parseResult(List<String> sections) {
        ParseResult result = new ParseResult();
        result.setMaterialId(MATERIAL_ID);
        result.setParseStatus(MaterialParseStatus.SUCCEEDED);
        result.setExtractedText(String.join("\n", sections));
        result.setSections(sections);
        result.setKeywords(List.of());
        return result;
    }

    private UploadedMaterial material() {
        UploadedMaterial material = new UploadedMaterial();
        material.setId(MATERIAL_ID);
        material.setProjectId(PROJECT_ID);
        material.setOriginalFileName("photosynthesis.md");
        return material;
    }
}
