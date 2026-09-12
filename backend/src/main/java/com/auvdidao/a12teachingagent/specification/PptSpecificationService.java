package com.auvdidao.a12teachingagent.specification;

import com.auvdidao.a12teachingagent.asset.AssetService;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.specification.AiSupplementPolicy;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationAssetRequirement;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationContentBlock;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationProvenance;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationSlide;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationStatus;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationVersion;
import com.auvdidao.a12teachingagent.domain.specification.repository.PptSpecificationVersionRepository;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.AssetRequirementWrite;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ContentBlockWrite;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ProposalRequest;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SemanticLayout;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SemanticRegion;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SlideWrite;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SpecificationResponse;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SpecificationWriteRequest;
import com.auvdidao.a12teachingagent.template.TemplateProfileContractGate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PptSpecificationService {

    private static final String CONTRACT_VERSION = "1.0.0";
    private static final Set<String> CONTENT_TYPES = Set.of("TITLE", "BODY", "BULLETS", "QUOTE", "TABLE", "CHART", "IMAGE", "TEXT");
    private static final Set<String> SOURCE_TYPES = Set.of("MATERIAL", "TEACHER", "AI_EXAMPLE", "AI_IMAGE");
    private static final Set<String> ASSET_TYPES = Set.of("IMAGE", "CHART", "TABLE", "ICON", "VIDEO", "OTHER");
    private static final Set<String> APPROVAL_STATUSES = Set.of("APPROVED", "PENDING", "REJECTED");
    private static final Set<String> PLACEMENT_INTENTS = Set.of("TITLE", "BODY", "SIDEBAR", "FOOTER", "IMAGE", "CHART", "TABLE", "DECORATION", "OTHER");
    private static final Set<String> POSITIONS = Set.of("TOP", "LEFT", "RIGHT", "CENTER", "BOTTOM", "FULL_BLEED");
    private static final Set<String> TRANSFORMS = Set.of("FIXED", "TRANSLATE_ONLY", "UNIFORM_SCALE", "STRETCH_X", "STRETCH_Y", "RESPONSIVE");

    private final PptSpecificationVersionRepository versionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final TemplateProfileContractGate profileContractGate;
    private final AssetService assetService;

    public PptSpecificationService(
            PptSpecificationVersionRepository versionRepository,
            ProjectRepository projectRepository,
            ProjectAccessService projectAccessService,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            TemplateProfileContractGate profileContractGate,
            AssetService assetService
    ) {
        this.versionRepository = versionRepository;
        this.projectRepository = projectRepository;
        this.projectAccessService = projectAccessService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.profileContractGate = profileContractGate;
        this.assetService = assetService;
    }

    @Transactional
    public SpecificationResponse createInitialDraft(Long projectId, SpecificationWriteRequest request) {
        requireProjectForWrite(projectId);
        PptSpecificationVersion latest = versionRepository.findFirstByProjectIdOrderByVersionNumberDesc(projectId).orElse(null);
        if (latest != null) {
            throw new ConflictException("A PPT Specification already exists for this project; use a proposal or the current DRAFT");
        }
        return toResponse(createVersion(projectId, UUID.randomUUID().toString(), 1, PptSpecificationStatus.DRAFT, request, null, null));
    }

    @Transactional
    public SpecificationResponse createPlanningProposal(Long projectId, ProposalRequest proposal) {
        requireProjectForWrite(projectId);
        PptSpecificationVersion latest = versionRepository.findFirstByProjectIdOrderByVersionNumberDesc(projectId).orElse(null);
        if (latest == null && proposal.operation() != PptSpecificationDtos.ProposalOperation.INITIAL_PROPOSAL) {
            throw new ConflictException("The first Planning proposal must use INITIAL_PROPOSAL");
        }
        if (latest != null) {
            if (proposal.operation() == PptSpecificationDtos.ProposalOperation.INITIAL_PROPOSAL) {
                throw new ConflictException("INITIAL_PROPOSAL is only allowed for a project without a Specification");
            }
            if (proposal.baseVersion() == null
                    || proposal.baseVersion().longValue() != latest.getVersionNumber().longValue()) {
                throw new ConflictException("The Planning proposal base version is stale");
            }
            if (proposal.baseChecksum() == null || !proposal.baseChecksum().equalsIgnoreCase(latest.getChecksum())) {
                throw new ConflictException("The Planning proposal base checksum is stale");
            }
            if (latest.getStatus() == PptSpecificationStatus.DRAFT && latest.getTeacherEditingAt() != null) {
                throw new ConflictException("Planning Agent cannot overwrite a teacher-edited DRAFT; request an explicit teacher patch");
            }
        }
        String specificationId = latest == null ? UUID.randomUUID().toString() : latest.getSpecificationId();
        int nextVersion = latest == null ? 1 : latest.getVersionNumber() + 1;
        return toResponse(createVersion(projectId, specificationId, nextVersion, PptSpecificationStatus.DRAFT, proposal.specification(), null, null));
    }

    @Transactional(readOnly = true)
    public PptSpecificationDtos.HistoryResponse history(Long projectId) {
        requireProject(projectId);
        List<SpecificationResponse> versions = versionRepository.findByProjectIdOrderByVersionNumberAsc(projectId)
                .stream().map(this::toResponse).toList();
        return new PptSpecificationDtos.HistoryResponse(
                projectId,
                versions.isEmpty() ? null : versions.get(0).specificationId(),
                versions.isEmpty() ? null : versions.get(versions.size() - 1),
                versions
        );
    }

    @Transactional(readOnly = true)
    public SpecificationResponse latest(Long projectId) {
        requireProject(projectId);
        return versionRepository.findFirstByProjectIdOrderByVersionNumberDesc(projectId).map(this::toResponse).orElse(null);
    }

    @Transactional(readOnly = true)
    public SpecificationResponse get(Long projectId, Long versionId) {
        requireProject(projectId);
        return toResponse(findVersion(projectId, versionId));
    }

    /** Immutable read gate for the new PPT Engine boundary. */
    @Transactional(readOnly = true)
    public PptSpecificationVersion requireLockedForGeneration(Long projectId, Long versionId) {
        requireProject(projectId);
        PptSpecificationVersion version = findVersion(projectId, versionId);
        requireStatus(version, PptSpecificationStatus.LOCKED);
        verifyIntegrity(version);
        return version;
    }

    @Transactional
    public SpecificationResponse updateDraft(Long projectId, Long versionId, SpecificationWriteRequest request) {
        requireProject(projectId);
        PptSpecificationVersion version = findVersionForUpdate(projectId, versionId);
        requireStatus(version, PptSpecificationStatus.DRAFT);
        requireExpectedVersion(version, request.expectedEntityVersion());
        requireExpectedChecksum(version, request.expectedChecksum());
        validateWrite(projectId, request, false);
        applyWrite(version, request);
        version.setUpdatedBy(actorId());
        version.setTeacherEditingAt(LocalDateTime.now());
        version.setChecksum(calculateChecksum(version));
        return toResponse(versionRepository.saveAndFlush(version));
    }

    @Transactional
    public SpecificationResponse submitReview(Long projectId, Long versionId, String expectedChecksum) {
        requireProject(projectId);
        PptSpecificationVersion version = findVersionForUpdate(projectId, versionId);
        requireStatus(version, PptSpecificationStatus.DRAFT);
        requireExpectedChecksum(version, expectedChecksum);
        verifyIntegrity(version);
        profileContractGate.requireReviewBinding(version.getProjectId(), version.getTemplateProfileId(),
                version.getTemplateProfileVersion(), version.getTemplateProfileChecksum(),
                version.getTemplateCapabilityViewVersion(), version.getTemplateCapabilityViewChecksum());
        version.setStatus(PptSpecificationStatus.REVIEW);
        version.setSubmittedBy(actorId());
        version.setSubmittedAt(LocalDateTime.now());
        version.setSubmittedChecksum(version.getChecksum());
        return toResponse(versionRepository.saveAndFlush(version));
    }

    @Transactional
    public SpecificationResponse returnToDraft(Long projectId, Long versionId, String expectedChecksum, String reason) {
        requireProjectForWrite(projectId);
        PptSpecificationVersion review = findVersionForUpdate(projectId, versionId);
        requireStatus(review, PptSpecificationStatus.REVIEW);
        requireExpectedChecksum(review, expectedChecksum);
        verifyIntegrity(review);
        PptSpecificationVersion latest = versionRepository.findFirstByProjectIdOrderByVersionNumberDesc(projectId).orElseThrow();
        if (!latest.getId().equals(review.getId())) throw new ConflictException("Only the latest REVIEW version can be returned");
        SpecificationWriteRequest copy = toWriteRequest(review);
        return toResponse(createVersion(projectId, review.getSpecificationId(), review.getVersionNumber() + 1,
                PptSpecificationStatus.DRAFT, copy, review.getVersionNumber(), reason));
    }

    @Transactional
    public SpecificationResponse lock(Long projectId, Long versionId, String expectedChecksum) {
        requireProject(projectId);
        PptSpecificationVersion version = findVersionForUpdate(projectId, versionId);
        requireStatus(version, PptSpecificationStatus.REVIEW);
        requireExpectedChecksum(version, expectedChecksum);
        if (!expectedChecksum.equalsIgnoreCase(version.getSubmittedChecksum())) {
            throw new ConflictException("The REVIEW checksum no longer matches the submitted snapshot");
        }
        verifyIntegrity(version);
        verifyLockedAssets(version);
        boolean unlockedBlock = version.getSlides().stream().flatMap(slide -> slide.getContentBlocks().stream())
                .anyMatch(block -> !Boolean.TRUE.equals(block.getLocked()));
        if (unlockedBlock) throw new ConflictException("All content blocks must be locked before the Specification can be locked");
        version.setStatus(PptSpecificationStatus.LOCKED);
        version.setLockedBy(actorId());
        version.setLockedAt(LocalDateTime.now());
        return toResponse(versionRepository.saveAndFlush(version));
    }

    private PptSpecificationVersion createVersion(
            Long projectId, String specificationId, int versionNumber, PptSpecificationStatus status,
            SpecificationWriteRequest request, Integer returnedFromVersion, String returnReason
    ) {
        validateWrite(projectId, request, status == PptSpecificationStatus.LOCKED);
        PptSpecificationVersion version = new PptSpecificationVersion();
        version.setSpecificationId(specificationId);
        version.setProjectId(projectId);
        version.setVersionNumber(versionNumber);
        version.setStatus(status);
        version.setCreatedBy(actorId());
        version.setUpdatedBy(actorId());
        version.setReturnedFromVersion(returnedFromVersion);
        version.setReturnReason(returnReason);
        applyWrite(version, request);
        version.setChecksum(calculateChecksum(version));
        return versionRepository.saveAndFlush(version);
    }

    private void applyWrite(PptSpecificationVersion version, SpecificationWriteRequest request) {
        version.setContractVersion(request.contractVersion().trim());
        version.setTemplateProfileId(request.templateProfileId().trim());
        version.setTemplateProfileVersion(request.templateProfileVersion());
        version.setTemplateProfileChecksum(profileContractGate.requireSpecificationBinding(version.getProjectId(), request.templateProfileId(),
                request.templateProfileVersion(), request.templateCapabilityViewVersion(), request.templateCapabilityViewChecksum()).profileChecksum());
        version.setTemplateCapabilityViewVersion(request.templateCapabilityViewVersion());
        version.setTemplateCapabilityViewChecksum(request.templateCapabilityViewChecksum().toLowerCase());
        version.setTargetSlideCount(request.targetSlideCount());
        version.setSlideCountTolerance(request.slideCountTolerance());
        version.setLocale(request.locale().trim());
        version.setProvider(request.provider().trim());
        version.setModel(request.model().trim());
        version.setAiSupplementPolicy(AiSupplementPolicy.valueOf(request.aiSupplementPolicy().name()));
        version.clearSlides();
        List<SlideWrite> slides = request.slides() == null ? List.of() : request.slides();
        for (int index = 0; index < slides.size(); index++) {
            SlideWrite input = slides.get(index);
            PptSpecificationSlide slide = new PptSpecificationSlide();
            slide.setSlideId(input.slideId().trim());
            slide.setPosition(index);
            slide.setPageNumber(input.pageNumber());
            slide.setTitle(input.title().trim());
            slide.setTeachingGoal(input.teachingGoal().trim());
            slide.setSemanticLayoutJson(writeJson(input.semanticLayout()));
            slide.setNotes(trimToNull(input.notes()));
            List<ContentBlockWrite> blocks = input.contentBlocks() == null ? List.of() : input.contentBlocks();
            for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                ContentBlockWrite item = blocks.get(blockIndex);
                PptSpecificationContentBlock block = new PptSpecificationContentBlock();
                block.setBlockId(item.blockId().trim()); block.setPosition(blockIndex); block.setType(item.type().trim());
                block.setContent(item.content()); block.setSourceType(item.sourceType().trim());
                block.setSourceReference(item.sourceReference().trim()); block.setLocked(item.locked());
                slide.addContentBlock(block);
            }
            List<AssetRequirementWrite> assets = input.assetRequirements() == null ? List.of() : input.assetRequirements();
            for (int assetIndex = 0; assetIndex < assets.size(); assetIndex++) {
                AssetRequirementWrite item = assets.get(assetIndex);
                PptSpecificationAssetRequirement asset = new PptSpecificationAssetRequirement();
                asset.setAssetId(item.assetId().trim()); asset.setPosition(assetIndex); asset.setAssetType(item.assetType().trim());
                asset.setSource(item.source().trim()); asset.setApprovalStatus(item.approvalStatus().trim());
                asset.setRequired(item.required()); asset.setPlacementIntent(item.placementIntent().trim());
                slide.addAssetRequirement(asset);
            }
            List<PptSpecificationDtos.ProvenanceWrite> provenance = input.provenance() == null ? List.of() : input.provenance();
            for (int provenanceIndex = 0; provenanceIndex < provenance.size(); provenanceIndex++) {
                PptSpecificationDtos.ProvenanceWrite item = provenance.get(provenanceIndex);
                PptSpecificationProvenance value = new PptSpecificationProvenance();
                value.setPosition(provenanceIndex); value.setSourceType(item.sourceType().trim()); value.setSourceReference(item.sourceReference().trim());
                slide.addProvenance(value);
            }
            version.addSlide(slide);
        }
    }

    private void validateWrite(Long projectId, SpecificationWriteRequest request, boolean locking) {
        if (request == null) throw new BadRequestException("Specification payload is required");
        if (!CONTRACT_VERSION.equals(request.contractVersion())) throw new BadRequestException("Unsupported Specification contract version");
        if (request.templateCapabilityViewChecksum() == null || !request.templateCapabilityViewChecksum().matches("[0-9a-fA-F]{64}")) throw new BadRequestException("templateCapabilityViewChecksum must be SHA-256 hex");
        profileContractGate.requireSpecificationBinding(projectId, request.templateProfileId(), request.templateProfileVersion(),
                request.templateCapabilityViewVersion(), request.templateCapabilityViewChecksum());
        if (request.slides() == null || request.slides().isEmpty()) throw new BadRequestException("At least one slide is required");
        int min = request.targetSlideCount() - request.slideCountTolerance();
        int max = request.targetSlideCount() + request.slideCountTolerance();
        if (request.slides().size() < min || request.slides().size() > max) throw new BadRequestException("Slide count is outside the confirmed tolerance");
        Set<String> slideIds = new HashSet<>(); Set<String> blockIds = new HashSet<>(); Set<String> assetIds = new HashSet<>();
        for (int i = 0; i < request.slides().size(); i++) {
            SlideWrite slide = request.slides().get(i);
            if (slide.pageNumber() != i + 1) throw new BadRequestException("Slide page numbers must be consecutive starting at 1");
            if (!slideIds.add(normalizeId(slide.slideId()))) throw new BadRequestException("Duplicate slideId: " + slide.slideId());
            validateLayout(slide.semanticLayout());
            for (ContentBlockWrite block : nullSafe(slide.contentBlocks())) {
                if (!CONTENT_TYPES.contains(trimToNull(block.type()))) throw new BadRequestException("Unsupported content block type: " + block.type());
                if (!SOURCE_TYPES.contains(trimToNull(block.sourceType()))) throw new BadRequestException("Unsupported content source type: " + block.sourceType());
                if (!blockIds.add(normalizeId(block.blockId()))) throw new BadRequestException("Duplicate blockId: " + block.blockId());
                if (locking && !Boolean.TRUE.equals(block.locked())) throw new BadRequestException("All content blocks must be locked");
            }
            for (AssetRequirementWrite asset : nullSafe(slide.assetRequirements())) {
                if (!ASSET_TYPES.contains(trimToNull(asset.assetType()))) throw new BadRequestException("Unsupported asset type: " + asset.assetType());
                if (!APPROVAL_STATUSES.contains(trimToNull(asset.approvalStatus()))) throw new BadRequestException("Unsupported asset approval status: " + asset.approvalStatus());
                if (!PLACEMENT_INTENTS.contains(trimToNull(asset.placementIntent()))) throw new BadRequestException("Unsupported asset placement intent: " + asset.placementIntent());
                if (!assetIds.add(normalizeId(asset.assetId()))) throw new BadRequestException("Duplicate assetId: " + asset.assetId());
            }
            for (PptSpecificationDtos.ProvenanceWrite item : nullSafe(slide.provenance())) {
                if (!SOURCE_TYPES.contains(item.sourceType())) throw new BadRequestException("Unsupported provenance source type: " + item.sourceType());
            }
        }
    }


    private void validateLayout(SemanticLayout layout) {
        if (layout == null || layout.regions() == null) throw new BadRequestException("semanticLayout and regions are required");
        if (layout.requestedTransform() != null && !TRANSFORMS.contains(layout.requestedTransform())) throw new BadRequestException("Unsupported requestedTransform");
        Set<String> ids = new HashSet<>();
        for (SemanticRegion region : layout.regions()) {
            if (!POSITIONS.contains(trimToNull(region.preferredPosition()))) throw new BadRequestException("Unsupported preferredPosition: " + region.preferredPosition());
            if (!ids.add(normalizeId(region.regionId()))) throw new BadRequestException("Duplicate semantic region id: " + region.regionId());
        }
    }

    private void verifyIntegrity(PptSpecificationVersion version) {
        String calculated = calculateChecksum(version);
        if (!calculated.equalsIgnoreCase(version.getChecksum())) throw new ConflictException("Specification checksum integrity check failed");
        if (version.getStatus() == PptSpecificationStatus.LOCKED) {
            profileContractGate.requireLockedBinding(version.getProjectId(), version.getTemplateProfileId(),
                    version.getTemplateProfileVersion(), version.getTemplateProfileChecksum(),
                    version.getTemplateCapabilityViewVersion(), version.getTemplateCapabilityViewChecksum());
            verifyLockedAssets(version);
        }
    }

    private void verifyLockedAssets(PptSpecificationVersion version) {
        for (PptSpecificationSlide slide : version.getSlides()) {
            for (PptSpecificationAssetRequirement asset : slide.getAssetRequirements()) {
                if (!"APPROVED".equals(asset.getApprovalStatus())) {
                    throw new ConflictException("LOCKED Specification contains an unapproved asset");
                }
                assetService.requireApprovedForSpecification(version.getProjectId(), asset.getAssetId(), asset.getSource(), asset.getPlacementIntent());
            }
        }
    }

    private String calculateChecksum(PptSpecificationVersion version) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("contractVersion", version.getContractVersion()); root.put("specificationId", version.getSpecificationId()); root.put("projectId", version.getProjectId());
        root.put("templateProfileId", version.getTemplateProfileId()); root.put("templateProfileVersion", version.getTemplateProfileVersion());
        root.put("templateProfileChecksum", version.getTemplateProfileChecksum());
        root.put("templateCapabilityViewVersion", version.getTemplateCapabilityViewVersion()); root.put("templateCapabilityViewChecksum", version.getTemplateCapabilityViewChecksum());
        root.put("targetSlideCount", version.getTargetSlideCount()); root.put("slideCountTolerance", version.getSlideCountTolerance());
        root.put("locale", version.getLocale()); root.put("provider", version.getProvider()); root.put("model", version.getModel());
        root.put("aiSupplementPolicy", version.getAiSupplementPolicy().name());
        List<Map<String, Object>> slides = new ArrayList<>();
        for (PptSpecificationSlide slide : version.getSlides()) {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("slideId", slide.getSlideId()); item.put("pageNumber", slide.getPageNumber()); item.put("title", slide.getTitle()); item.put("teachingGoal", slide.getTeachingGoal());
            item.put("semanticLayout", readJson(slide.getSemanticLayoutJson())); item.put("notes", slide.getNotes());
            item.put("contentBlocks", versionBlocks(slide)); item.put("assetRequirements", versionAssets(slide)); item.put("provenance", versionProvenance(slide)); slides.add(item);
        }
        root.put("slides", slides);
        try {
            JsonNode canonical = canonicalize(objectMapper.valueToTree(root));
            return sha256(objectMapper.writeValueAsBytes(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot calculate Specification checksum", e);
        }
    }

    private List<Map<String, Object>> versionBlocks(PptSpecificationSlide slide) {
        return slide.getContentBlocks().stream().map(block -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("blockId", block.getBlockId()); value.put("type", block.getType()); value.put("content", block.getContent());
            value.put("sourceType", block.getSourceType()); value.put("sourceReference", block.getSourceReference()); value.put("locked", block.getLocked());
            return value;
        }).toList();
    }
    private List<Map<String, Object>> versionAssets(PptSpecificationSlide slide) {
        return slide.getAssetRequirements().stream().map(asset -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("assetId", asset.getAssetId()); value.put("assetType", asset.getAssetType()); value.put("source", asset.getSource());
            value.put("approvalStatus", asset.getApprovalStatus()); value.put("required", asset.getRequired()); value.put("placementIntent", asset.getPlacementIntent());
            return value;
        }).toList();
    }
    private List<Map<String, Object>> versionProvenance(PptSpecificationSlide slide) {
        return slide.getProvenance().stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sourceType", item.getSourceType()); value.put("sourceReference", item.getSourceReference());
            return value;
        }).toList();
    }

    private SpecificationWriteRequest toWriteRequest(PptSpecificationVersion version) {
        List<SlideWrite> slides = version.getSlides().stream().map(slide -> new SlideWrite(slide.getSlideId(), slide.getPageNumber(), slide.getTitle(), slide.getTeachingGoal(), readJson(slide.getSemanticLayoutJson(), SemanticLayout.class),
                slide.getContentBlocks().stream().map(block -> new ContentBlockWrite(block.getBlockId(), block.getType(), block.getContent(), block.getSourceType(), block.getSourceReference(), block.getLocked())).toList(),
                slide.getAssetRequirements().stream().map(asset -> new AssetRequirementWrite(asset.getAssetId(), asset.getAssetType(), asset.getSource(), asset.getApprovalStatus(), asset.getRequired(), asset.getPlacementIntent())).toList(),
                slide.getProvenance().stream().map(item -> new PptSpecificationDtos.ProvenanceWrite(item.getSourceType(), item.getSourceReference())).toList(), slide.getNotes())).toList();
        return new SpecificationWriteRequest(version.getContractVersion(), version.getTemplateProfileId(), version.getTemplateProfileVersion(), version.getTemplateCapabilityViewVersion(), version.getTemplateCapabilityViewChecksum(), version.getTargetSlideCount(), version.getSlideCountTolerance(), version.getLocale(), version.getProvider(), version.getModel(), PptSpecificationDtos.AiSupplementPolicyValue.valueOf(version.getAiSupplementPolicy().name()), slides, null, null);
    }

    private SpecificationResponse toResponse(PptSpecificationVersion version) {
        if (version.getStatus() == PptSpecificationStatus.LOCKED) verifyIntegrity(version);
        return new SpecificationResponse(version.getId(), version.getSpecificationId(), version.getProjectId(), version.getVersionNumber(), version.getStatus(), version.getContractVersion(), version.getTemplateProfileId(), version.getTemplateProfileVersion(), version.getTemplateProfileChecksum(), version.getTemplateCapabilityViewVersion(), version.getTemplateCapabilityViewChecksum(), version.getTargetSlideCount(), version.getSlideCountTolerance(), version.getLocale(), version.getProvider(), version.getModel(), PptSpecificationDtos.AiSupplementPolicyValue.valueOf(version.getAiSupplementPolicy().name()), version.getCreatedBy(), version.getUpdatedBy(), version.getSubmittedBy(), version.getSubmittedAt(), version.getSubmittedChecksum(), version.getLockedBy(), version.getLockedAt(), version.getChecksum(), version.getReturnedFromVersion(), version.getReturnReason(), version.getTeacherEditingAt(), version.getEntityVersion(), version.getCreatedAt(), version.getUpdatedAt(), version.getSlides().stream().map(slide -> new PptSpecificationDtos.SlideResponse(slide.getSlideId(), slide.getPageNumber(), slide.getTitle(), slide.getTeachingGoal(), readJson(slide.getSemanticLayoutJson(), SemanticLayout.class), slide.getContentBlocks().stream().map(block -> new PptSpecificationDtos.ContentBlockResponse(block.getBlockId(), block.getType(), block.getContent(), block.getSourceType(), block.getSourceReference(), block.getLocked())).toList(), slide.getAssetRequirements().stream().map(asset -> new PptSpecificationDtos.AssetRequirementResponse(asset.getAssetId(), asset.getAssetType(), asset.getSource(), asset.getApprovalStatus(), asset.getRequired(), asset.getPlacementIntent())).toList(), slide.getProvenance().stream().map(item -> new PptSpecificationDtos.ProvenanceResponse(item.getSourceType(), item.getSourceReference())).toList(), slide.getNotes())).toList());
    }

    private PptSpecificationVersion findVersion(Long projectId, Long versionId) { return versionRepository.findByIdAndProjectId(versionId, projectId).orElseThrow(() -> new ResourceNotFoundException("PPT Specification version not found: " + versionId)); }
    private PptSpecificationVersion findVersionForUpdate(Long projectId, Long versionId) { return versionRepository.findByIdAndProjectIdForUpdate(versionId, projectId).orElseThrow(() -> new ResourceNotFoundException("PPT Specification version not found: " + versionId)); }
    private void requireStatus(PptSpecificationVersion version, PptSpecificationStatus expected) { if (version.getStatus() != expected) throw new ConflictException("Specification version is " + version.getStatus() + "; expected " + expected); }
    private void requireExpectedChecksum(PptSpecificationVersion version, String expected) { if (expected == null || !expected.equalsIgnoreCase(version.getChecksum())) throw new ConflictException("Specification checksum is stale"); }
    private void requireExpectedVersion(PptSpecificationVersion version, Long expected) { if (expected != null && !expected.equals(version.getEntityVersion())) throw new ConflictException("Specification version has changed; reload before editing"); }
    private Project requireProject(Long projectId) {
        projectAccessService.requireAuthenticatedTeacherAccess(projectId);
        return projectRepository.findById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }
    private Project requireProjectForWrite(Long projectId) {
        projectAccessService.requireAuthenticatedTeacherAccess(projectId);
        return projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }
    private Long actorId() { return currentUserService.currentUser().map(AuthenticatedUser::userId).orElse(null); }
    private static String trimToNull(String value) { if (value == null || value.isBlank()) return null; return value.trim(); }
    private static String normalizeId(String value) { return value == null ? "" : value.trim(); }
    private static <T> List<T> nullSafe(List<T> value) { return value == null ? List.of() : value; }
    private String writeJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new BadRequestException("semanticLayout is invalid"); } }
    private JsonNode readJson(String value) { try { return objectMapper.readTree(value); } catch (JsonProcessingException e) { throw new ConflictException("Specification contains invalid structured layout data"); } }
    private <T> T readJson(String value, Class<T> type) { try { return objectMapper.treeToValue(objectMapper.readTree(value), type); } catch (JsonProcessingException e) { throw new ConflictException("Specification contains invalid structured layout data"); } }
    private JsonNode canonicalize(JsonNode node) { if (node.isObject()) { ObjectNode result = objectMapper.createObjectNode(); node.fieldNames().forEachRemaining(name -> result.set(name, canonicalize(node.get(name)))); java.util.TreeMap<String, JsonNode> sorted = new java.util.TreeMap<>(); result.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue())); ObjectNode ordered = objectMapper.createObjectNode(); sorted.forEach(ordered::set); return ordered; } if (node.isArray()) { ArrayNode result = objectMapper.createArrayNode(); node.forEach(item -> result.add(canonicalize(item))); return result; } return node; }
    private String sha256(byte[] bytes) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result = new StringBuilder(64); for (byte value : digest) result.append(String.format("%02x", value)); return result.toString(); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); } }
}
