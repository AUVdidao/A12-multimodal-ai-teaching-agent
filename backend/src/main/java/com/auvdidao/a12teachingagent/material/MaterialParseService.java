package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryData;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.knowledge.KnowledgeIndexService;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeChunkResponse;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.ParseResultResponse;
import com.auvdidao.a12teachingagent.material.chunk.TextCleaner;
import com.auvdidao.a12teachingagent.material.parse.MaterialPrototypeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class MaterialParseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialParseService.class);

    private static final Set<String> SUCCESSFUL_ANALYSIS_STATUSES = Set.of(
            "PARSED",
            "SUCCESS",
            "SUCCEEDED",
            "COMPLETED"
    );
    private static final int MAX_AI_SUMMARY_LENGTH = 8_000;
    private static final int MAX_AI_LIST_ITEMS = 20;
    private static final int MAX_AI_LIST_ITEM_LENGTH = 200;

    private final MaterialService materialService;
    private final UploadedMaterialRepository materialRepository;
    private final MaterialPurposeRepository purposeRepository;
    private final ParseResultRepository parseResultRepository;
    private final MaterialPrototypeParser prototypeParser;
    private final AIWorkflowGateway aiWorkflowGateway;
    private final KnowledgeIndexService knowledgeIndexService;
    private final TextCleaner textCleaner;

    public MaterialParseService(
            MaterialService materialService,
            UploadedMaterialRepository materialRepository,
            MaterialPurposeRepository purposeRepository,
            ParseResultRepository parseResultRepository,
            MaterialPrototypeParser prototypeParser,
            AIWorkflowGateway aiWorkflowGateway,
            KnowledgeIndexService knowledgeIndexService,
            TextCleaner textCleaner
    ) {
        this.materialService = materialService;
        this.materialRepository = materialRepository;
        this.purposeRepository = purposeRepository;
        this.parseResultRepository = parseResultRepository;
        this.prototypeParser = prototypeParser;
        this.aiWorkflowGateway = aiWorkflowGateway;
        this.knowledgeIndexService = knowledgeIndexService;
        this.textCleaner = textCleaner;
    }

    @Transactional
    public ParseResultResponse parse(Long projectId, Long materialId) {
        return parse(projectId, materialId, false);
    }

    @Transactional
    public ParseResultResponse parse(Long projectId, Long materialId, boolean forceReparse) {
        RequirementSummary summary = materialService.requireConfirmedSummary(projectId);
        UploadedMaterial material = materialService.requireMaterial(projectId, materialId);
        List<PurposeType> usages = usageTypes(materialId);
        if (usages.isEmpty()) {
            throw new ConflictException("At least one material usage is required before prototype parsing");
        }

        ParseResult result = parseResultRepository
                .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(materialId)
                .orElse(null);
        if (result != null && result.getParseStatus() == MaterialParseStatus.SUCCEEDED && !forceReparse) {
            return toResponse(result);
        }
        if (result != null && result.getParseStatus() == MaterialParseStatus.PROCESSING) {
            throw new ConflictException("Material parsing is already in progress");
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
        long startedAt = System.nanoTime();

        try {
            MaterialPrototypeParser.ParsedContent parsed = prototypeParser.parse(material, usages, summary);
            String extractedText = textCleaner.clean(parsed.extractedText());
            if (extractedText == null || extractedText.isBlank()) {
                throw new IllegalStateException("Material parser returned no extractable text");
            }
            EnrichedContent enriched;
            try {
                MaterialAnalysisResponse analysis = aiWorkflowGateway.analyzeMaterial(new MaterialAnalysisRequest(
                        projectId,
                        analysisFileName(material),
                        analysisMaterialType(material),
                        analysisPurpose(usages),
                        parsed.analysisText(),
                        usages.stream().map(Enum::name).toList(),
                        analysisCourseContext(summary)
                ));
                enriched = mergeAnalysis(parsed, analysis);
            } catch (RuntimeException analysisException) {
                // Local extraction is the authoritative M2 result. AI enrichment is
                // optional so Kimi outages do not discard a valid parse and index.
                LOGGER.warn("Optional material AI enrichment unavailable; using deterministic parser result");
                enriched = new EnrichedContent(
                        parsed.summary(),
                        parsed.keywords(),
                        parsed.teachingStages()
                );
            }
            result.setSummary(enriched.summary());
            result.setKeywords(enriched.keywords());
            result.setApplicableTeachingStages(enriched.teachingStages());
            result.setExtractedText(extractedText);
            result.setPageCount(parsed.pageCount());
            result.setSections(normalizeSections(parsed.sections(), enriched, extractedText, summary));
            result.setParseDurationMs((System.nanoTime() - startedAt) / 1_000_000L);
            // Indexing requires a successfully parsed result. Persist that state before
            // indexing; any indexing failure is converted to a failed parse below.
            result.setParseStatus(MaterialParseStatus.SUCCEEDED);
            result.setParsedAt(LocalDateTime.now());
            result.setFailureReason(null);
            result = parseResultRepository.saveAndFlush(result);

            material.setParseStatus(MaterialParseStatus.SUCCEEDED);
            material.setUploadStatus(UploadStatus.PARSED);
            materialRepository.saveAndFlush(material);
            knowledgeIndexService.index(material);
            result.setParseDurationMs((System.nanoTime() - startedAt) / 1_000_000L);
            result = parseResultRepository.saveAndFlush(result);
            return toResponse(result);
        } catch (RuntimeException exception) {
            result.setParseStatus(MaterialParseStatus.FAILED);
            result.setFailureReason("Prototype parsing could not be completed. Please retry.");
            result.setParsedAt(LocalDateTime.now());
            result.setParseDurationMs((System.nanoTime() - startedAt) / 1_000_000L);
            parseResultRepository.save(result);
            material.setParseStatus(MaterialParseStatus.FAILED);
            material.setUploadStatus(UploadStatus.FAILED);
            materialRepository.save(material);
            return toResponse(result);
        }
    }

    @Transactional
    public ParseResultResponse retry(Long projectId, Long materialId) {
        materialService.requireProject(projectId);
        materialService.requireMaterial(projectId, materialId);
        ParseResult existing = parseResultRepository
                .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(materialId)
                .orElseThrow(() -> new ConflictException("No failed parse result is available for retry"));
        if (existing.getParseStatus() == MaterialParseStatus.SUCCEEDED) {
            return toResponse(existing);
        }
        if (existing.getParseStatus() != MaterialParseStatus.FAILED) {
            throw new ConflictException("Only failed prototype parsing can be retried");
        }
        return parse(projectId, materialId, true);
    }

    @Transactional(readOnly = true)
    public ParseResultResponse getResult(Long projectId, Long materialId) {
        materialService.requireProject(projectId);
        materialService.requireMaterial(projectId, materialId);
        return parseResultRepository.findFirstByMaterialIdOrderByCreatedAtDescIdDesc(materialId)
                .map(MaterialParseService::toResponse)
                .orElse(new ParseResultResponse(
                        null,
                        materialId,
                        MaterialParseStatus.NOT_STARTED,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        true,
                        null,
                        null,
                        List.of(),
                        null,
                        null
                ));
    }

    @Transactional
    public List<KnowledgeChunkResponse> index(Long projectId, Long materialId) {
        materialService.requireProject(projectId);
        UploadedMaterial material = materialService.requireMaterial(projectId, materialId);
        return knowledgeIndexService.index(material);
    }

    private List<PurposeType> usageTypes(Long materialId) {
        return purposeRepository.findByMaterialIdOrderByIdAsc(materialId).stream()
                .map(MaterialPurpose::getPurposeType)
                .distinct()
                .toList();
    }

    private static EnrichedContent mergeAnalysis(
            MaterialPrototypeParser.ParsedContent parsed,
            MaterialAnalysisResponse analysis
    ) {
        if (parsed == null) {
            throw new IllegalStateException("Material parser returned no result");
        }
        if (analysis == null || !isSuccessfulStatus(analysis.status())) {
            throw new IllegalStateException("Material AI analysis did not complete successfully");
        }

        String aiSummary = sanitizeExternalText(analysis.summary(), MAX_AI_SUMMARY_LENGTH);
        List<String> aiKeywords = sanitizeExternalList(analysis.keywords());
        List<String> aiTeachingUses = sanitizeExternalList(analysis.teachingUses());
        if (aiSummary == null && aiKeywords.isEmpty() && aiTeachingUses.isEmpty()) {
            throw new IllegalStateException("Material AI analysis returned no usable fields");
        }

        return new EnrichedContent(
                mergeSummary(parsed.summary(), aiSummary),
                mergeTrustedAndExternal(parsed.keywords(), aiKeywords),
                mergeTrustedAndExternal(parsed.teachingStages(), aiTeachingUses)
        );
    }

    private static boolean isSuccessfulStatus(String status) {
        return status != null
                && SUCCESSFUL_ANALYSIS_STATUSES.contains(status.strip().toUpperCase(Locale.ROOT));
    }

    private static String mergeSummary(String parsedSummary, String aiSummary) {
        String local = parsedSummary == null ? "" : parsedSummary.strip();
        if (aiSummary == null || aiSummary.equals(local)) {
            return local;
        }
        if (local.isEmpty()) {
            return aiSummary;
        }
        return local + "\n\nAI 教学分析：" + aiSummary;
    }

    private static List<String> mergeTrustedAndExternal(List<String> trusted, List<String> external) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (trusted != null) {
            trusted.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip)
                    .forEach(merged::add);
        }
        external.forEach(merged::add);
        return List.copyOf(merged);
    }

    private static List<String> normalizeSections(
            List<String> sections,
            EnrichedContent enriched,
            String extractedText,
            RequirementSummary requirementSummary
    ) {
        List<String> normalized = sections == null
                ? List.of()
                : sections.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .toList();
        if (normalized.size() >= 3) {
            return normalized;
        }

        String fallback = extractedText == null ? "" : extractedText.strip();
        String summary = firstNonBlank(enriched.summary(), fallback, "课程材料");
        String teachingUses = joinValues(enriched.teachingStages(), fallback);
        String context = requirementSummary == null
                ? "课程需求上下文未提供"
                : "教师已确认教学需求，课程主题："
                + firstNonBlank(requirementSummary.getTopic(), "当前课程");
        String goals = joinValues(enriched.keywords(), fallback) + "；" + context;
        return List.of(
                "核心摘要：" + summary,
                "教学应用：" + teachingUses,
                "目标关联：" + goals
        );
    }

    private static String joinValues(List<String> values, String fallback) {
        if (values != null) {
            String joined = values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip)
                    .distinct()
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("");
            if (!joined.isBlank()) {
                return joined;
            }
        }
        return firstNonBlank(fallback, "待补充");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "待补充";
    }

    private static List<String> sanitizeExternalList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = sanitizeExternalText(value, MAX_AI_LIST_ITEM_LENGTH);
            if (normalized != null) {
                sanitized.add(normalized);
            }
            if (sanitized.size() >= MAX_AI_LIST_ITEMS) {
                break;
            }
        }
        return List.copyOf(sanitized);
    }

    private static String sanitizeExternalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String analysisFileName(UploadedMaterial material) {
        if (material.getOriginalFileName() != null && !material.getOriginalFileName().isBlank()) {
            return material.getOriginalFileName().strip();
        }
        if (material.getFileName() != null && !material.getFileName().isBlank()) {
            return material.getFileName().strip();
        }
        return "material-" + material.getId();
    }

    private static String analysisMaterialType(UploadedMaterial material) {
        if (material.getFileType() != null) {
            return material.getFileType().name();
        }
        if (material.getFileExtension() != null && !material.getFileExtension().isBlank()) {
            return material.getFileExtension().strip().toUpperCase(Locale.ROOT);
        }
        return "UNKNOWN";
    }

    private static String analysisPurpose(List<PurposeType> usages) {
        return usages.stream()
                .map(MaterialLabels::usageLabel)
                .distinct()
                .reduce((left, right) -> left + "、" + right)
                .orElse("教学知识补充");
    }

    private static RequirementSummaryData analysisCourseContext(RequirementSummary summary) {
        if (summary == null) {
            return null;
        }
        return new RequirementSummaryData(
                summary.getSubject(),
                summary.getTopic(),
                summary.getGradeLevel(),
                resolveLessonDurationMinutes(summary.getLessonDuration()),
                splitSummaryValues(summary.getTeachingGoals()),
                mergeSummaryValues(summary.getKeyPoints(), summary.getDifficultPoints()),
                summary.getOutputTypes(),
                summary.getStylePreference(),
                summary.getInteractionType()
        );
    }

    private static List<String> splitSummaryValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[,，;；\\r\\n]+"))
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> mergeSummaryValues(String first, String second) {
        LinkedHashSet<String> values = new LinkedHashSet<>(splitSummaryValues(first));
        values.addAll(splitSummaryValues(second));
        return List.copyOf(values);
    }

    private static Integer resolveLessonDurationMinutes(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        java.util.regex.Matcher minutes = java.util.regex.Pattern.compile("(\\d+)\\s*分钟").matcher(value);
        if (minutes.find()) {
            return Integer.parseInt(minutes.group(1));
        }
        java.util.regex.Matcher periods = java.util.regex.Pattern.compile("(\\d+)\\s*(?:课时|学时)").matcher(value);
        if (periods.find()) {
            return Integer.parseInt(periods.group(1)) * 45;
        }
        return null;
    }

    public static ParseResultResponse toResponse(ParseResult result) {
        return new ParseResultResponse(
                result.getId(),
                result.getMaterialId(),
                result.getParseStatus(),
                result.getSummary(),
                result.getKeywords(),
                result.getApplicableTeachingStages(),
                result.getFailureReason(),
                result.getParsedAt(),
                true,
                previewText(result.getExtractedText()),
                result.getPageCount(),
                result.getSections(),
                result.getChunkCount(),
                result.getParseDurationMs()
        );
    }

    private static String previewText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.strip();
        return normalized.length() <= 2_000 ? normalized : normalized.substring(0, 2_000) + "...";
    }

    private record EnrichedContent(String summary, List<String> keywords, List<String> teachingStages) {
    }
}
