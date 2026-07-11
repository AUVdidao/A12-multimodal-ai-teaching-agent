package com.auvdidao.a12teachingagent.material;

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
import com.auvdidao.a12teachingagent.material.parse.MaterialPrototypeParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MaterialParseService {

    private final MaterialService materialService;
    private final UploadedMaterialRepository materialRepository;
    private final MaterialPurposeRepository purposeRepository;
    private final ParseResultRepository parseResultRepository;
    private final MaterialPrototypeParser prototypeParser;
    private final KnowledgeIndexService knowledgeIndexService;

    public MaterialParseService(
            MaterialService materialService,
            UploadedMaterialRepository materialRepository,
            MaterialPurposeRepository purposeRepository,
            ParseResultRepository parseResultRepository,
            MaterialPrototypeParser prototypeParser,
            KnowledgeIndexService knowledgeIndexService
    ) {
        this.materialService = materialService;
        this.materialRepository = materialRepository;
        this.purposeRepository = purposeRepository;
        this.parseResultRepository = parseResultRepository;
        this.prototypeParser = prototypeParser;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    @Transactional
    public ParseResultResponse parse(Long projectId, Long materialId) {
        RequirementSummary summary = materialService.requireConfirmedSummary(projectId);
        UploadedMaterial material = materialService.requireMaterial(projectId, materialId);
        List<PurposeType> usages = usageTypes(materialId);
        if (usages.isEmpty()) {
            throw new ConflictException("At least one material usage is required before prototype parsing");
        }

        ParseResult result = parseResultRepository
                .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(materialId)
                .orElse(null);
        if (result != null && result.getParseStatus() == MaterialParseStatus.SUCCEEDED) {
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

        try {
            MaterialPrototypeParser.ParsedContent parsed = prototypeParser.parse(material, usages, summary);
            result.setSummary(parsed.summary());
            result.setKeywords(parsed.keywords());
            result.setApplicableTeachingStages(parsed.teachingStages());
            result.setParseStatus(MaterialParseStatus.SUCCEEDED);
            result.setFailureReason(null);
            result.setParsedAt(LocalDateTime.now());
            result = parseResultRepository.saveAndFlush(result);

            material.setParseStatus(MaterialParseStatus.SUCCEEDED);
            material.setUploadStatus(UploadStatus.PARSED);
            materialRepository.saveAndFlush(material);
            knowledgeIndexService.index(material);
            return toResponse(result);
        } catch (RuntimeException exception) {
            result.setParseStatus(MaterialParseStatus.FAILED);
            result.setFailureReason("Prototype parsing could not be completed. Please retry.");
            result.setParsedAt(LocalDateTime.now());
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
        return parse(projectId, materialId);
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
                        true
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
                true
        );
    }
}
