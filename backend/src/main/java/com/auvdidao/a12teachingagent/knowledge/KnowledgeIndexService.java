package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeChunkResponse;
import com.auvdidao.a12teachingagent.material.MaterialLabels;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class KnowledgeIndexService {

    private final KnowledgeChunkRepository chunkRepository;
    private final ParseResultRepository parseResultRepository;
    private final MaterialPurposeRepository purposeRepository;
    private final RequirementSummaryRepository summaryRepository;

    public KnowledgeIndexService(
            KnowledgeChunkRepository chunkRepository,
            ParseResultRepository parseResultRepository,
            MaterialPurposeRepository purposeRepository,
            RequirementSummaryRepository summaryRepository
    ) {
        this.chunkRepository = chunkRepository;
        this.parseResultRepository = parseResultRepository;
        this.purposeRepository = purposeRepository;
        this.summaryRepository = summaryRepository;
    }

    @Transactional
    public List<KnowledgeChunkResponse> index(UploadedMaterial material) {
        ParseResult parseResult = parseResultRepository
                .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(material.getId())
                .filter(result -> result.getParseStatus() == MaterialParseStatus.SUCCEEDED)
                .orElseThrow(() -> new ConflictException("Only successfully parsed materials can be indexed"));
        RequirementSummary summary = summaryRepository
                .findFirstByProjectIdOrderByCreatedAtDescIdDesc(material.getProjectId())
                .orElseThrow(() -> new ConflictException("A confirmed requirement summary is required before indexing"));
        List<PurposeType> usages = purposeRepository.findByMaterialIdOrderByIdAsc(material.getId()).stream()
                .map(MaterialPurpose::getPurposeType)
                .distinct()
                .toList();

        chunkRepository.deleteByMaterialId(material.getId());
        chunkRepository.flush();

        String sourceStem = filenameStem(material.getOriginalFileName());
        String usageLabels = usages.stream().map(MaterialLabels::usageLabel).collect(Collectors.joining("、"));
        String stages = String.join("、", parseResult.getApplicableTeachingStages());
        String topic = fallback(summary.getTopic(), "当前课题");
        String goals = fallback(summary.getTeachingGoals(), "教师确认的教学目标");

        KnowledgeChunk summaryChunk = chunk(
                material,
                1,
                "核心摘要 · " + sourceStem,
                parseResult.getSummary(),
                parseResult.getKeywords(),
                usages
        );
        KnowledgeChunk applicationChunk = chunk(
                material,
                2,
                "教学应用 · " + topic,
                "该资料被标记为" + usageLabels + "，原型流程建议用于" + stages + "。资料应用必须服从教师已确认的课题“" + topic + "”。",
                mergeKeywords(parseResult.getKeywords(), List.of(topic, stages)),
                usages
        );
        KnowledgeChunk goalChunk = chunk(
                material,
                3,
                "目标关联 · " + fallback(summary.getSubject(), "课程"),
                "该资料可作为增强证据支撑教学目标：“" + goals + "”。引用时应保留来源文件与用途，不替代教师明确需求。",
                mergeKeywords(parseResult.getKeywords(), List.of("教学目标", fallback(summary.getSubject(), "课程"))),
                usages
        );

        return chunkRepository.saveAll(List.of(summaryChunk, applicationChunk, goalChunk)).stream()
                .map(KnowledgeIndexService::toResponse)
                .toList();
    }

    public static KnowledgeChunkResponse toResponse(KnowledgeChunk chunk) {
        return new KnowledgeChunkResponse(
                chunk.getId(),
                chunk.getProjectId(),
                chunk.getMaterialId(),
                chunk.getChunkNo(),
                chunk.getSourceFilename(),
                chunk.getTitle(),
                chunk.getContent(),
                chunk.getKeywords(),
                chunk.getUsageTypes(),
                chunk.getCreatedAt()
        );
    }

    private static KnowledgeChunk chunk(
            UploadedMaterial material,
            int chunkNo,
            String title,
            String content,
            List<String> keywords,
            List<PurposeType> usages
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setProjectId(material.getProjectId());
        chunk.setMaterialId(material.getId());
        chunk.setChunkNo(chunkNo);
        chunk.setSourceFilename(material.getOriginalFileName());
        chunk.setTitle(title);
        chunk.setContent(content);
        chunk.setKeywords(keywords);
        chunk.setUsageTypes(usages);
        return chunk;
    }

    private static List<String> mergeKeywords(List<String> base, List<String> additions) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>(base);
        additions.stream().filter(value -> value != null && !value.isBlank()).forEach(values::add);
        return List.copyOf(values);
    }

    private static String filenameStem(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : fallback(filename, "教学资料");
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
