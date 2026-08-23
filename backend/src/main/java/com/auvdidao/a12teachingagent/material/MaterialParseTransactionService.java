package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.knowledge.KnowledgeIndexService;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.ParseResultResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Keeps database state transitions short and separate from file parsing and AI calls.
 */
@Service
public class MaterialParseTransactionService {

    private static final String FAILURE_REASON =
            "Prototype parsing could not be completed. Please retry.";
    private static final String STALE_PROCESSING_FAILURE_REASON =
            "The previous material parsing attempt became inactive. Please retry.";

    private final MaterialService materialService;
    private final UploadedMaterialRepository materialRepository;
    private final MaterialPurposeRepository purposeRepository;
    private final ParseResultRepository parseResultRepository;
    private final KnowledgeIndexService knowledgeIndexService;

    @Value("${a12.material-parser.processing-stale-after-seconds:300}")
    private long processingStaleAfterSeconds = 300L;

    public MaterialParseTransactionService(
            MaterialService materialService,
            UploadedMaterialRepository materialRepository,
            MaterialPurposeRepository purposeRepository,
            ParseResultRepository parseResultRepository,
            KnowledgeIndexService knowledgeIndexService
    ) {
        this.materialService = materialService;
        this.materialRepository = materialRepository;
        this.purposeRepository = purposeRepository;
        this.parseResultRepository = parseResultRepository;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    /**
     * Converts an abandoned PROCESSING attempt into an explicit retryable failure.
     * This runs in its own transaction so a request interruption cannot leave the
     * next retry permanently blocked by the old status.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoverStaleProcessing(Long projectId, Long materialId) {
        ParseResult result = parseResultRepository
                .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(materialId)
                .orElse(null);
        if (result == null || result.getParseStatus() != MaterialParseStatus.PROCESSING) {
            return;
        }

        UploadedMaterial material = materialService.requireMaterialForParse(projectId, materialId);

        if (!isStale(result)) {
            throw new ConflictException("Material parsing is already in progress");
        }

        result.setParseStatus(MaterialParseStatus.FAILED);
        result.setFailureReason(STALE_PROCESSING_FAILURE_REASON);
        result.setParsedAt(LocalDateTime.now());
        parseResultRepository.saveAndFlush(result);

        material.setParseStatus(MaterialParseStatus.FAILED);
        material.setUploadStatus(UploadStatus.FAILED);
        materialRepository.saveAndFlush(material);
    }

    private boolean isStale(ParseResult result) {
        LocalDateTime lastActivity = result.getUpdatedAt() != null
                ? result.getUpdatedAt()
                : result.getCreatedAt();
        if (lastActivity == null) {
            return false;
        }
        LocalDateTime cutoff = LocalDateTime.now()
                .minusSeconds(Math.max(1L, processingStaleAfterSeconds));
        return !lastActivity.isAfter(cutoff);
    }

    @Transactional
    public ParsePreparation prepare(Long projectId, Long materialId, boolean forceReparse, boolean retryOnly) {
        RequirementSummary summary = materialService.requireConfirmedSummary(projectId);
        UploadedMaterial material = materialService.requireMaterialForParse(projectId, materialId);
        List<PurposeType> usages = purposeRepository.findByMaterialIdOrderByIdAsc(materialId).stream()
                .map(purpose -> purpose.getPurposeType())
                .distinct()
                .toList();
        if (usages.isEmpty()) {
            throw new ConflictException("At least one material usage is required before prototype parsing");
        }

        ParseResult result = parseResultRepository
                .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(materialId)
                .orElse(null);
        if (retryOnly) {
            if (result == null) {
                throw new ConflictException("No failed parse result is available for retry");
            }
            if (result.getParseStatus() == MaterialParseStatus.SUCCEEDED) {
                return existing(projectId, materialId, material, summary, usages, result);
            }
            if (result.getParseStatus() != MaterialParseStatus.FAILED) {
                throw new ConflictException("Only failed prototype parsing can be retried");
            }
        } else {
            if (result != null && result.getParseStatus() == MaterialParseStatus.SUCCEEDED && !forceReparse) {
                return existing(projectId, materialId, material, summary, usages, result);
            }
            if (result != null && result.getParseStatus() == MaterialParseStatus.PROCESSING) {
                throw new ConflictException("Material parsing is already in progress");
            }
        }

        material.setParseStatus(MaterialParseStatus.PROCESSING);
        materialRepository.saveAndFlush(material);
        if (result == null) {
            result = new ParseResult();
            result.setMaterialId(materialId);
        }
        result.setParseStatus(MaterialParseStatus.PROCESSING);
        result.setFailureReason(null);
        result = parseResultRepository.saveAndFlush(result);
        return new ParsePreparation(
                projectId,
                materialId,
                material,
                summary,
                usages,
                System.nanoTime(),
                result,
                null
        );
    }

    @Transactional
    public ParseResultResponse complete(ParseCompletion completion) {
        ParseResult result = completion.result();
        if (result == null) {
            throw new IllegalStateException("Parse result is required to complete material parsing");
        }
        result.setSummary(completion.summary());
        result.setKeywords(completion.keywords());
        result.setApplicableTeachingStages(completion.teachingStages());
        result.setExtractedText(completion.extractedText());
        result.setPageCount(completion.pageCount());
        result.setSections(completion.sections());
        result.setParseDurationMs(elapsedMillis(completion.startedAtNanos()));
        result.setParseStatus(MaterialParseStatus.SUCCEEDED);
        result.setParsedAt(LocalDateTime.now());
        result.setFailureReason(null);
        // Keep this provisional state inside the completion transaction. If indexing
        // fails, the transaction rolls back this flush together with the chunks.
        result = parseResultRepository.saveAndFlush(result);

        UploadedMaterial material = completion.material();
        material.setParseStatus(MaterialParseStatus.SUCCEEDED);
        material.setUploadStatus(UploadStatus.PARSED);
        knowledgeIndexService.index(material);

        materialRepository.saveAndFlush(material);
        result.setParseDurationMs(elapsedMillis(completion.startedAtNanos()));
        return MaterialParseService.toResponse(parseResultRepository.saveAndFlush(result));
    }

    @Transactional
    public ParseResultResponse fail(ParseFailure failure) {
        ParseResult result = failure.result();
        if (result == null) {
            throw new IllegalStateException("Parse result is required to fail material parsing");
        }
        result.setParseStatus(MaterialParseStatus.FAILED);
        result.setFailureReason(FAILURE_REASON);
        result.setParsedAt(LocalDateTime.now());
        result.setParseDurationMs(elapsedMillis(failure.startedAtNanos()));
        parseResultRepository.save(result);

        UploadedMaterial material = failure.material();
        material.setParseStatus(MaterialParseStatus.FAILED);
        material.setUploadStatus(UploadStatus.FAILED);
        materialRepository.save(material);
        return MaterialParseService.toResponse(result);
    }

    private ParsePreparation existing(
            Long projectId,
            Long materialId,
            UploadedMaterial material,
            RequirementSummary summary,
            List<PurposeType> usages,
            ParseResult result
    ) {
        return new ParsePreparation(
                projectId,
                materialId,
                material,
                summary,
                usages,
                0L,
                result,
                MaterialParseService.toResponse(result)
        );
    }

    private static long elapsedMillis(long startedAtNanos) {
        if (startedAtNanos <= 0L) {
            return 0L;
        }
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    public record ParsePreparation(
            Long projectId,
            Long materialId,
            UploadedMaterial material,
            RequirementSummary summary,
            List<PurposeType> usages,
            long startedAtNanos,
            ParseResult result,
            ParseResultResponse existingResponse
    ) {
        public ParsePreparation {
            usages = usages == null ? List.of() : List.copyOf(usages);
        }

        public boolean hasExistingResponse() {
            return existingResponse != null;
        }
    }

    public record ParseCompletion(
            Long projectId,
            Long materialId,
            UploadedMaterial material,
            ParseResult result,
            String summary,
            List<String> keywords,
            List<String> teachingStages,
            String extractedText,
            Integer pageCount,
            List<String> sections,
            long startedAtNanos
    ) {
        public ParseCompletion {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            teachingStages = teachingStages == null ? List.of() : List.copyOf(teachingStages);
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    public record ParseFailure(
            Long projectId,
            Long materialId,
            UploadedMaterial material,
            ParseResult result,
            long startedAtNanos,
            String failureReason
    ) {
    }
}
