package com.auvdidao.a12teachingagent.intent;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntentEvidence;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.intent.dto.TeachingIntentDtos.TeachingIntentEvidenceResponse;
import com.auvdidao.a12teachingagent.intent.dto.TeachingIntentDtos.TeachingIntentResponse;
import com.auvdidao.a12teachingagent.intent.dto.TeachingIntentDtos.TeachingIntentUpdateRequest;
import com.auvdidao.a12teachingagent.knowledge.KnowledgeSearchService;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeHitResponse;
import com.auvdidao.a12teachingagent.material.MaterialLabels;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TeachingIntentService {

    private final ProjectRepository projectRepository;
    private final RequirementSummaryRepository summaryRepository;
    private final UploadedMaterialRepository materialRepository;
    private final MaterialPurposeRepository purposeRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final TeachingIntentRepository intentRepository;
    private final KnowledgeSearchService searchService;

    public TeachingIntentService(
            ProjectRepository projectRepository,
            RequirementSummaryRepository summaryRepository,
            UploadedMaterialRepository materialRepository,
            MaterialPurposeRepository purposeRepository,
            KnowledgeChunkRepository chunkRepository,
            TeachingIntentRepository intentRepository,
            KnowledgeSearchService searchService
    ) {
        this.projectRepository = projectRepository;
        this.summaryRepository = summaryRepository;
        this.materialRepository = materialRepository;
        this.purposeRepository = purposeRepository;
        this.chunkRepository = chunkRepository;
        this.intentRepository = intentRepository;
        this.searchService = searchService;
    }

    @Transactional
    public TeachingIntentResponse generate(Long projectId) {
        Project project = requireProject(projectId);
        RequirementSummary summary = requireConfirmedSummary(projectId);
        TeachingIntent existing = intentRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .filter(intent -> summary.getId().equals(intent.getRequirementSummaryId()))
                .orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }

        List<UploadedMaterial> materials = materialRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        if (materials.isEmpty()) {
            throw new ConflictException("At least one uploaded material is required before teaching intent generation");
        }
        List<UploadedMaterial> parsedMaterials = materials.stream()
                .filter(material -> material.getParseStatus() == MaterialParseStatus.SUCCEEDED)
                .toList();
        if (parsedMaterials.isEmpty()) {
            throw new ConflictException("At least one successfully parsed material is required before teaching intent generation");
        }
        if (chunkRepository.countByProjectId(projectId) == 0) {
            throw new ConflictException("Knowledge chunks are required before teaching intent generation");
        }

        List<KnowledgeHitResponse> hits = searchService.search(projectId, summary.getTopic(), 5).hits();
        if (hits.isEmpty()) {
            throw new ConflictException("At least one knowledge search hit is required before teaching intent generation");
        }

        List<PurposeType> usages = parsedMaterials.stream()
                .flatMap(material -> purposeRepository.findByMaterialIdOrderByIdAsc(material.getId()).stream())
                .map(MaterialPurpose::getPurposeType)
                .distinct()
                .toList();
        String sources = parsedMaterials.stream()
                .map(UploadedMaterial::getOriginalFileName)
                .collect(Collectors.joining("、"));

        TeachingIntent intent = new TeachingIntent();
        intent.setProjectId(projectId);
        intent.setRequirementSummaryId(summary.getId());
        intent.setGenerationGoal(summary.getTeachingGoals());
        intent.setContentBasis("以教师已确认需求为主，结合资料「" + sources + "」的用途、原型摘要与本地知识片段作为增强依据。资料不会覆盖教师明确要求。");
        intent.setTeachingApproach(resolveApproach(usages));
        intent.setInteractionMode(resolveInteraction(usages));
        intent.setOutputTypes(summary.getOutputTypes());
        intent.setStylePreference(summary.getStylePreference());
        intent.setEvidenceItems(hits.stream().map(TeachingIntentService::toEvidence).toList());
        intent.setStatus(TeachingIntentStatus.DRAFT);

        return toResponse(intentRepository.save(intent));
    }

    @Transactional(readOnly = true)
    public TeachingIntentResponse latest(Long projectId) {
        requireProject(projectId);
        return intentRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .map(TeachingIntentService::toResponse)
                .orElse(null);
    }

    @Transactional
    public TeachingIntentResponse update(Long projectId, Long intentId, TeachingIntentUpdateRequest request) {
        requireProject(projectId);
        TeachingIntent intent = requireIntent(projectId, intentId);
        if (intent.getStatus() == TeachingIntentStatus.CONFIRMED) {
            throw new ConflictException("A confirmed teaching intent cannot be modified");
        }
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        intent.setGenerationGoal(request.generationGoal().trim());
        intent.setContentBasis(request.contentBasis().trim());
        intent.setTeachingApproach(request.teachingApproach().trim());
        intent.setInteractionMode(request.interactionMode().trim());
        intent.setOutputTypes(normalizeValues(request.outputTypes()));
        intent.setStylePreference(trimToNull(request.stylePreference()));
        return toResponse(intentRepository.save(intent));
    }

    @Transactional
    public TeachingIntentResponse confirm(Long projectId, Long intentId) {
        Project project = requireProject(projectId);
        TeachingIntent intent = requireIntent(projectId, intentId);
        if (intent.getStatus() == TeachingIntentStatus.CONFIRMED) {
            return toResponse(intent);
        }
        validateComplete(intent);
        intent.setStatus(TeachingIntentStatus.CONFIRMED);
        intent.setConfirmedAt(LocalDateTime.now());
        project.setStatus(ProjectStatus.INTENT_CONFIRMED);
        projectRepository.save(project);
        return toResponse(intentRepository.save(intent));
    }

    private Project requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BadRequestException("projectId must be greater than 0");
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    private RequirementSummary requireConfirmedSummary(Long projectId) {
        RequirementSummary summary = summaryRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .orElseThrow(() -> new ConflictException("A confirmed requirement summary is required before teaching intent generation"));
        if (summary.getStatus() != RequirementSummaryStatus.CONFIRMED) {
            throw new ConflictException("A confirmed requirement summary is required before teaching intent generation");
        }
        return summary;
    }

    private TeachingIntent requireIntent(Long projectId, Long intentId) {
        if (intentId == null || intentId <= 0) {
            throw new BadRequestException("intentId must be greater than 0");
        }
        TeachingIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new ResourceNotFoundException("Teaching intent not found: " + intentId));
        if (!projectId.equals(intent.getProjectId())) {
            throw new ResourceNotFoundException("Teaching intent does not belong to project: " + projectId);
        }
        return intent;
    }

    private static TeachingIntentEvidence toEvidence(KnowledgeHitResponse hit) {
        TeachingIntentEvidence evidence = new TeachingIntentEvidence();
        evidence.setMaterialId(hit.materialId());
        evidence.setKnowledgeChunkId(hit.chunkId());
        evidence.setSourceFilename(hit.sourceFilename());
        evidence.setUsageTypes(hit.usageTypes().stream().map(Enum::name).collect(Collectors.joining(",")));
        evidence.setHitReason(hit.hitReason());
        evidence.setContentExcerpt(abbreviate(hit.content(), 260));
        return evidence;
    }

    private static String resolveApproach(List<PurposeType> usages) {
        LinkedHashSet<String> approaches = new LinkedHashSet<>();
        if (usages.contains(PurposeType.TEXTBOOK_BASIS)) approaches.add("概念讲解");
        if (usages.contains(PurposeType.CASE_MATERIAL)) approaches.add("案例分析");
        if (usages.contains(PurposeType.EXERCISE_SOURCE)) approaches.add("讲练结合");
        if (usages.contains(PurposeType.KNOWLEDGE_SUPPLEMENT)) approaches.add("拓展探究");
        if (usages.contains(PurposeType.IMAGE_ASSET)) approaches.add("图像观察");
        if (approaches.isEmpty()) approaches.add("问题导向教学");
        return String.join(" + ", approaches);
    }

    private static String resolveInteraction(List<PurposeType> usages) {
        if (usages.contains(PurposeType.CASE_MATERIAL) || usages.contains(PurposeType.IMAGE_ASSET)) {
            return "教师引导、学生观察讨论与证据表达";
        }
        if (usages.contains(PurposeType.EXERCISE_SOURCE)) {
            return "即时练习、同伴互评与教师反馈";
        }
        return "教师提问、学生思考与课堂反馈";
    }

    private static void validateComplete(TeachingIntent intent) {
        if (!hasText(intent.getGenerationGoal())
                || !hasText(intent.getContentBasis())
                || !hasText(intent.getTeachingApproach())
                || !hasText(intent.getInteractionMode())
                || intent.getOutputTypes().isEmpty()
                || intent.getEvidenceItems().isEmpty()) {
            throw new BadRequestException("The teaching intent is incomplete and cannot be confirmed");
        }
    }

    private static TeachingIntentResponse toResponse(TeachingIntent intent) {
        return new TeachingIntentResponse(
                intent.getId(),
                intent.getProjectId(),
                intent.getRequirementSummaryId(),
                intent.getGenerationGoal(),
                intent.getContentBasis(),
                intent.getTeachingApproach(),
                intent.getInteractionMode(),
                intent.getOutputTypes(),
                intent.getStylePreference(),
                intent.getEvidenceItems().stream().map(evidence -> new TeachingIntentEvidenceResponse(
                        evidence.getMaterialId(),
                        evidence.getKnowledgeChunkId(),
                        evidence.getSourceFilename(),
                        parseUsages(evidence.getUsageTypes()),
                        evidence.getHitReason(),
                        evidence.getContentExcerpt()
                )).toList(),
                intent.getStatus(),
                intent.getCreatedAt(),
                intent.getUpdatedAt(),
                intent.getConfirmedAt(),
                true
        );
    }

    private static List<PurposeType> parseUsages(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .filter(part -> !part.isBlank())
                .map(PurposeType::valueOf)
                .toList();
    }

    private static List<String> normalizeValues(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            values.stream().map(TeachingIntentService::trimToNull).filter(java.util.Objects::nonNull).forEach(normalized::add);
        }
        return List.copyOf(normalized);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String abbreviate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit) + "…";
    }
}
