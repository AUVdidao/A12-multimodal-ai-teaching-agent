package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.knowledge.KnowledgeIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class MaterialParseTransactionAtomicityTest {

    private static final long PROJECT_ID = 990001L;

    @Autowired
    private MaterialParseTransactionService transactionService;
    @Autowired
    private UploadedMaterialRepository materialRepository;
    @Autowired
    private MaterialPurposeRepository purposeRepository;
    @Autowired
    private ParseResultRepository parseResultRepository;
    @Autowired
    private KnowledgeChunkRepository chunkRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @SpyBean
    private KnowledgeIndexService knowledgeIndexService;

    private Long materialId;

    @AfterEach
    void cleanFixture() {
        if (materialId == null) {
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            chunkRepository.deleteByMaterialId(materialId);
            chunkRepository.flush();
            purposeRepository.deleteByMaterialId(materialId);
            purposeRepository.flush();
            parseResultRepository.findFirstByMaterialIdOrderByCreatedAtDescIdDesc(materialId)
                    .ifPresent(result -> parseResultRepository.deleteById(result.getId()));
            parseResultRepository.flush();
            materialRepository.deleteById(materialId);
            materialRepository.flush();
        });
        materialId = null;
    }

    @Test
    void indexingFailureRollsBackCompletionAndFailureTransactionMarksMaterialFailed() {
        Fixture fixture = createFixture();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("forced index failure");
        }).when(knowledgeIndexService).index(any(UploadedMaterial.class));

        assertThatThrownBy(() -> transactionService.complete(fixture.completion()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced index failure");

        PersistedState rolledBack = readState();
        assertThat(rolledBack.parseStatus()).isEqualTo(MaterialParseStatus.PROCESSING);
        assertThat(rolledBack.uploadStatus()).isEqualTo(UploadStatus.UPLOADED);
        assertThat(rolledBack.extractedText()).isEqualTo("old extracted text");
        assertThat(rolledBack.chunkCount()).isEqualTo(1);
        assertThat(rolledBack.sections()).containsExactly("old section");
        assertThat(rolledBack.chunkContents()).containsExactly("old chunk content");

        transactionService.fail(new MaterialParseTransactionService.ParseFailure(
                fixture.projectId(),
                fixture.materialId(),
                fixture.material(),
                fixture.result(),
                System.nanoTime()
        ));

        PersistedState failed = readState();
        assertThat(failed.parseStatus()).isEqualTo(MaterialParseStatus.FAILED);
        assertThat(failed.uploadStatus()).isEqualTo(UploadStatus.FAILED);
        assertThat(failed.chunkContents()).containsExactly("old chunk content");
    }

    @Test
    void successfulCompletionPersistsStateAndChunkCountTogether() {
        Fixture fixture = createFixture();

        transactionService.complete(fixture.completion());

        PersistedState completed = readState();
        assertThat(completed.parseStatus()).isEqualTo(MaterialParseStatus.SUCCEEDED);
        assertThat(completed.uploadStatus()).isEqualTo(UploadStatus.PARSED);
        assertThat(completed.extractedText()).isEqualTo("new extracted text");
        assertThat(completed.sections()).containsExactly("new section");
        assertThat(completed.chunkCount()).isEqualTo(completed.chunkContents().size());
        assertThat(completed.chunkContents())
                .isNotEmpty()
                .doesNotContain("old chunk content");
    }

    private Fixture createFixture() {
        Fixture fixture = new TransactionTemplate(transactionManager).execute(status -> {
            UploadedMaterial material = new UploadedMaterial();
            material.setProjectId(PROJECT_ID);
            material.setFileName("atomic-" + UUID.randomUUID() + ".md");
            material.setOriginalFileName("atomic.md");
            material.setFileExtension("md");
            material.setContentType("text/markdown");
            material.setFileType(MaterialFileType.MD);
            material.setFilePath("uploads/atomic.md");
            material.setFileSize(32L);
            material.setUploadStatus(UploadStatus.UPLOADED);
            material.setParseStatus(MaterialParseStatus.PROCESSING);
            material = materialRepository.saveAndFlush(material);

            MaterialPurpose purpose = new MaterialPurpose();
            purpose.setProjectId(PROJECT_ID);
            purpose.setMaterialId(material.getId());
            purpose.setPurposeType(PurposeType.TEXTBOOK_BASIS);
            purposeRepository.saveAndFlush(purpose);

            ParseResult result = new ParseResult();
            result.setMaterialId(material.getId());
            result.setSummary("old summary");
            result.setExtractedText("old extracted text");
            result.setSections(List.of("old section"));
            result.setChunkCount(1);
            result.setParseStatus(MaterialParseStatus.PROCESSING);
            result = parseResultRepository.saveAndFlush(result);

            KnowledgeChunk oldChunk = new KnowledgeChunk();
            oldChunk.setProjectId(PROJECT_ID);
            oldChunk.setMaterialId(material.getId());
            oldChunk.setChunkNo(1);
            oldChunk.setTitle("old section");
            oldChunk.setContent("old chunk content");
            oldChunk.setKeywords(List.of("old"));
            oldChunk.setUsageTypes(List.of(PurposeType.TEXTBOOK_BASIS));
            oldChunk.setSourceFilename(material.getFileName());
            chunkRepository.saveAndFlush(oldChunk);

            return new Fixture(
                    PROJECT_ID,
                    material.getId(),
                    material,
                    result,
                    new MaterialParseTransactionService.ParseCompletion(
                            PROJECT_ID,
                            material.getId(),
                            material,
                            result,
                            "new summary",
                            List.of("new"),
                            List.of("lesson"),
                            "new extracted text",
                            1,
                            List.of("new section"),
                            System.nanoTime()
                    )
            );
        });
        materialId = fixture.materialId();
        return fixture;
    }

    private PersistedState readState() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            UploadedMaterial material = materialRepository.findById(materialId).orElseThrow();
            ParseResult result = parseResultRepository
                    .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(materialId)
                    .orElseThrow();
            List<KnowledgeChunk> chunks = chunkRepository.findByMaterialIdOrderByChunkNoAsc(materialId);
            return new PersistedState(
                    result.getParseStatus(),
                    material.getUploadStatus(),
                    result.getExtractedText(),
                    result.getChunkCount(),
                    result.getSections(),
                    chunks.stream().map(KnowledgeChunk::getContent).toList()
            );
        });
    }

    private record Fixture(
            Long projectId,
            Long materialId,
            UploadedMaterial material,
            ParseResult result,
            MaterialParseTransactionService.ParseCompletion completion
    ) {
    }

    private record PersistedState(
            MaterialParseStatus parseStatus,
            UploadStatus uploadStatus,
            String extractedText,
            Integer chunkCount,
            List<String> sections,
            List<String> chunkContents
    ) {
    }
}
