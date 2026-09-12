package com.auvdidao.a12teachingagent.pptengine;

import com.auvdidao.a12teachingagent.asset.AssetService;
import com.auvdidao.a12teachingagent.asset.ManifestStatus;
import com.auvdidao.a12teachingagent.domain.generation.GenerationJob;
import com.auvdidao.a12teachingagent.domain.generation.GenerationJobStatus;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationJobRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationAssetRequirement;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationContentBlock;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationProvenance;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationSlide;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationVersion;
import com.auvdidao.a12teachingagent.domain.specification.repository.PptSpecificationVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileVersion;
import com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ApprovedManifestInput;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ArtifactReceipt;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.AssetRequirementInput;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ConfirmedProfileInput;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ContentBlockInput;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.EngineStatus;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ExecutionContext;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ExecutionResponse;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.GenerationRequest;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ProvenanceInput;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.SlideInput;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.SpecificationInput;
import com.auvdidao.a12teachingagent.pptengine.PptGenerationDtos.CreateGenerationJobRequest;
import com.auvdidao.a12teachingagent.pptengine.PptGenerationDtos.GenerationJobResponse;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PptGenerationService {
    private static final String CONTRACT_VERSION = "ppt-engine-generation-v1";
    /**
     * Wire profiles legitimately contain style tokens (fontToken/colorToken). A
     * substring scan therefore rejects valid Engine-native profiles. Inspect
     * JSON field names instead and reserve the deny-list for credential-bearing
     * names and an exact generic token field.
     */
    private static final java.util.regex.Pattern SECRET_FIELD_PATTERN = java.util.regex.Pattern.compile(
            "(?i)^(api[_-]?key|authorization|bearer|credential|password|secret|access[_-]?token|refresh[_-]?token|token|private[_-]?key|client[_-]?secret)$");

    private final ProjectRepository projectRepository;
    private final PptSpecificationVersionRepository specificationRepository;
    private final TemplateProfileVersionRepository profileRepository;
    private final GenerationJobRepository jobRepository;
    private final ProjectAccessService access;
    private final com.auvdidao.a12teachingagent.specification.PptSpecificationService specificationService;
    private final com.auvdidao.a12teachingagent.template.TemplateProfileContractGate profileGate;
    private final AssetService assetService;
    private final PptEngineClient engineClient;
    private final ObjectMapper objectMapper;
    private final TemplateSourceVersionRepository sourceRepository;
    private final com.auvdidao.a12teachingagent.material.storage.StorageProperties storageProperties;

    @Autowired
    public PptGenerationService(
            ProjectRepository projectRepository,
            PptSpecificationVersionRepository specificationRepository,
            TemplateProfileVersionRepository profileRepository,
            GenerationJobRepository jobRepository,
            ProjectAccessService access,
            com.auvdidao.a12teachingagent.specification.PptSpecificationService specificationService,
            com.auvdidao.a12teachingagent.template.TemplateProfileContractGate profileGate,
            AssetService assetService,
            PptEngineClient engineClient,
            ObjectMapper objectMapper,
            TemplateSourceVersionRepository sourceRepository,
            com.auvdidao.a12teachingagent.material.storage.StorageProperties storageProperties
    ) {
        this.projectRepository = projectRepository;
        this.specificationRepository = specificationRepository;
        this.profileRepository = profileRepository;
        this.jobRepository = jobRepository;
        this.access = access;
        this.specificationService = specificationService;
        this.profileGate = profileGate;
        this.assetService = assetService;
        this.engineClient = engineClient;
        this.objectMapper = objectMapper;
        this.sourceRepository = sourceRepository;
        this.storageProperties = storageProperties;
    }

    /** Compatibility constructor retained for isolated service tests that do not exercise file-backed HTTP input. */
    public PptGenerationService(
            ProjectRepository projectRepository,
            PptSpecificationVersionRepository specificationRepository,
            TemplateProfileVersionRepository profileRepository,
            GenerationJobRepository jobRepository,
            ProjectAccessService access,
            com.auvdidao.a12teachingagent.specification.PptSpecificationService specificationService,
            com.auvdidao.a12teachingagent.template.TemplateProfileContractGate profileGate,
            AssetService assetService,
            PptEngineClient engineClient,
            ObjectMapper objectMapper
    ) {
        this(projectRepository, specificationRepository, profileRepository, jobRepository, access,
                specificationService, profileGate, assetService, engineClient, objectMapper, null, null);
    }

    @Transactional(noRollbackFor = PptEngineException.class)
    public GenerationJobResponse start(Long projectId, CreateGenerationJobRequest request) {
        AuthenticatedUser user = access.requireAuthenticatedTeacherAccess(projectId);
        if (request == null) throw new ConflictException("Generation request is required");
        String idempotencyKey = bounded(request.idempotencyKey(), "idempotencyKey");
        String requestedEngineVersion = bounded(request.engineVersion(), "engineVersion");
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        PptSpecificationVersion specification = specificationService.requireLockedForGeneration(projectId, request.specificationVersionId());
        if (!projectId.equals(specification.getProjectId())) throw new ConflictException("Specification project binding is stale");

        var profileBinding = profileGate.requireLockedBinding(projectId, specification.getTemplateProfileId(),
                specification.getTemplateProfileVersion(), specification.getTemplateProfileChecksum(),
                specification.getTemplateCapabilityViewVersion(), specification.getTemplateCapabilityViewChecksum());
        TemplateProfileVersion profile = profileRepository.findByIdAndProjectId(profileBinding.profileId(), projectId)
                .orElseThrow(() -> new ConflictException("Confirmed Template Profile disappeared during generation binding"));
        if (!projectId.equals(profile.getProjectId())
                || (profile.getStatus() != com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus.READY
                && profile.getStatus() != com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus.CONFIRMED)
                || profile.getOwnedByTeacherId() == null
                || !project.getOwnerUserId().equals(profile.getOwnedByTeacherId())
                || !user.userId().equals(profile.getOwnedByTeacherId())
                || !profileBinding.profileChecksum().equalsIgnoreCase(profile.getChecksum())) {
            throw new ConflictException("Template Profile project/checksum binding is stale");
        }

        List<AssetService.EngineManifest> manifests = collectManifests(projectId, project.getOwnerUserId(), specification);
        String manifestChecksum = manifestSetChecksum(manifests);
        int manifestVersion = manifests.stream().map(AssetService.EngineManifest::manifestVersion).max(Integer::compareTo).orElse(0);
        assertEngineInputsReady(project, specification, profile, manifests);
        String inputIdentityChecksum = inputIdentityChecksum(projectId, specification, profile, manifests, requestedEngineVersion);
        GenerationJob existing = jobRepository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey).orElse(null);
        if (existing != null) {
            if (!inputIdentityChecksum.equalsIgnoreCase(existing.getInputIdentityChecksum())) {
                throw new ConflictException("Idempotency key is already bound to a different generation input");
            }
            return toResponse(existing);
        }
        String executionId = UUID.randomUUID().toString();

        GenerationJob job = new GenerationJob();
        job.setProjectId(projectId);
        job.setOwnerUserId(project.getOwnerUserId());
        job.setRequestedBy(user.userId());
        job.setExecutionId(executionId);
        job.setIdempotencyKey(idempotencyKey);
        job.setSpecificationVersionId(specification.getId());
        job.setSpecificationVersion(specification.getVersionNumber());
        job.setSpecificationChecksum(specification.getChecksum());
        job.setTemplateProfileId(profile.getId());
        job.setTemplateProfileVersion(profile.getVersionNumber());
        job.setTemplateProfileChecksum(profile.getChecksum());
        job.setAssetManifestVersion(manifestVersion);
        job.setAssetManifestChecksum(manifestChecksum);
        job.setInputIdentityChecksum(inputIdentityChecksum);
        job.setEngineVersion(requestedEngineVersion);
        job.setStatus(GenerationJobStatus.REQUESTED);
        job.setRequestSnapshotJson("{}");
        GenerationJob saved;
        try {
            saved = jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Idempotency key is already being created for this project");
        }

        GenerationRequest engineRequest = new GenerationRequest(CONTRACT_VERSION,
                new ExecutionContext(saved.getId(), executionId, projectId, project.getOwnerUserId(), user.userId(), requestedEngineVersion),
                specificationInput(specification), profileInput(profile), manifests.stream().map(this::manifestInput).toList(),
                buildExecutionBindings(saved, project, specification, profile, manifests, requestedEngineVersion, user.userId()));
        ExecutionResponse response;
        try {
            String requestJson = json(engineRequest);
            rejectSecretFields(requestJson);
            saved.setRequestSnapshotJson(requestJson);
            jobRepository.saveAndFlush(saved);
            response = engineClient.execute(engineRequest);
            validateResponse(engineRequest, response);
        } catch (PptEngineException exception) {
            saved.setStatus(isContractFailure(exception.safeCode())
                    ? GenerationJobStatus.CONTRACT_FAILED : GenerationJobStatus.TRANSPORT_FAILED);
            saved.setFailureCode(exception.safeCode());
            saved.setFailureMessage(exception.getMessage());
            jobRepository.saveAndFlush(saved);
            throw exception;
        } catch (RuntimeException exception) {
            saved.setStatus(GenerationJobStatus.CONTRACT_FAILED);
            saved.setFailureCode("PPT_ENGINE_CONTRACT_FAILED");
            saved.setFailureMessage("PPT Engine response could not be verified");
            jobRepository.saveAndFlush(saved);
            throw new PptEngineException("PPT_ENGINE_CONTRACT_FAILED", 502,
                    "PPT Engine response could not be verified");
        }

        saved.setStatus(mapStatus(response.status()));
        saved.setResponseReceiptJson(json(response));
        saved.setFailureCode(null);
        saved.setFailureMessage(null);
        return toResponse(jobRepository.saveAndFlush(saved));
    }

    @Transactional(readOnly = true)
    public GenerationJobResponse get(Long projectId, Long jobId) {
        access.requireAuthenticatedTeacherAccess(projectId);
        GenerationJob job = jobRepository.findById(jobId)
                .filter(item -> projectId.equals(item.getProjectId()))
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
        return toResponse(job);
    }

    private List<AssetService.EngineManifest> collectManifests(Long projectId, Long projectOwnerId, PptSpecificationVersion specification) {
        Map<String, AssetRequirementRef> requirements = new LinkedHashMap<>();
        for (PptSpecificationSlide slide : specification.getSlides()) {
            for (PptSpecificationAssetRequirement requirement : slide.getAssetRequirements()) {
                if (!"APPROVED".equals(requirement.getApprovalStatus())) {
                    throw new ConflictException("ENGINE_SPECIFICATION_ASSET_NOT_APPROVED");
                }
                AssetRequirementRef prior = requirements.putIfAbsent(requirement.getAssetId(),
                        new AssetRequirementRef(requirement.getAssetId(), requirement.getSource(), requirement.getPlacementIntent()));
                if (prior != null && (!prior.source().equals(requirement.getSource())
                        || !prior.placementIntent().equals(requirement.getPlacementIntent()))) {
                    throw new ConflictException("ENGINE_SPECIFICATION_ASSET_REQUIREMENT_CONFLICT");
                }
            }
        }
        List<AssetService.EngineManifest> manifests = new ArrayList<>();
        for (AssetRequirementRef requirement : requirements.values()) {
            AssetService.EngineManifest manifest = assetService.requireApprovedManifestForEngine(projectId,
                    requirement.assetKey(), requirement.source(), requirement.placementIntent());
            if (manifest.status() != null && !ManifestStatus.ACTIVE.name().equals(manifest.status())
                    || !projectOwnerId.equals(manifest.ownerUserId())) {
                throw new ConflictException("ENGINE_ASSET_NOT_ACTIVE");
            }
            manifests.add(manifest);
        }
        manifests.sort(Comparator.comparing(AssetService.EngineManifest::assetKey)
                .thenComparing(AssetService.EngineManifest::manifestVersion));
        return List.copyOf(manifests);
    }

    private SpecificationInput specificationInput(PptSpecificationVersion specification) {
        return new SpecificationInput(specification.getId(), specification.getSpecificationId(), specification.getVersionNumber(),
                specification.getStatus().name(), specification.getChecksum(), specification.getContractVersion(), specification.getTemplateProfileId(),
                specification.getTemplateProfileVersion(), specification.getTemplateProfileChecksum(), specification.getSlides().stream()
                .map(this::slideInput).toList());
    }

    private SlideInput slideInput(PptSpecificationSlide slide) {
        return new SlideInput(slide.getSlideId(), slide.getPageNumber(), slide.getTitle(), slide.getTeachingGoal(), slide.getSemanticLayoutJson(),
                slide.getContentBlocks().stream().map(this::contentBlockInput).toList(), slide.getAssetRequirements().stream().map(this::assetRequirementInput).toList(),
                slide.getProvenance().stream().map(this::provenanceInput).toList(), slide.getNotes());
    }

    private ContentBlockInput contentBlockInput(PptSpecificationContentBlock block) {
        return new ContentBlockInput(block.getBlockId(), block.getType(), block.getContent(), block.getSourceType(), block.getSourceReference(), block.getLocked());
    }

    private AssetRequirementInput assetRequirementInput(PptSpecificationAssetRequirement asset) {
        return new AssetRequirementInput(asset.getAssetId(), asset.getAssetType(), asset.getSource(), asset.getApprovalStatus(), asset.getRequired(), asset.getPlacementIntent());
    }

    private ProvenanceInput provenanceInput(PptSpecificationProvenance value) {
        return new ProvenanceInput(value.getSourceType(), value.getSourceReference());
    }

    private ConfirmedProfileInput profileInput(TemplateProfileVersion profile) {
        return new ConfirmedProfileInput(profile.getId(), profile.getTemplateId(), profile.getSourceVersionId(), profile.getVersionNumber(),
                profile.getChecksum(), profile.getCapabilityViewVersion(), profile.getCapabilityViewChecksum(), profile.getProfileJson(), profile.getCapabilityViewJson(),
                profile.getOwnedByTeacherId(), profile.getParserSnapshotChecksum(), profile.getConfirmedChecksum());
    }

    private com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ExecutionBindings buildExecutionBindings(
            GenerationJob job, Project project, PptSpecificationVersion specification,
            TemplateProfileVersion profile, List<AssetService.EngineManifest> manifests,
            String engineVersion, Long requestedBy) {
        if (sourceRepository == null || storageProperties == null) {
            return null;
        }
        TemplateSourceVersion source = sourceRepository.findByIdAndTemplateIdAndProjectId(
                        profile.getSourceVersionId(), profile.getTemplateId(), project.getId())
                .orElse(null);
        if (source == null) throw new ConflictException("ENGINE_TEMPLATE_SOURCE_NOT_FOUND");
        JsonNode engineProfile = requireEngineProfile(profile, source);
        if (engineProfile.hasNonNull("projectId")
                && !String.valueOf(project.getId()).equals(engineProfile.path("projectId").asText())) {
            throw new ConflictException("ENGINE_CONFIRMED_PROFILE_BINDING_STALE: project binding does not match the locked project");
        }
        if (engineProfile.hasNonNull("ownerUserId")
                && !String.valueOf(project.getOwnerUserId()).equals(engineProfile.path("ownerUserId").asText())) {
            throw new ConflictException("ENGINE_CONFIRMED_PROFILE_BINDING_STALE: owner binding does not match the locked project");
        }
        ((ObjectNode) engineProfile).put("projectId", String.valueOf(project.getId()));
        ((ObjectNode) engineProfile).put("ownerUserId", String.valueOf(project.getOwnerUserId()));
        ((ObjectNode) engineProfile).put("executionStatus", "EXECUTION_READY");
        JsonNode engineSpecification = engineSpecification(project, specification, profile);
        JsonNode engineManifest = engineManifest(project, specification, manifests);
        String specificationChecksum = sha256CanonicalWithout(engineSpecification, Set.of("checksum"));
        ((ObjectNode) engineSpecification).put("checksum", specificationChecksum);
        // The manifest must bind to the exact wire Specification sent to the Engine,
        // not to the legacy domain row checksum.
        ((ObjectNode) engineManifest).put("specificationChecksum", specificationChecksum);
        String manifestChecksum = sha256CanonicalWithout(engineManifest, Set.of("manifestChecksum"));
        ((ObjectNode) engineManifest).put("manifestChecksum", manifestChecksum);
        String profileChecksum = sha256CanonicalWithout(engineProfile, Set.of());
        String manifestId = engineManifest.path("manifestId").asText();
        ObjectNode generationJob = objectMapper.createObjectNode();
        generationJob.put("generationJobId", String.valueOf(job.getId()));
        generationJob.put("executionAttemptId", job.getExecutionId());
        generationJob.put("projectId", String.valueOf(project.getId()));
        generationJob.put("ownerUserId", String.valueOf(project.getOwnerUserId()));
        generationJob.set("specificationBinding", binding(specification.getSpecificationId(), specification.getVersionNumber(), specificationChecksum));
        generationJob.set("templateProfileBinding", binding(engineProfile.path("profileId").asText(), profile.getVersionNumber(), profileChecksum));
        generationJob.set("approvedAssetManifestBinding", binding(manifestId, manifests.stream().map(AssetService.EngineManifest::manifestVersion).max(Integer::compareTo).orElse(1), manifestChecksum));
        generationJob.put("composeContractVersion", "2.0.0");
        generationJob.put("planContractVersion", "2.0.0");
        generationJob.put("engineBuildVersion", engineVersion);
        generationJob.put("executorAdapterVersion", "same-package-v1");
        generationJob.put("fontEnvironmentVersion", "UNBOUND");
        generationJob.put("requestedBy", String.valueOf(requestedBy));
        generationJob.put("requestedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        generationJob.put("idempotencyKey", job.getIdempotencyKey());
        generationJob.put("jobBindingChecksum", sha256CanonicalWithout(generationJob, Set.of("jobBindingChecksum", "executionAttemptId")));

        ObjectNode templateSource = objectMapper.createObjectNode();
        templateSource.put("storageKey", source.getStorageKey());
        templateSource.put("sha256", source.getSha256());
        templateSource.put("size", source.getFileSize());
        templateSource.put("lastModifiedUtc", sourceLastModified(source));
        // The Engine validates the physical source against the persisted
        // profile binding as well as its content identity. These values are
        // server-owned and must come from the locked source/profile pair.
        templateSource.put("templateId", String.valueOf(profile.getTemplateId()));
        templateSource.put("templateVersion", source.getVersionNumber());
        templateSource.put("sourceVersionId", source.getId());
        List<JsonNode> assetFiles = manifests.stream().map(manifest -> (JsonNode) approvedAssetFile(manifest)).toList();
        return new com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ExecutionBindings(
                generationJob, engineSpecification, engineProfile, engineManifest, null, templateSource,
                assetFiles, specificationChecksum, profileChecksum, manifestChecksum);
    }

    /**
     * The generation row is not created until all file-backed Engine inputs are
     * known to be executable. This prevents a legacy Profile from falling into
     * the old incomplete Execute envelope.
     */
    private void assertEngineInputsReady(Project project, PptSpecificationVersion specification,
                                          TemplateProfileVersion profile,
                                          List<AssetService.EngineManifest> manifests) {
        if (sourceRepository == null || storageProperties == null) return;
        TemplateSourceVersion source = sourceRepository.findByIdAndTemplateIdAndProjectId(
                        profile.getSourceVersionId(), profile.getTemplateId(), project.getId())
                .orElseThrow(() -> new ConflictException("ENGINE_TEMPLATE_SOURCE_NOT_FOUND"));
        requireEngineProfile(profile, source);
        if (source.getFileSize() == null || source.getFileSize() < 1
                || source.getSha256() == null || !source.getSha256().matches("[0-9a-fA-F]{64}")
                || sourceLastModified(source) == null) {
            throw new ConflictException("ENGINE_TEMPLATE_SOURCE_IDENTITY_UNAVAILABLE");
        }
        manifests.forEach(this::approvedAssetFile);
    }

    private JsonNode engineSpecification(Project project, PptSpecificationVersion specification,
                                         TemplateProfileVersion profile) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("contractVersion", "1.0.0");
        root.put("specificationId", specification.getSpecificationId());
        root.put("projectId", String.valueOf(project.getId()));
        root.put("version", specification.getVersionNumber());
        root.put("status", specification.getStatus().name());
        root.put("templateProfileId", profileIdForSpecification(specification));
        root.put("templateProfileVersion", specification.getTemplateProfileVersion());
        root.put("targetSlideCount", specification.getTargetSlideCount());
        root.put("slideCountTolerance", specification.getSlideCountTolerance());
        root.put("locale", specification.getLocale());
        root.put("provider", specification.getProvider());
        root.put("model", specification.getModel());
        root.put("aiSupplementPolicy", specification.getAiSupplementPolicy().name());
        root.put("lockedBy", String.valueOf(specification.getLockedBy()));
        root.put("lockedAt", specification.getLockedAt().atOffset(ZoneOffset.UTC).toString());
        ArrayNode slides = root.putArray("slides");
        for (PptSpecificationSlide slide : specification.getSlides()) {
            ObjectNode item = slides.addObject();
            item.put("slideId", slide.getSlideId());
            item.put("pageNumber", slide.getPageNumber());
            item.put("title", slide.getTitle());
            item.put("teachingGoal", slide.getTeachingGoal());
            item.set("semanticLayout", parseJson(slide.getSemanticLayoutJson()));
            ArrayNode blocks = item.putArray("contentBlocks");
            for (PptSpecificationContentBlock block : slide.getContentBlocks()) {
                ObjectNode value = blocks.addObject();
                value.put("blockId", block.getBlockId()); value.put("type", block.getType());
                value.put("content", block.getContent()); value.put("sourceType", block.getSourceType());
                value.put("sourceReference", block.getSourceReference()); value.put("locked", block.getLocked());
            }
            ArrayNode assets = item.putArray("assetRequirements");
            for (PptSpecificationAssetRequirement asset : slide.getAssetRequirements()) {
                ObjectNode value = assets.addObject();
                value.put("assetId", asset.getAssetId()); value.put("assetType", asset.getAssetType());
                value.put("source", asset.getSource()); value.put("approvalStatus", asset.getApprovalStatus());
                value.put("required", asset.getRequired()); value.put("placementIntent", asset.getPlacementIntent());
            }
            ArrayNode provenance = item.putArray("provenance");
            for (PptSpecificationProvenance entry : slide.getProvenance()) {
                ObjectNode value = provenance.addObject();
                value.put("sourceType", entry.getSourceType()); value.put("sourceReference", entry.getSourceReference());
            }
            item.put("notes", slide.getNotes() == null ? "" : slide.getNotes());
        }
        return root;
    }

    private JsonNode engineManifest(Project project, PptSpecificationVersion specification,
                                    List<AssetService.EngineManifest> manifests) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("contractVersion", "1.0.0");
        root.put("manifestId", "manifest-" + project.getId() + "-" + manifests.stream()
                .map(AssetService.EngineManifest::manifestVersion).max(Integer::compareTo).orElse(1));
        root.put("manifestVersion", manifests.stream().map(AssetService.EngineManifest::manifestVersion).max(Integer::compareTo).orElse(1));
        root.put("status", "APPROVED");
        root.put("projectId", String.valueOf(project.getId()));
        root.put("ownerUserId", String.valueOf(project.getOwnerUserId()));
        root.put("specificationId", specification.getSpecificationId());
        root.put("specificationVersion", specification.getVersionNumber());
        root.put("specificationChecksum", specification.getChecksum());
        ArrayNode entries = root.putArray("entries");
        for (AssetService.EngineManifest manifest : manifests) {
            ObjectNode entry = entries.addObject();
            entry.put("assetRequirementId", manifest.requirementKey());
            entry.put("resolution", "APPROVED_ASSET");
            entry.put("approvedAssetId", String.valueOf(manifest.candidateAssetId()));
            entry.put("assetType", assetTypeFor(specification, manifest.requirementKey()));
            entry.put("storageKey", manifest.storageKey());
            entry.put("fileSize", manifest.fileSize());
            entry.put("lastModifiedUtc", manifest.approvedFileLastModifiedUtc());
            entry.put("contentSha256", manifest.sha256());
        }
        return root;
    }

    private ObjectNode binding(String inputId, Integer version, String checksum) {
        ObjectNode binding = objectMapper.createObjectNode();
        binding.put("inputId", inputId);
        binding.put("inputVersion", version == null ? 1 : version);
        binding.put("inputChecksum", checksum);
        return binding;
    }

    private String assetTypeFor(PptSpecificationVersion specification, String assetId) {
        return specification.getSlides().stream().flatMap(slide -> slide.getAssetRequirements().stream())
                .filter(asset -> assetId.equals(asset.getAssetId())).findFirst()
                .map(PptSpecificationAssetRequirement::getAssetType).orElse("OTHER");
    }

    private String profileIdForSpecification(PptSpecificationVersion specification) {
        return specification.getTemplateProfileId();
    }

    private boolean isEngineProfile(JsonNode value) {
        return value != null && value.isObject()
                && value.hasNonNull("profileId") && value.hasNonNull("templateId")
                && value.hasNonNull("templateVersion") && value.hasNonNull("profileVersion")
                && value.has("pageSize") && value.has("spatialProfile")
                && value.has("templatePageReferences") && value.has("components");
    }

    private JsonNode parseJson(String value) {
        if (value == null || value.isBlank()) return null;
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { return null; }
    }

    private JsonNode requireEngineProfile(TemplateProfileVersion profile, TemplateSourceVersion source) {
        JsonNode direct = parseJson(profile.getProfileJson());
        JsonNode migrated = parseJson(profile.getEngineNativeProfileJson());
        if ((profile.getEngineNativeProfileJson() == null) != (profile.getEngineNativeProfileChecksum() == null)) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_BINDING_INVALID: persisted native profile identity is incomplete");
        }
        if (migrated != null && !sha256(profile.getEngineNativeProfileJson())
                .equalsIgnoreCase(profile.getEngineNativeProfileChecksum())) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_BINDING_INVALID: persisted native profile checksum mismatch");
        }
        if (!isEngineProfile(migrated)) {
            migrated = direct != null && direct.has("engineProfile")
                    ? direct.get("engineProfile") : null;
            if (isEngineProfile(direct)) migrated = direct;
        }
        if (!isEngineProfile(migrated)) {
            JsonNode capability = parseJson(profile.getCapabilityViewJson());
            migrated = capability != null && capability.has("engineProfile")
                    ? capability.get("engineProfile") : null;
        }
        if (!isEngineProfile(migrated)) {
            throw new ConflictException("ENGINE_PROFILE_MIGRATION_REQUIRED: legacy Profile must be migrated to the explicit Engine-native profile contract before generation");
        }
        final com.auvdidao.a12teachingagent.domain.template.TemplateStructuralSnapshot snapshot;
        try {
            snapshot = profileGate.loadAndVerifySnapshot(source.getId());
        } catch (RuntimeException exception) {
            throw new ConflictException("ENGINE_PROFILE_SOURCE_BINDING_INVALID: Parser snapshot is unavailable or invalid");
        }
        if (!"1.0.0".equals(migrated.path("contractVersion").asText())
                || !String.valueOf(profile.getId()).equals(migrated.path("profileId").asText())
                || !String.valueOf(profile.getTemplateId()).equals(migrated.path("templateId").asText())
                || profile.getVersionNumber() == null
                || migrated.path("profileVersion").asInt(-1) != profile.getVersionNumber()
                || source.getVersionNumber() == null
                || migrated.path("templateVersion").asInt(-1) != source.getVersionNumber()
                || profile.getStatus() == null
                || !profile.getStatus().name().equals(migrated.path("status").asText())
                || !String.valueOf(source.getId()).equals(migrated.path("sourceVersionId").asText())
                || source.getSha256() == null
                || !source.getSha256().equalsIgnoreCase(migrated.path("sourceSha256").asText())
                || profile.getParserSnapshotChecksum() == null
                || !profile.getParserSnapshotChecksum().equalsIgnoreCase(migrated.path("parserSnapshotChecksum").asText())
                || !snapshot.getChecksum().equalsIgnoreCase(migrated.path("parserSnapshotChecksum").asText())
                || !source.getTemplateId().equals(profile.getTemplateId())
                || !source.getProjectId().equals(profile.getProjectId())) {
            throw new ConflictException("ENGINE_PROFILE_BINDING_STALE: Profile/template/version/status does not match the locked source");
        }
        return migrated;
    }

    private ObjectNode approvedAssetFile(AssetService.EngineManifest manifest) {
        try {
            if (manifest == null || storageProperties == null
                    || manifest.storageKey() == null || manifest.storageKey().isBlank()
                    || manifest.fileSize() == null || manifest.fileSize() < 1
                    || manifest.sha256() == null || !manifest.sha256().matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("approved asset identity is incomplete");
            }
            Path root = Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
            Path key = Path.of(manifest.storageKey());
            if (key.isAbsolute() || manifest.storageKey().indexOf('\\') >= 0
                    || manifest.storageKey().indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("approved asset storageKey must be relative");
            }
            Path path = root.resolve(key).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)
                    || !path.equals(path.toRealPath())) {
                throw new IllegalArgumentException("approved asset file is outside controlled storage");
            }
            long size = Files.size(path);
            String sha256 = sha256File(path);
            String lastModifiedUtc = Files.getLastModifiedTime(path).toInstant().toString();
            if (manifest.approvedFileLastModifiedUtc() == null
                    || size != manifest.fileSize()
                    || !sha256.equalsIgnoreCase(manifest.sha256())
                    || !lastModifiedUtc.equals(manifest.approvedFileLastModifiedUtc())) {
                throw new IllegalArgumentException("approved asset file content identity changed");
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("assetRequirementId", manifest.requirementKey());
            item.put("approvedAssetId", String.valueOf(manifest.candidateAssetId()));
            item.put("storageKey", manifest.storageKey());
            item.put("sha256", manifest.sha256());
            item.put("size", size);
            item.put("lastModifiedUtc", lastModifiedUtc);
            return item;
        } catch (IOException | RuntimeException exception) {
            throw new ConflictException("ENGINE_APPROVED_ASSET_FILE_IDENTITY_INVALID: approved asset file must match storageKey/sha256/size/lastModifiedUtc");
        }
    }

    private String sourceLastModified(TemplateSourceVersion source) {
        try {
            Path root = Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
            Path path = root.resolve(source.getStorageKey()).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) return null;
            // Instant.toString() emits the canonical seconds-bearing UTC form
            // required by the Engine executor's strict Instant.parse contract.
            return Files.getLastModifiedTime(path).toInstant().toString();
        } catch (RuntimeException | java.io.IOException exception) {
            return null;
        }
    }

    private String sha256File(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String sha256CanonicalWithout(JsonNode input, Set<String> excluded) {
        if (!(input instanceof ObjectNode object)) throw new IllegalArgumentException("wire contract input must be an object");
        ObjectNode copy = object.deepCopy();
        excluded.forEach(copy::remove);
        try { return sha256(objectMapper.writeValueAsString(canonicalize(copy))); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot hash wire contract", exception); }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isValueNode()) return node;
        if (node.isArray()) { ArrayNode result = objectMapper.createArrayNode(); node.forEach(item -> result.add(canonicalize(item))); return result; }
        ObjectNode result = objectMapper.createObjectNode();
        node.fieldNames().forEachRemaining(name -> result.set(name, canonicalize(node.get(name))));
        java.util.TreeMap<String, JsonNode> sorted = new java.util.TreeMap<>();
        result.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
        ObjectNode ordered = objectMapper.createObjectNode(); sorted.forEach(ordered::set); return ordered;
    }

    private ApprovedManifestInput manifestInput(AssetService.EngineManifest manifest) {
        return new ApprovedManifestInput(manifest.id(), manifest.projectId(), manifest.ownerUserId(), manifest.candidateAssetId(), manifest.assetKey(),
                manifest.manifestVersion(), manifest.candidateVersion(), manifest.requirementKey(), manifest.placementIntent(), manifest.sourceType(),
                manifest.sourceReference(), manifest.storageKey(), manifest.mimeType(), manifest.fileSize(), manifest.sha256(), manifest.provider(), manifest.model(),
                manifest.status(), manifest.manifestChecksum(), manifest.approvedFileLastModifiedUtc());
    }

    private void validateResponse(GenerationRequest request, ExecutionResponse response) {
        var bindings = request.executionBindings();
        String expectedSpecificationChecksum = bindings == null || bindings.specificationChecksum() == null
                ? request.specification().checksum() : bindings.specificationChecksum();
        String expectedProfileChecksum = bindings == null || bindings.templateProfileChecksum() == null
                ? request.templateProfile().checksum() : bindings.templateProfileChecksum();
        String expectedManifestChecksum = bindings == null || bindings.assetManifestChecksum() == null
                ? manifestSetChecksumFromInputs(request.approvedAssetManifest()) : bindings.assetManifestChecksum();
        if (response == null || response.status() == null
                || (response.contractVersion() != null && !"2.0.0".equals(response.contractVersion()))
                || (response.requestId() != null && !request.executionContext().executionId().equals(response.requestId()))
                || !request.executionContext().executionId().equals(response.executionId())
                || !request.executionContext().engineVersion().equals(response.engineVersion())
                || !expectedSpecificationChecksum.equalsIgnoreCase(response.specificationChecksum())
                || !expectedProfileChecksum.equalsIgnoreCase(response.templateProfileChecksum())
                || !expectedManifestChecksum.equalsIgnoreCase(response.assetManifestChecksum())) {
            throw new PptEngineException("PPT_ENGINE_CONTRACT_FAILED", 502, "PPT Engine response binding does not match the submitted inputs");
        }
        List<ArtifactReceipt> artifacts = response.artifacts() == null ? List.of() : response.artifacts();
        if (response.status() == EngineStatus.FAILED && !artifacts.isEmpty()) {
            throw new PptEngineException("PPT_ENGINE_CONTRACT_FAILED", 502, "Failed Engine response must not contain artifact receipts");
        }
        if (response.status() != EngineStatus.FAILED && artifacts.isEmpty()) {
            throw new PptEngineException("PPT_ENGINE_CONTRACT_FAILED", 502, "Successful Engine response must contain an artifact receipt");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ArtifactReceipt artifact : artifacts) {
            if (artifact == null || artifact.artifactId() == null || artifact.artifactId().isBlank()
                    || artifact.artifactType() == null || artifact.artifactType().isBlank()
                    || artifact.storageKey() == null || artifact.storageKey().isBlank()
                    || artifact.sha256() == null || !artifact.sha256().matches("[0-9a-fA-F]{64}")
                    || artifact.fileSize() == null || artifact.fileSize() < 0
                    || !ids.add(artifact.artifactId())) {
                throw new PptEngineException("PPT_ENGINE_CONTRACT_FAILED", 502, "PPT Engine artifact receipt is invalid or duplicated");
            }
        }
        rejectSecretFields(json(response));
    }

    private String manifestSetChecksum(List<AssetService.EngineManifest> manifests) {
        return manifestSetChecksumFromInputs(manifests.stream().map(this::manifestInput).toList());
    }

    private String manifestSetChecksumFromInputs(List<ApprovedManifestInput> manifests) {
        String value = manifests.stream().sorted(Comparator.comparing(ApprovedManifestInput::assetKey).thenComparing(ApprovedManifestInput::manifestVersion))
                .map(item -> String.join("|", String.valueOf(item.id()), item.assetKey(), String.valueOf(item.manifestVersion()),
                        String.valueOf(item.candidateAssetId()), String.valueOf(item.candidateVersion()), item.sha256(),
                        String.valueOf(item.fileSize()), String.valueOf(item.approvedFileLastModifiedUtc()), item.manifestChecksum()))
                .reduce("", (left, right) -> left + right + "\n");
        return sha256(value);
    }

    private String inputIdentityChecksum(Long projectId, PptSpecificationVersion specification,
                                         TemplateProfileVersion profile,
                                         List<AssetService.EngineManifest> manifests,
                                         String engineVersion) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "project", projectId);
        appendCanonical(canonical, "spec.id", specification.getId());
        appendCanonical(canonical, "spec.identity", specification.getSpecificationId());
        appendCanonical(canonical, "spec.version", specification.getVersionNumber());
        appendCanonical(canonical, "spec.checksum", specification.getChecksum());
        appendCanonical(canonical, "profile.id", profile.getId());
        appendCanonical(canonical, "profile.templateId", profile.getTemplateId());
        appendCanonical(canonical, "profile.sourceVersionId", profile.getSourceVersionId());
        appendCanonical(canonical, "profile.version", profile.getVersionNumber());
        appendCanonical(canonical, "profile.checksum", profile.getChecksum());
        appendCanonical(canonical, "profile.engineNativeProfileChecksum", profile.getEngineNativeProfileChecksum());
        appendCanonical(canonical, "profile.capabilityViewVersion", profile.getCapabilityViewVersion());
        appendCanonical(canonical, "profile.capabilityViewChecksum", profile.getCapabilityViewChecksum());
        appendCanonical(canonical, "profile.ownerUserId", profile.getOwnedByTeacherId());
        appendCanonical(canonical, "profile.parserSnapshotChecksum", profile.getParserSnapshotChecksum());
        appendCanonical(canonical, "profile.confirmedChecksum", profile.getConfirmedChecksum());
        List<AssetService.EngineManifest> ordered = manifests.stream()
                .sorted(Comparator.comparing(AssetService.EngineManifest::assetKey)
                        .thenComparing(AssetService.EngineManifest::manifestVersion)
                        .thenComparing(AssetService.EngineManifest::id))
                .toList();
        appendCanonical(canonical, "manifest.count", ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            AssetService.EngineManifest manifest = ordered.get(index);
            appendCanonical(canonical, "manifest[" + index + "].id", manifest.id());
            appendCanonical(canonical, "manifest[" + index + "].projectId", manifest.projectId());
            appendCanonical(canonical, "manifest[" + index + "].ownerUserId", manifest.ownerUserId());
            appendCanonical(canonical, "manifest[" + index + "].assetKey", manifest.assetKey());
            appendCanonical(canonical, "manifest[" + index + "].version", manifest.manifestVersion());
            appendCanonical(canonical, "manifest[" + index + "].candidateAssetId", manifest.candidateAssetId());
            appendCanonical(canonical, "manifest[" + index + "].candidateVersion", manifest.candidateVersion());
            appendCanonical(canonical, "manifest[" + index + "].sha256", manifest.sha256());
            appendCanonical(canonical, "manifest[" + index + "].manifestChecksum", manifest.manifestChecksum());
            if (sourceRepository != null && storageProperties != null) {
                ObjectNode file = approvedAssetFile(manifest);
                appendCanonical(canonical, "manifest[" + index + "].fileSize", file.path("size").asLong());
                appendCanonical(canonical, "manifest[" + index + "].lastModifiedUtc", file.path("lastModifiedUtc").asText());
            }
        }
        appendCanonical(canonical, "engineVersion", engineVersion);
        return sha256(canonical.toString());
    }

    private void appendCanonical(StringBuilder canonical, String name, Object value) {
        String text = value == null ? "<null>" : String.valueOf(value);
        canonical.append(name).append('#').append(text.length()).append(':').append(text).append('\n');
    }

    private boolean isContractFailure(String code) {
        return code != null && (code.startsWith("PPT_ENGINE_CONTRACT")
                || code.startsWith("PPT_ENGINE_SECRET")
                || code.equals("PPT_ENGINE_EMPTY_RESPONSE"));
    }

    private GenerationJobStatus mapStatus(EngineStatus status) {
        return switch (status) {
            case SUCCEEDED -> GenerationJobStatus.SUCCEEDED;
            case PARTIAL -> GenerationJobStatus.PARTIAL;
            case SUCCEEDED_WITH_FEEDBACK -> GenerationJobStatus.SUCCEEDED_WITH_FEEDBACK;
            case FAILED -> GenerationJobStatus.FAILED;
        };
    }

    private GenerationJobResponse toResponse(GenerationJob job) {
        return new GenerationJobResponse(job.getId(), job.getProjectId(), job.getOwnerUserId(), job.getRequestedBy(), job.getExecutionId(), job.getIdempotencyKey(),
                job.getSpecificationVersionId(), job.getSpecificationVersion(), job.getSpecificationChecksum(), job.getTemplateProfileId(), job.getTemplateProfileVersion(),
                job.getTemplateProfileChecksum(), job.getAssetManifestVersion(), job.getAssetManifestChecksum(), job.getInputIdentityChecksum(), job.getEngineVersion(), job.getStatus(),
                job.getResponseReceiptJson(), job.getFailureCode(), job.getFailureMessage(), job.getCreatedAt(), job.getUpdatedAt());
    }

    private String bounded(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) throw new ConflictException(field + " is invalid");
        return value.trim();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new PptEngineException("PPT_ENGINE_CONTRACT_FAILED", 502, "PPT Engine contract cannot be serialized"); }
    }

    private void rejectSecretFields(String json) {
        if (json != null && (containsForbiddenField(parseJsonNode(json))
                || json.matches("(?i).*sk-[A-Za-z0-9_-]+.*"))) {
            throw new PptEngineException("PPT_ENGINE_SECRET_FIELD_REJECTED", 502, "PPT Engine contract contains a forbidden secret field");
        }
    }

    private boolean containsForbiddenField(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (SECRET_FIELD_PATTERN.matcher(field.getKey()).matches()
                        || containsForbiddenField(field.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) if (containsForbiddenField(item)) return true;
        }
        return false;
    }

    private JsonNode parseJsonNode(String json) {
        try { return objectMapper.readTree(json); }
        catch (JsonProcessingException exception) { return null; }
    }

    private String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private record AssetRequirementRef(String assetKey, String source, String placementIntent) { }
}
