package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.template.Template;
import com.auvdidao.a12teachingagent.domain.template.TemplateAnalysisResult;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileReview;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileReviewAction;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileOrigin;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileVersion;
import com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion;
import com.auvdidao.a12teachingagent.domain.template.TemplateStructuralSnapshot;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileReviewRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateAnalysisResultRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateRenderedSlideSetRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateStructuralSnapshotRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.material.MaterialParseService;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.auvdidao.a12teachingagent.template.TemplateDtos.*;

@Service
public class TemplateProfileService {

    private final TemplateService templateService;
    private final TemplateProfileVersionRepository profileRepository;
    private final TemplateProfileReviewRepository reviewRepository;
    private final ObjectMapper objectMapper;
    private final TemplateStructuralSnapshotRepository snapshotRepository;
    private final TemplateProfileContractGate profileContractGate;
    private final TemplateAnalysisResultRepository analysisResultRepository;
    private final TemplateRenderedSlideSetRepository renderedRepository;
    private final Validator validator;
    private final UploadedMaterialRepository materialRepository;
    private final MaterialPurposeRepository materialPurposeRepository;
    private final MaterialParseService materialParseService;
    private final ProjectRepository projectRepository;
    private final TemplateSourceVersionRepository sourceRepository;

    public TemplateProfileService(
            TemplateService templateService,
            TemplateProfileVersionRepository profileRepository,
            TemplateProfileReviewRepository reviewRepository,
            ObjectMapper objectMapper,
            TemplateStructuralSnapshotRepository snapshotRepository,
            TemplateProfileContractGate profileContractGate,
            TemplateAnalysisResultRepository analysisResultRepository,
            TemplateRenderedSlideSetRepository renderedRepository,
            Validator validator,
            UploadedMaterialRepository materialRepository,
            MaterialPurposeRepository materialPurposeRepository,
            MaterialParseService materialParseService,
            ProjectRepository projectRepository,
            TemplateSourceVersionRepository sourceRepository
    ) {
        this.templateService = templateService;
        this.profileRepository = profileRepository;
        this.reviewRepository = reviewRepository;
        this.objectMapper = objectMapper;
        this.snapshotRepository = snapshotRepository;
        this.profileContractGate = profileContractGate;
        this.analysisResultRepository = analysisResultRepository;
        this.renderedRepository = renderedRepository;
        this.validator = validator;
        this.materialRepository = materialRepository;
        this.materialPurposeRepository = materialPurposeRepository;
        this.materialParseService = materialParseService;
        this.projectRepository = projectRepository;
        this.sourceRepository = sourceRepository;
    }

    @Transactional
    public ProfileResponse createCandidate(Long projectId, Long templateId, RevisionRequest request) {
        Template template = templateService.requireTemplate(projectId, templateId);
        TemplateSourceVersion source = templateService.requireSource(projectId, templateId, request.sourceVersionId());
        TemplateProfileOrigin origin = request.origin() == null ? TemplateProfileOrigin.MANUAL_DRAFT : request.origin();
        String snapshotChecksum = requireCandidatePreconditions(source, origin, request.parserSnapshotChecksum());
        TemplateAnalysisResult analysis = origin == TemplateProfileOrigin.ANALYZER_CANDIDATE
                ? requireAnalyzerResult(projectId, source, request.analysisRunId(), snapshotChecksum) : null;
        String materialBindingJson = analysis == null ? null : requireMaterialParseBinding(projectId, source);
        CandidateProfileRequest effectiveProfile = analysis == null
                ? request.profile() : bindServerOwnedCandidate(analysis, request.profile());
        TemplateProfileStatus initialStatus = readyStatusFor(origin);
        if (analysis != null) {
            Optional<TemplateProfileVersion> existing = findExistingAnalyzerCandidate(template, source, analysis, effectiveProfile, materialBindingJson);
            if (existing.isPresent()) {
                ensureEngineNativeProfile(existing.get(), template, source, snapshotChecksum);
                return toResponse(existing.get());
            }
            try {
                return toResponse(createVersion(template, source, null, effectiveProfile, initialStatus, null, origin, snapshotChecksum, analysis, materialBindingJson));
            } catch (DataIntegrityViolationException exception) {
                throw analyzerIdentityConflict();
            }
        }
        return toResponse(createVersion(template, source, null, effectiveProfile, initialStatus, null, origin, snapshotChecksum, analysis, materialBindingJson));
    }

    @Transactional(readOnly = true)
    public List<ProfileSummary> history(Long projectId, Long templateId) {
        templateService.requireTemplate(projectId, templateId);
        return profileRepository.findByTemplateIdOrderByVersionNumberDesc(templateId).stream()
                .map(profile -> {
                    verifyIntegrity(profile);
                    return new ProfileSummary(profile.getId(), profile.getVersionNumber(), profile.getSourceVersionId(), profile.getStatus(), profile.getOrigin(),
                            profile.getChecksum(), profile.getCapabilityViewChecksum(), profile.getCreatedAt(), profile.getConfirmedAt());
                }).toList();
    }

    @Transactional(readOnly = true)
    public ProfileResponse detail(Long projectId, Long templateId, Long profileId) {
        return toResponse(requireProfile(projectId, templateId, profileId));
    }

    @Transactional
    public ProfileResponse editCandidate(Long projectId, Long templateId, Long profileId, CandidateProfileRequest request) {
        TemplateProfileVersion profile = requireProfile(projectId, templateId, profileId);
        ensureEditable(profile);
        ensureAnalyzerCandidateImmutable(profile);
        claimOwnership(profile);
        applyDocument(profile, request);
        addReview(profile, TemplateProfileReviewAction.EDITED, null);
        return toResponse(profileRepository.saveAndFlush(profile));
    }

    @Transactional
    public ProfileResponse submitReview(Long projectId, Long templateId, Long profileId, String note) {
        TemplateProfileVersion profile = requireProfile(projectId, templateId, profileId);
        ensureEditable(profile);
        if (profile.getStatus() != TemplateProfileStatus.CANDIDATE
                && profile.getStatus() != TemplateProfileStatus.READY) {
            throw new ConflictException("Only a CANDIDATE or READY profile can enter REVIEW");
        }
        profile.setStatus(TemplateProfileStatus.REVIEW);
        syncEngineNativeProfileStatus(profile);
        addReview(profile, TemplateProfileReviewAction.SUBMITTED, note);
        return toResponse(profileRepository.saveAndFlush(profile));
    }

    @Transactional
    public ProfileResponse confirm(Long projectId, Long templateId, Long profileId, ConfirmProfileRequest request) {
        TemplateProfileVersion profile = requireProfile(projectId, templateId, profileId);
        ensureEditable(profile);
        if (profile.getStatus() != TemplateProfileStatus.REVIEW) {
            throw new ConflictException("Only a REVIEW profile can be confirmed");
        }
        verifyIntegrity(profile);
        if (profile.getOrigin() != TemplateProfileOrigin.ANALYZER_CANDIDATE) {
            throw new ConflictException("MANUAL_DRAFT profiles are NOT_READY and cannot be confirmed");
        }
        if (profile.getRendererStatus() != com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus.SUCCEEDED
                || profile.getAnalyzerStatus() != com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus.SUCCEEDED
                || profile.getParserSnapshotChecksum() == null) {
            throw new ConflictException("Profile processing prerequisites are not ready for confirmation");
        }
        if (!profile.getChecksum().equals(request.checksum())) {
            throw new ConflictException("Profile checksum changed; refresh before confirming");
        }
        // Confirm is a legacy compatibility path. Keep the service-owned
        // native object in the same transaction and never source native data
        // from the teacher request.
        profile.setStatus(TemplateProfileStatus.CONFIRMED);
        syncEngineNativeProfileStatus(profile);
        profile.setConfirmedChecksum(profile.getChecksum());
        profile.setConfirmedAt(LocalDateTime.now());
        addReview(profile, TemplateProfileReviewAction.CONFIRMED, request.note());
        return toResponse(profileRepository.saveAndFlush(profile));
    }

    @Transactional
    public ProfileResponse createRevision(Long projectId, Long templateId, Long profileId, RevisionRequest request) {
        Template template = templateService.requireTemplate(projectId, templateId);
        TemplateProfileVersion current = requireProfile(projectId, templateId, profileId);
        TemplateSourceVersion source = templateService.requireSource(projectId, templateId, request.sourceVersionId());
        TemplateProfileOrigin origin = request.origin() == null ? TemplateProfileOrigin.MANUAL_DRAFT : request.origin();
        String snapshotChecksum = requireCandidatePreconditions(source, origin, request.parserSnapshotChecksum());
        TemplateAnalysisResult analysis = origin == TemplateProfileOrigin.ANALYZER_CANDIDATE
                ? requireAnalyzerResult(projectId, source, request.analysisRunId(), snapshotChecksum) : null;
        String materialBindingJson = analysis == null ? null : requireMaterialParseBinding(projectId, source);
        CandidateProfileRequest effectiveProfile = analysis == null
                ? request.profile() : bindServerOwnedCandidate(analysis, request.profile());
        TemplateProfileStatus initialStatus = readyStatusFor(origin);
        if (analysis != null) {
            Optional<TemplateProfileVersion> existing = findExistingAnalyzerCandidate(template, source, analysis, effectiveProfile, materialBindingJson);
            if (existing.isPresent()) {
                ensureEngineNativeProfile(existing.get(), template, source, snapshotChecksum);
                return toResponse(existing.get());
            }
            try {
                TemplateProfileVersion revision = createVersion(template, source, current.getId(), effectiveProfile, initialStatus, null, origin, snapshotChecksum, analysis, materialBindingJson);
                addReview(revision, TemplateProfileReviewAction.REVISION_CREATED, null);
                return toResponse(revision);
            } catch (DataIntegrityViolationException exception) {
                throw analyzerIdentityConflict();
            }
        }
        TemplateProfileVersion revision = createVersion(template, source, current.getId(), effectiveProfile, initialStatus, null, origin, snapshotChecksum, analysis, materialBindingJson);
        addReview(revision, TemplateProfileReviewAction.REVISION_CREATED, null);
        return toResponse(revision);
    }

    @Transactional
    public ProfileResponse rollback(Long projectId, Long templateId, Long profileId, RollbackRequest request) {
        Template template = templateService.requireTemplate(projectId, templateId);
        TemplateProfileVersion current = requireProfile(projectId, templateId, profileId);
        TemplateProfileVersion target = requireProfile(projectId, templateId, request.targetProfileVersionId());
        CandidateProfileRequest targetDocument = readDocument(target.getProfileJson());
        TemplateSourceVersion source = templateService.requireSource(projectId, templateId, target.getSourceVersionId());
        TemplateProfileVersion rollback = createVersion(template, source, current.getId(), targetDocument, TemplateProfileStatus.CANDIDATE, null,
                TemplateProfileOrigin.MANUAL_DRAFT, null, null, null);
        addReview(rollback, TemplateProfileReviewAction.ROLLBACK_CREATED, "Rollback source profile version " + target.getVersionNumber());
        return toResponse(rollback);
    }

    @Transactional(readOnly = true)
    public CapabilityViewResponse capabilityView(Long projectId, Long templateId, Long profileId) {
        TemplateProfileVersion profile = requireProfile(projectId, templateId, profileId);
        verifyIntegrity(profile);
        return toCapabilityView(profile);
    }

    /**
     * Internal, owner-scoped bridge consumed by the LessonForge agent service.
     * The caller supplies only the authenticated teacher identity, mission
     * correlation id and template digest.  Project/template/profile identity
     * is resolved from the Java-owned rows and the native profile is returned
     * only after its persisted integrity and READY gates pass.
     */
    @Transactional(readOnly = true)
    public InternalCapabilityResponse internalCapability(InternalCapabilityRequest request) {
        String sha256 = request.templateFileSha256().toLowerCase(Locale.ROOT);
        List<TemplateSourceVersion> sources = sourceRepository.findByOwnerUserIdAndSha256(request.ownerUserId(), sha256);
        record Match(TemplateSourceVersion source, TemplateProfileVersion profile) { }
        List<Match> matches = new ArrayList<>();
        for (TemplateSourceVersion source : sources) {
            profileRepository.findReadyByOwnerAndSource(request.ownerUserId(), source.getId(), TemplateProfileStatus.READY)
                    .stream()
                    .filter(profile -> request.ownerUserId().equals(profile.getOwnedByTeacherId())
                            && profile.getEngineNativeProfileJson() != null
                            && profile.getEngineNativeProfileChecksum() != null)
                    .forEach(profile -> matches.add(new Match(source, profile)));
        }
        if (matches.isEmpty()) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_UNIQUE");
        }
        // A source may legitimately have several READY profile versions as the
        // teacher re-runs analysis. The repository orders versions newest first;
        // select that newest version for one source, but keep different source
        // rows fail-closed because a digest alone cannot disambiguate them.
        Long sourceId = matches.get(0).source().getId();
        if (matches.stream().anyMatch(match -> !sourceId.equals(match.source().getId()))) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_UNIQUE");
        }
        Match match = matches.get(0);
        TemplateSourceVersion source = match.source();
        TemplateProfileVersion profile = match.profile();
        verifyIntegrity(profile);
        if (!sha256.equalsIgnoreCase(source.getSha256())) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_SOURCE_SHA_INVALID");
        }
        JsonNode nativeProfile = readJson(profile.getEngineNativeProfileJson());
        if (!nativeProfile.isObject()
                || !"READY".equalsIgnoreCase(nativeProfile.path("status").asText())
                || !"EXECUTION_READY".equalsIgnoreCase(nativeProfile.path("executionStatus").asText())
                || profile.getEngineNativeProfileChecksum() == null
                || !TemplateChecksum.sha256(profile.getEngineNativeProfileJson())
                .equalsIgnoreCase(profile.getEngineNativeProfileChecksum())) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY");
        }
        return new InternalCapabilityResponse(
                request.missionId(),
                request.ownerUserId(),
                profile.getProjectId(),
                profile.getTemplateId(),
                profile.getSourceVersionId(),
                source.getSha256().toLowerCase(Locale.ROOT),
                String.valueOf(source.getVersionNumber()),
                String.valueOf(profile.getVersionNumber()),
                nativeProfile,
                profile.getEngineNativeProfileChecksum()
        );
    }

    private TemplateProfileVersion createVersion(Template template, TemplateSourceVersion source, Long parentId,
                                                 CandidateProfileRequest request, TemplateProfileStatus status, Long ownerId,
                                                 TemplateProfileOrigin origin, String parserSnapshotChecksum,
                                                 TemplateAnalysisResult analysis, String materialBindingJson) {
        if (request == null) throw new BadRequestException("Profile document is required");
        JsonNode profileNode = objectMapper.valueToTree(request);
        String profileJson = TemplateChecksum.canonicalJson(objectMapper, profileNode);
        String capabilityJson = TemplateChecksum.canonicalJson(objectMapper, capabilityNode(request));
        TemplateProfileVersion profile = new TemplateProfileVersion();
        profile.setTemplateId(template.getId());
        profile.setProjectId(template.getProjectId());
        profile.setSourceVersionId(source.getId());
        profile.setParentProfileVersionId(parentId);
        profile.setVersionNumber(profileRepository.findTopByTemplateIdOrderByVersionNumberDesc(template.getId())
                .map(previous -> previous.getVersionNumber() + 1).orElse(1));
        profile.setCreatedByUserId(templateService.currentUserId().orElse(null));
        // Analyzer candidates are server-owned by the same owner as the
        // verified source.  A null owner would later make a real Generation
        // binding impossible, so do not create a READY row without identity.
        Long serverOwnerId = ownerId;
        if (serverOwnerId == null && origin == TemplateProfileOrigin.ANALYZER_CANDIDATE) {
            serverOwnerId = source.getCreatedByUserId() != null
                    ? source.getCreatedByUserId() : templateService.currentUserId().orElse(null);
        }
        profile.setOwnedByTeacherId(serverOwnerId);
        profile.setOrigin(origin);
        profile.setParserSnapshotChecksum(parserSnapshotChecksum);
        profile.setMaterialParseBindingJson(materialBindingJson);
        profile.setMaterialParseBindingChecksum(materialBindingJson == null ? null : TemplateChecksum.sha256(materialBindingJson));
        profile.setRendererStatus(source.getRenderStatus());
        profile.setAnalyzerStatus(source.getAnalysisStatus());
        profile.setStatus(status);
        profile.setProfileJson(profileJson);
        profile.setChecksum(TemplateChecksum.sha256(profileJson));
        profile.setCapabilityViewVersion(1);
        profile.setCapabilityViewJson(capabilityJson);
        profile.setCapabilityViewChecksum(TemplateChecksum.sha256(capabilityJson));
        if (analysis != null) {
            profile.setAnalysisRunId(analysis.getAnalysisRunId());
            profile.setAnalyzerInputSha256(analysis.getInputSha256());
            profile.setAnalyzerOutputSha256(analysis.getOutputSha256());
            profile.setRenderedOutputSha256(analysis.getRenderedOutputSha256());
            profile.setRenderedOutputSizeBytes(analysis.getRenderedOutputSizeBytes());
            // Both persisted views are derived from the validated service-owned
            // Candidate, never from the client request DTO.
            profile.setAnalyzerProposalJson(profileJson);
        }
        TemplateProfileVersion saved = profileRepository.saveAndFlush(profile);
        if (status == TemplateProfileStatus.READY && origin == TemplateProfileOrigin.ANALYZER_CANDIDATE) {
            ensureEngineNativeProfile(saved, template, source, parserSnapshotChecksum);
        }
        return saved;
    }

    /**
     * Creates the native Engine profile only from the verified source snapshot.
     * The client CandidateProfileRequest and the semantic Capability View are
     * intentionally not consulted here.
     */
    private void ensureEngineNativeProfile(TemplateProfileVersion profile, Template template,
                                            TemplateSourceVersion source, String snapshotChecksum) {
        if (profile.getId() == null || source.getId() == null || snapshotChecksum == null) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: profile/source identity is incomplete");
        }
        if (profile.getEngineNativeProfileJson() != null || profile.getEngineNativeProfileChecksum() != null) {
            if (profile.getEngineNativeProfileJson() == null
                    || profile.getEngineNativeProfileChecksum() == null
                    || !TemplateChecksum.sha256(profile.getEngineNativeProfileJson())
                    .equalsIgnoreCase(profile.getEngineNativeProfileChecksum())) {
                throw new ConflictException("ENGINE_NATIVE_PROFILE_BINDING_INVALID: persisted native profile checksum mismatch");
            }
            TemplateStructuralSnapshot snapshot = profileContractGate.loadAndVerifySnapshot(source.getId());
            verifyNativeSourceBinding(profile, source, snapshot, snapshotChecksum);
            return;
        }
        if (profile.getOwnedByTeacherId() == null || source.getSha256() == null
                || !source.getSha256().matches("[0-9a-fA-F]{64}")) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: owner or source identity is unavailable");
        }
        TemplateStructuralSnapshot snapshot = profileContractGate.loadAndVerifySnapshot(source.getId());
        if (!template.getId().equals(snapshot.getTemplateId())
                || !source.getId().equals(snapshot.getSourceVersionId())
                || !snapshotChecksum.equalsIgnoreCase(snapshot.getChecksum())) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_BINDING_STALE: source snapshot identity does not match");
        }
        String nativeJson = deriveEngineNativeProfile(profile, source, snapshot);
        profile.setEngineNativeProfileJson(nativeJson);
        profile.setEngineNativeProfileChecksum(TemplateChecksum.sha256(nativeJson));
        profileRepository.saveAndFlush(profile);
    }

    private void syncEngineNativeProfileStatus(TemplateProfileVersion profile) {
        if (profile.getEngineNativeProfileJson() == null
                && profile.getEngineNativeProfileChecksum() == null) {
            // Manual drafts have no Engine-native object by design. Their
            // legacy review lifecycle remains available, while any partial
            // native binding still fails closed below.
            return;
        }
        if (profile.getEngineNativeProfileJson() == null
                || profile.getEngineNativeProfileChecksum() == null) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: status transition requires a persisted native profile");
        }
        try {
            JsonNode parsed = objectMapper.readTree(profile.getEngineNativeProfileJson());
            if (!(parsed instanceof ObjectNode nativeProfile)) {
                throw new ConflictException("ENGINE_NATIVE_PROFILE_BINDING_INVALID: persisted native profile is not an object");
            }
            nativeProfile.put("status", profile.getStatus().name());
            String canonical = TemplateChecksum.canonicalJson(objectMapper, nativeProfile);
            profile.setEngineNativeProfileJson(canonical);
            profile.setEngineNativeProfileChecksum(TemplateChecksum.sha256(canonical));
        } catch (ConflictException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_BINDING_INVALID: persisted native profile JSON is invalid");
        }
    }

    private void verifyNativeSourceBinding(TemplateProfileVersion profile, TemplateSourceVersion source,
                                           TemplateStructuralSnapshot snapshot, String snapshotChecksum) {
        JsonNode nativeProfile;
        try {
            nativeProfile = objectMapper.readTree(profile.getEngineNativeProfileJson());
        } catch (Exception exception) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_BINDING_INVALID: persisted native profile JSON is invalid");
        }
        if (nativeProfile == null || !nativeProfile.isObject()
                || !String.valueOf(source.getId()).equals(nativeProfile.path("sourceVersionId").asText())
                || source.getSha256() == null
                || !source.getSha256().equalsIgnoreCase(nativeProfile.path("sourceSha256").asText())
                || profile.getParserSnapshotChecksum() == null
                || !profile.getParserSnapshotChecksum().equalsIgnoreCase(nativeProfile.path("parserSnapshotChecksum").asText())
                || !snapshot.getChecksum().equalsIgnoreCase(nativeProfile.path("parserSnapshotChecksum").asText())
                || snapshotChecksum == null || !snapshotChecksum.equalsIgnoreCase(snapshot.getChecksum())) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_BINDING_STALE: native source or Parser snapshot identity does not match");
        }
    }

    private String deriveEngineNativeProfile(TemplateProfileVersion profile, TemplateSourceVersion source,
                                             TemplateStructuralSnapshot snapshot) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(snapshot.getSnapshotJson());
        } catch (Exception exception) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: Parser snapshot JSON is invalid");
        }
        if (root == null || !root.isObject() || !root.path("slides").isArray()
                || root.path("slides").isEmpty() || root.path("slideCount").asInt(-1) != snapshot.getSlideCount()) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: Parser snapshot has no complete slide structure");
        }
        long pageWidth = snapshotEmu(root, "pageWidth");
        long pageHeight = snapshotEmu(root, "pageHeight");
        ObjectNode nativeProfile = objectMapper.createObjectNode();
        nativeProfile.put("contractVersion", "1.0.0");
        nativeProfile.put("profileId", String.valueOf(profile.getId()));
        nativeProfile.put("templateId", String.valueOf(profile.getTemplateId()));
        nativeProfile.put("projectId", String.valueOf(profile.getProjectId()));
        nativeProfile.put("ownerUserId", String.valueOf(profile.getOwnedByTeacherId()));
        nativeProfile.put("templateVersion", source.getVersionNumber());
        nativeProfile.put("profileVersion", profile.getVersionNumber());
        nativeProfile.put("status", profile.getStatus().name());
        nativeProfile.put("executionStatus", "EXECUTION_READY");
        nativeProfile.put("sourceVersionId", source.getId());
        nativeProfile.put("sourceSha256", source.getSha256());
        nativeProfile.put("parserSnapshotChecksum", snapshot.getChecksum());
        ObjectNode pageSize = nativeProfile.putObject("pageSize");
        pageSize.put("widthEmu", checkedInt(pageWidth, "pageWidth"));
        pageSize.put("heightEmu", checkedInt(pageHeight, "pageHeight"));
        ObjectNode spatial = nativeProfile.putObject("spatialProfile");
        spatial.put("safeMarginLeftEmu", 0);
        spatial.put("safeMarginTopEmu", 0);
        spatial.put("safeMarginRightEmu", 0);
        spatial.put("safeMarginBottomEmu", 0);
        ObjectNode textFitPolicy = nativeProfile.putObject("textFitPolicy");
        textFitPolicy.put("policyVersion", "1.0.0");
        textFitPolicy.put("mode", "NO_ADJUSTMENT_PROFILE_V1");
        textFitPolicy.put("fontEnvironmentVersion", "UNBOUND");
        textFitPolicy.put("minimumFontSizePt", 1);
        textFitPolicy.put("defaultFontSizePt", 1);
        textFitPolicy.put("maximumFontSizePt", 1);
        textFitPolicy.put("fontSizeStepPt", 1);
        textFitPolicy.put("minimumLineSpacingPct", 100);
        textFitPolicy.put("defaultLineSpacingPct", 100);
        textFitPolicy.put("maximumLineSpacingPct", 100);
        textFitPolicy.put("lineSpacingStepPct", 1);
        textFitPolicy.put("maxTextBoxGrowthWidthEmu", 0);
        textFitPolicy.put("maxTextBoxGrowthHeightEmu", 0);
        textFitPolicy.put("minimumParagraphSpacingPt", 0);
        textFitPolicy.put("defaultParagraphSpacingPt", 0);
        textFitPolicy.put("maximumParagraphSpacingPt", 0);
        textFitPolicy.put("paragraphSpacingStepPt", 1);
        textFitPolicy.putArray("adjustmentOrder");

        ArrayNode pages = nativeProfile.putArray("templatePageReferences");
        ArrayNode components = nativeProfile.putArray("components");
        ArrayNode preservedNativeObjects = nativeProfile.putArray("preservedNativeObjects");
        int componentNumber = 0;
        for (JsonNode slide : root.path("slides")) {
            int pageNumber = slide.path("pageNumber").asInt(-1);
            if (pageNumber < 1 || !slide.path("shapes").isArray() || slide.path("shapes").isEmpty()) {
                throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: page has no objective shape references");
            }
            ObjectNode page = pages.addObject();
            page.put("pageReferenceId", "template-" + profile.getTemplateId() + "-v" + source.getVersionNumber() + "-page-" + pageNumber);
            page.put("sourceSlide", pageNumber);
            PageRoleMapping pageRole = resolvePageRole(profile, pageNumber);
            page.put("semanticRole", pageRole.role());
            page.put("semanticRoleSource", pageRole.source());
            ArrayNode pageObjects = page.putArray("objectIds");
            for (JsonNode shape : slide.path("shapes")) {
                String reference = shape.path("reference").asText("");
                if (reference.isBlank()) {
                    throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: shape reference is missing");
                }
                String objectId = nativeObjectId(reference);
                pageObjects.add(objectId);
                String preserveReason = preserveOnlyReason(shape, pageWidth, pageHeight, componentNumber);
                if (preserveReason != null) {
                    preservedNativeObjects.add(preservedNativeObject(shape, pageNumber, objectId, preserveReason));
                    continue;
                }
                ObjectNode component = components.addObject();
                component.put("componentId", "component-" + (++componentNumber));
                component.put("name", objectId);
                String semanticRole = semanticRole(shape);
                component.put("semanticRole", semanticRole);
                component.put("sourceSlide", pageNumber);
                ArrayNode refs = component.putArray("shapeRefs");
                ObjectNode ref = refs.addObject();
                ref.put("objectType", objectType(shape));
                ref.put("objectId", objectId);
                component.putArray("childComponentIds");
                ArrayNode slots = component.putArray("slots");
                ObjectNode slot = slots.addObject();
                slot.put("slotId", "slot-" + componentNumber);
                slot.put("semanticRole", semanticRole);
                ArrayNode accepted = slot.putArray("acceptedContentTypes");
                accepted.add(contentType(shape));
                slot.set("bounds", bounds(shape, pageWidth, pageHeight));
                slot.put("required", false);
                ObjectNode capacity = slot.putObject("capacityConstraint");
                capacity.putNull("maxCharacters");
                capacity.putNull("maxItems");
                component.put("transformConstraint", "FIXED");
                ObjectNode style = component.putObject("fixedStyle");
                style.put("styleId", "style-template-default");
                style.put("fontToken", "template-default");
                style.put("colorToken", "template-default");
                style.put("preserveTheme", true);
                component.put("reusable", false);
                component.put("confidence", 1.0);
                component.put("teacherConfirmed", false);
                // Execution eligibility is a service-owned projection of the
                // validated snapshot classification. It is deliberately
                // independent from teacher confirmation and reusable-template
                // semantics; the Engine must never infer teacher approval from
                // this field.
                component.put("executionEligibility", "EXECUTION_READY");
            }
        }
        if (pages.size() != snapshot.getSlideCount() || components.isEmpty()) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: snapshot cannot form a complete native profile");
        }
        return TemplateChecksum.canonicalJson(objectMapper, nativeProfile);
    }

    private PageRoleMapping resolvePageRole(TemplateProfileVersion profile, int pageNumber) {
        final JsonNode capability;
        try {
            capability = objectMapper.readTree(profile.getCapabilityViewJson());
        } catch (Exception exception) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: capability view JSON is invalid");
        }
        Set<String> advertisedRoles = new LinkedHashSet<>();
        JsonNode pageRoles = capability == null ? null : capability.path("pageRoles");
        if (pageRoles != null && pageRoles.isArray()) {
            for (JsonNode role : pageRoles) {
                String value = role.asText("").trim().toUpperCase(java.util.Locale.ROOT);
                if (!value.isBlank()) {
                    advertisedRoles.add(value);
                }
            }
        }
        if (pageNumber == 1 && advertisedRoles.contains("COVER")) {
            return new PageRoleMapping("COVER", "ANALYZER_PROFILE_COVER_FIRST_PAGE");
        }
        if (pageNumber > 1 && advertisedRoles.contains("CONTENT")) {
            return new PageRoleMapping("CONTENT", "ANALYZER_PROFILE_CONTENT_REMAINDER");
        }
        // A role that cannot be grounded in the server-owned capability view
        // is explicit and schema-valid, but intentionally cannot be resolved by
        // the Engine's exact-role gate. This keeps the failure observable
        // instead of guessing or silently using PAGE for every source slide.
        return new PageRoleMapping("UNMAPPED", "PROFILE_ROLE_UNAVAILABLE_FAIL_CLOSED");
    }

    private record PageRoleMapping(String role, String source) {
    }

    private String preserveOnlyReason(JsonNode shape, long pageWidth, long pageHeight, int componentNumber) {
        if (shape.path("fixedDecoration").asBoolean(false)) {
            if (!isSupportedFixedDecoration(shape)) {
                return "FIXED_DECORATION_EVIDENCE_INVALID";
            }
            return "FIXED_DECORATION";
        }
        if (isSupportedNegativeXContentPlaceholder(shape, pageWidth, pageHeight)) {
            return "CONTENT_PLACEHOLDER_NEGATIVE_X";
        }
        String type = shape.path("type").asText("");
        String lowerType = type.toLowerCase(java.util.Locale.ROOT);
        if (lowerType.contains("group") || isLineLike(shape)) {
            return "GROUP_OR_CONNECTOR_NOT_EXECUTABLE_IN_V1";
        }
        if (!isKnownExecutableObjectType(shape)) {
            return "UNKNOWN_OR_SPECIAL_OBJECT_TYPE";
        }
        if (!hasExecutableGeometry(shape)) {
            return "NON_POSITIVE_OR_INVALID_GEOMETRY";
        }
        if (componentNumber >= 500) {
            return "ENGINE_COMPONENT_LIMIT";
        }
        return null;
    }

    private boolean isKnownExecutableObjectType(JsonNode shape) {
        String type = shape.path("type").asText("");
        String lowerType = type.toLowerCase(java.util.Locale.ROOT);
        return "XSLFAutoShape".equals(type)
                || "XSLFTextBox".equals(type)
                || "XSLFPictureShape".equals(type)
                || "XSLFTable".equals(type)
                || lowerType.contains("chart");
    }

    private boolean hasExecutableGeometry(JsonNode shape) {
        String[] fields = {"x", "y", "width", "height"};
        double[] values = new double[fields.length];
        for (int index = 0; index < fields.length; index++) {
            if (!shape.path(fields[index]).isNumber()) {
                return false;
            }
            values[index] = shape.path(fields[index]).asDouble(Double.NaN);
            if (!Double.isFinite(values[index])) {
                return false;
            }
        }
        if (values[0] < 0d || values[1] < 0d || values[2] <= 0d || values[3] <= 0d) {
            return false;
        }
        try {
            long x = coordinateEmu(values[0]);
            long y = coordinateEmu(values[1]);
            long width = coordinateEmu(values[2]);
            long height = coordinateEmu(values[3]);
            return x >= 0 && y >= 0 && width >= 1 && height >= 1
                    && x <= 100_000_000L && y <= 100_000_000L
                    && width <= 100_000_000L && height <= 100_000_000L;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ObjectNode preservedNativeObject(JsonNode shape, int pageNumber, String objectId, String reason) {
        ObjectNode preserved = objectMapper.createObjectNode();
        preserved.put("sourceSlide", pageNumber);
        preserved.put("stableNativeReference", objectId);
        preserved.put("objectType", objectType(shape));
        preserved.put("ooxmlType", shape.path("type").asText("UNKNOWN"));
        preserved.put("geometry", shape.path("geometry").asText("UNKNOWN"));
        ObjectNode originalBounds = preserved.putObject("originalBounds");
        copyNumberOrNull(shape, originalBounds, "x");
        copyNumberOrNull(shape, originalBounds, "y");
        copyNumberOrNull(shape, originalBounds, "width");
        copyNumberOrNull(shape, originalBounds, "height");
        originalBounds.put("coordinateUnit", shape.path("coordinateUnit").asText("UNKNOWN"));
        preserved.put("placeholder", shape.path("placeholder").asBoolean(false));
        preserved.put("contentPlaceholder", shape.path("contentPlaceholder").asBoolean(false));
        preserved.put("textContent", shape.path("textContent").asBoolean(false));
        preserved.put("semanticRole", semanticRole(shape));
        preserved.put("fixedDecoration", shape.path("fixedDecoration").asBoolean(false));
        preserved.put("classification", "PRESERVE_ONLY");
        preserved.put("classificationReason", reason);
        preserved.put("editable", false);
        preserved.putArray("relationships");
        preserved.put("relationshipEvidence", "PARSER_SNAPSHOT_RELATIONSHIPS_NOT_EXPOSED");
        return preserved;
    }

    private void copyNumberOrNull(JsonNode source, ObjectNode target, String field) {
        if (source.path(field).isNumber()) {
            target.set(field, source.path(field));
        } else {
            target.putNull(field);
        }
    }

    private long snapshotEmu(JsonNode root, String field) {
        if (!root.path(field).isNumber() || root.path(field).asDouble() <= 0) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: snapshot " + field + " is unavailable");
        }
        double value = root.path(field).asDouble();
        double emu = value < 100_000d ? value * 12_700d : value;
        if (emu < 1 || emu > Integer.MAX_VALUE) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: snapshot " + field + " is out of bounds");
        }
        return Math.round(emu);
    }

    private int checkedInt(long value, String field) {
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: " + field + " is out of bounds");
        }
        return (int) value;
    }

    private String nativeObjectId(String reference) {
        String value = reference.replace('/', '.').replace('\\', '.');
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: snapshot reference is not a stable Engine object id");
        }
        return value;
    }

    private ObjectNode bounds(JsonNode shape, long pageWidth, long pageHeight) {
        ObjectNode value = objectMapper.createObjectNode();
        boolean lineLike = isLineLike(shape);
        boolean negativeContentPlaceholder = isSupportedNegativeXContentPlaceholder(shape, pageWidth, pageHeight);
        // A shape may legally touch the slide's left/top edge. Keep x/y at
        // zero while retaining the separate positive-width/height checks.
        long x = snapshotCoordinate(shape, "x", true, negativeContentPlaceholder);
        value.put("leftEmu", negativeContentPlaceholder ? checkedSignedCoordinateInt(x, "shape.x")
                : checkedCoordinateInt(x, "shape.x"));
        value.put("topEmu", checkedCoordinateInt(snapshotCoordinate(shape, "y", true, false), "shape.y"));
        long width = snapshotCoordinate(shape, "width", lineLike, false);
        long height = snapshotCoordinate(shape, "height", lineLike, false);
        if (lineLike && width == 0 && height == 0) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: line geometry has no positive axis");
        }
        value.put("widthEmu", lineLike ? checkedCoordinateInt(width, "shape.width") : checkedInt(width, "shape.width"));
        value.put("heightEmu", lineLike ? checkedCoordinateInt(height, "shape.height") : checkedInt(height, "shape.height"));
        return value;
    }

    private boolean isLineLike(JsonNode shape) {
        return "connector".equalsIgnoreCase(shape.path("geometry").asText())
                || "line".equalsIgnoreCase(shape.path("geometry").asText())
                || shape.path("type").asText("").toLowerCase(java.util.Locale.ROOT).contains("connector");
    }

    private boolean isSupportedFixedDecoration(JsonNode shape) {
        return "XSLFAutoShape".equals(shape.path("type").asText())
                && "rect".equalsIgnoreCase(shape.path("geometry").asText())
                && shape.path("fixedDecorationReason").asText("").equals("EMPTY_AUTOSHAPE_NO_FILL")
                && !shape.path("textContent").asBoolean(true)
                && (shape.path("width").asDouble(-1) == 0d || shape.path("height").asDouble(-1) == 0d);
    }

    private boolean isSupportedNegativeXContentPlaceholder(JsonNode shape, long pageWidth, long pageHeight) {
        if (!shape.path("contentPlaceholder").asBoolean(false)
                || !shape.path("placeholder").asBoolean(false)
                || !"XSLFAutoShape".equals(shape.path("type").asText())
                || shape.path("picture").asBoolean(false)
                || shape.path("table").asBoolean(false)
                || shape.path("chart").asBoolean(false)) {
            return false;
        }
        double x = shape.path("x").asDouble(Double.NaN);
        double y = shape.path("y").asDouble(Double.NaN);
        double width = shape.path("width").asDouble(Double.NaN);
        double height = shape.path("height").asDouble(Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)
                || x >= 0d || y < 0d || width <= 0d || height <= 0d) {
            return false;
        }
        long xEmu = coordinateEmu(x);
        long yEmu = coordinateEmu(y);
        long widthEmu = coordinateEmu(width);
        long heightEmu = coordinateEmu(height);
        return xEmu >= -pageWidth
                && xEmu < pageWidth
                && yEmu < pageHeight
                && yEmu > -heightEmu
                && xEmu > -widthEmu;
    }

    private int checkedCoordinateInt(long value, String field) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: " + field + " is out of bounds");
        }
        return (int) value;
    }

    private int checkedSignedCoordinateInt(long value, String field) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: " + field + " is out of bounds");
        }
        return (int) value;
    }

    private long snapshotCoordinate(JsonNode shape, String field, boolean allowZero, boolean allowNegative) {
        if (!shape.path(field).isNumber()) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: shape " + field + " is unavailable");
        }
        double value = shape.path(field).asDouble();
        if (!Double.isFinite(value)
                || (!allowNegative && ((allowZero && value < 0) || (!allowZero && value <= 0)))) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: shape " + field + " is out of bounds");
        }
        double emu = coordinateEmu(value);
        if (emu < Integer.MIN_VALUE || emu > Integer.MAX_VALUE) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: shape " + field + " is out of bounds");
        }
        return Math.round(emu);
    }

    private long coordinateEmu(double value) {
        double emu = value < 100_000d ? value * 12_700d : value;
        if (!Double.isFinite(emu) || emu < Long.MIN_VALUE || emu > Long.MAX_VALUE) {
            throw new ConflictException("ENGINE_NATIVE_PROFILE_NOT_READY: shape coordinate is out of bounds");
        }
        return Math.round(emu);
    }

    private String objectType(JsonNode shape) {
        String type = shape.path("type").asText("").toLowerCase();
        if (Boolean.TRUE.equals(shape.path("text").asBoolean(false))) return "TEXT";
        if (Boolean.TRUE.equals(shape.path("picture").asBoolean(false))) return "PICTURE";
        if (Boolean.TRUE.equals(shape.path("table").asBoolean(false))) return "TABLE";
        if (Boolean.TRUE.equals(shape.path("chart").asBoolean(false)) || type.contains("chart")) return "CHART";
        if (type.contains("group")) return "GROUP";
        return "SHAPE";
    }

    private String semanticRole(JsonNode shape) {
        return switch (objectType(shape)) {
            case "TEXT" -> "TEXT";
            case "PICTURE" -> "IMAGE";
            case "TABLE" -> "TABLE";
            case "CHART" -> "CHART";
            default -> "BODY";
        };
    }

    private String contentType(JsonNode shape) {
        return switch (objectType(shape)) {
            case "PICTURE" -> "IMAGE";
            case "TABLE" -> "TABLE";
            case "CHART" -> "CHART";
            default -> "TEXT";
        };
    }

    private void applyDocument(TemplateProfileVersion profile, CandidateProfileRequest request) {
        JsonNode profileNode = objectMapper.valueToTree(request);
        String profileJson = TemplateChecksum.canonicalJson(objectMapper, profileNode);
        String capabilityJson = TemplateChecksum.canonicalJson(objectMapper, capabilityNode(request));
        profile.setProfileJson(profileJson);
        profile.setChecksum(TemplateChecksum.sha256(profileJson));
        profile.setCapabilityViewJson(capabilityJson);
        profile.setCapabilityViewChecksum(TemplateChecksum.sha256(capabilityJson));
    }

    private JsonNode capabilityNode(CandidateProfileRequest request) {
        var view = objectMapper.createObjectNode();
        view.put("displayName", request.displayName());
        view.set("pageRoles", objectMapper.valueToTree(request.pageRoles() == null ? List.of() : request.pageRoles()));
        view.set("semanticLayouts", objectMapper.valueToTree(request.semanticLayouts() == null ? List.of() : request.semanticLayouts()));
        view.set("imageCapability", objectMapper.valueToTree(request.imageCapability()));
        view.set("tableCapability", objectMapper.valueToTree(request.tableCapability()));
        view.set("chartCapability", objectMapper.valueToTree(request.chartCapability()));
        view.set("fixedBrandAreas", objectMapper.valueToTree(request.fixedBrandAreas() == null ? List.of() : request.fixedBrandAreas()));
        view.set("limitations", objectMapper.valueToTree(request.limitations() == null ? List.of() : request.limitations()));
        return view;
    }

    private TemplateProfileVersion requireProfile(Long projectId, Long templateId, Long profileId) {
        templateService.requireTemplate(projectId, templateId);
        if (profileId == null || profileId <= 0) throw new BadRequestException("profileId must be greater than 0");
        return profileRepository.findByIdAndTemplateIdAndProjectId(profileId, templateId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Template profile not found: " + profileId));
    }

    private void ensureEditable(TemplateProfileVersion profile) {
        if (profile.getStatus() == TemplateProfileStatus.CONFIRMED) {
            throw new ConflictException("CONFIRMED profiles are immutable; create a revision instead");
        }
        Long currentUserId = templateService.currentUserId().orElse(null);
        if (profile.getOwnedByTeacherId() != null && currentUserId != null && !profile.getOwnedByTeacherId().equals(currentUserId)) {
            throw new ForbiddenException("This candidate profile is owned by another teacher");
        }
    }

    private void ensureAnalyzerCandidateImmutable(TemplateProfileVersion profile) {
        if (profile.getOrigin() == TemplateProfileOrigin.ANALYZER_CANDIDATE) {
            throw new ConflictException("ANALYZER_CANDIDATE profiles are immutable; create a revision instead");
        }
    }

    private Optional<TemplateProfileVersion> findExistingAnalyzerCandidate(Template template,
                                                                            TemplateSourceVersion source,
                                                                            TemplateAnalysisResult analysis,
                                                                            CandidateProfileRequest effectiveProfile,
                                                                            String materialBindingJson) {
        Optional<TemplateProfileVersion> existing = profileRepository
                .findByProjectIdAndTemplateIdAndSourceVersionIdAndAnalysisRunIdAndOrigin(
                        template.getProjectId(), template.getId(), source.getId(), analysis.getAnalysisRunId(),
                        TemplateProfileOrigin.ANALYZER_CANDIDATE);
        if (existing.isEmpty()) return existing;
        TemplateProfileVersion profile = existing.get();
        String expectedProfileJson = TemplateChecksum.canonicalJson(objectMapper, objectMapper.valueToTree(effectiveProfile));
        if (!expectedProfileJson.equals(profile.getProfileJson())
                || !expectedProfileJson.equals(profile.getAnalyzerProposalJson())
                || !analysis.getAnalysisRunId().equals(profile.getAnalysisRunId())
                || materialBindingJson == null
                || !materialBindingJson.equals(profile.getMaterialParseBindingJson())) {
            throw analyzerIdentityConflict();
        }
        verifyIntegrity(profile);
        return existing;
    }

    private ConflictException analyzerIdentityConflict() {
        return new ConflictException("ANALYZER_CANDIDATE already exists for this template source and analysisRunId");
    }

    private void claimOwnership(TemplateProfileVersion profile) {
        if (profile.getOwnedByTeacherId() == null) {
            templateService.currentUserId().ifPresent(profile::setOwnedByTeacherId);
            profile.setTeacherEditedAt(LocalDateTime.now());
        }
    }

    private void addReview(TemplateProfileVersion profile, TemplateProfileReviewAction action, String note) {
        TemplateProfileReview review = new TemplateProfileReview();
        review.setTemplateId(profile.getTemplateId());
        review.setProfileVersionId(profile.getId());
        review.setReviewerUserId(templateService.currentUserId().orElse(null));
        review.setAction(action);
        review.setChecksumAtAction(profile.getChecksum());
        review.setNote(note == null || note.isBlank() ? null : note.trim());
        reviewRepository.save(review);
    }

    private ProfileResponse toResponse(TemplateProfileVersion profile) {
        verifyIntegrity(profile);
        return new ProfileResponse(profile.getId(), profile.getTemplateId(), profile.getProjectId(), profile.getSourceVersionId(),
                profile.getVersionNumber(), profile.getParentProfileVersionId(), profile.getStatus(), profile.getOrigin(), profile.getParserSnapshotChecksum(),
                profile.getMaterialParseBindingJson() == null ? null : readJson(profile.getMaterialParseBindingJson()),
                profile.getMaterialParseBindingChecksum(),
                profile.getRendererStatus(), profile.getAnalyzerStatus(), profile.getAnalysisRunId(), profile.getAnalyzerInputSha256(),
                profile.getAnalyzerOutputSha256(), profile.getRenderedOutputSha256(), profile.getRenderedOutputSizeBytes(), profile.getChecksum(),
                profile.getCapabilityViewChecksum(), readJson(profile.getProfileJson()),
                profile.getAnalyzerProposalJson() == null ? null : readJson(profile.getAnalyzerProposalJson()), toCapabilityView(profile),
                profile.getEngineNativeProfileJson() == null ? null : readJson(profile.getEngineNativeProfileJson()),
                profile.getEngineNativeProfileChecksum(), profile.getOwnedByTeacherId(),
                profile.getCreatedAt(), profile.getTeacherEditedAt(), profile.getConfirmedAt(), profile.getConfirmedChecksum(),
                reviewRepository.findByProfileVersionIdOrderByCreatedAtAsc(profile.getId()).stream()
                        .map(review -> new ReviewResponse(review.getId(), review.getAction().name(), review.getChecksumAtAction(), review.getNote(), review.getCreatedAt())).toList());
    }

    private CapabilityViewResponse toCapabilityView(TemplateProfileVersion profile) {
        CandidateProfileRequest document = readDocument(profile.getCapabilityViewJson());
        return new CapabilityViewResponse(profile.getId(), profile.getVersionNumber(), profile.getCapabilityViewChecksum(), document.displayName(),
                document.pageRoles(), document.semanticLayouts(), document.imageCapability(), document.tableCapability(), document.chartCapability(),
                document.fixedBrandAreas(), document.limitations());
    }

    private CandidateProfileRequest readDocument(String json) {
        try {
            return objectMapper.readValue(json, CandidateProfileRequest.class);
        } catch (Exception exception) {
            throw new ResourceNotFoundException("Template profile document is unavailable");
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new ResourceNotFoundException("Template profile document is unavailable");
        }
    }

    private String requireCandidatePreconditions(TemplateSourceVersion source, TemplateProfileOrigin origin, String requestedSnapshotChecksum) {
        if (origin == TemplateProfileOrigin.MANUAL_DRAFT) return null;
        if (source.getParseStatus() != com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus.SUCCEEDED
                || source.getRenderStatus() != com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus.SUCCEEDED
                || source.getAnalysisStatus() != com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus.SUCCEEDED) {
            throw new ConflictException("ANALYZER_CANDIDATE requires successful Parser, Renderer and Analyzer runs");
        }
        String actual = profileContractGate.loadAndVerifySnapshot(source.getId()).getChecksum();
        if (actual == null || requestedSnapshotChecksum == null || !actual.equalsIgnoreCase(requestedSnapshotChecksum)) {
            throw new ConflictException("ANALYZER_CANDIDATE must bind the successful Parser snapshot checksum");
        }
        return actual;
    }

    private TemplateAnalysisResult requireAnalyzerResult(Long projectId, TemplateSourceVersion source,
                                                          String analysisRunId, String snapshotChecksum) {
        if (analysisRunId == null || analysisRunId.isBlank()) {
            throw new ConflictException("ANALYZER_CANDIDATE must bind analysisRunId");
        }
        TemplateAnalysisResult analysis = analysisResultRepository.findByAnalysisRunId(analysisRunId)
                .orElseThrow(() -> new ConflictException("Analyzer result is unavailable for analysisRunId"));
        if (analysis.getStatus() != com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus.SUCCEEDED
                || !Long.valueOf(projectId).equals(analysis.getProjectId())
                || !Long.valueOf(source.getId()).equals(analysis.getSourceVersionId())
                || source.getCreatedByUserId() == null
                || !source.getCreatedByUserId().equals(analysis.getOwnerUserId())
                || !source.getSha256().equalsIgnoreCase(analysis.getSourceSha256())
                || !snapshotChecksum.equalsIgnoreCase(analysis.getParserSnapshotChecksum())
                || analysis.getCandidateProfileJson() == null
                || analysis.getInputSha256() == null || analysis.getOutputSha256() == null) {
            throw new ConflictException("Analyzer result identity or checksum binding failed");
        }
        if (analysis.getRenderedSlideSetId() == null || analysis.getRenderedOutputSha256() == null
                || analysis.getRenderedOutputSizeBytes() == null || analysis.getRenderedOutputSizeBytes() <= 0) {
            throw new ConflictException("Analyzer result is missing rendered output identity");
        }
        var rendered = renderedRepository.findById(analysis.getRenderedSlideSetId())
                .orElseThrow(() -> new ConflictException("Analyzer rendered output is unavailable"));
        if (rendered.getSourceVersionId() == null
                || !rendered.getSourceVersionId().equals(source.getId())
                || rendered.getStatus() != com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus.SUCCEEDED
                || !analysis.getRenderedOutputSha256().equalsIgnoreCase(rendered.getOutputSha256())
                || !analysis.getRenderedOutputSizeBytes().equals(rendered.getOutputSizeBytes())) {
            throw new ConflictException("Analyzer rendered output identity binding failed");
        }
        return analysis;
    }

    private CandidateProfileRequest bindServerOwnedCandidate(TemplateAnalysisResult analysis,
                                                              CandidateProfileRequest requested) {
        if (analysis.getCandidateProfileJson() == null || analysis.getCandidateProfileJson().isBlank()
                || analysis.getCandidateProfileSha256() == null
                || !analysis.getCandidateProfileSha256().matches("[0-9a-fA-F]{64}")) {
            throw new ConflictException("Analyzer Candidate content binding is unavailable");
        }
        final CandidateProfileRequest serverCandidate;
        try {
            String actualHash = TemplateChecksum.canonicalSha256(objectMapper, analysis.getCandidateProfileJson());
            if (!actualHash.equalsIgnoreCase(analysis.getCandidateProfileSha256())) {
                throw new ConflictException("Analyzer Candidate content integrity check failed");
            }
            JsonNode candidateNode = objectMapper.readTree(analysis.getCandidateProfileJson());
            if (candidateNode == null || !candidateNode.isObject()) {
                throw new ConflictException("Analyzer Candidate content is not a JSON object");
            }
            serverCandidate = objectMapper.readerFor(CandidateProfileRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(candidateNode);
        } catch (ConflictException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ConflictException("Analyzer Candidate content is invalid");
        }
        Set<ConstraintViolation<CandidateProfileRequest>> violations = validator.validate(serverCandidate);
        if (!violations.isEmpty()) {
            throw new ConflictException("Analyzer Candidate content failed Profile schema validation");
        }
        String serverCanonical = TemplateChecksum.canonicalJson(objectMapper, objectMapper.valueToTree(serverCandidate));
        String requestedCanonical = TemplateChecksum.canonicalJson(objectMapper, objectMapper.valueToTree(requested));
        if (!serverCanonical.equals(requestedCanonical)) {
            throw new ConflictException("request.profile does not match the service-owned Analyzer Candidate");
        }
        return serverCandidate;
    }

    private void verifyIntegrity(TemplateProfileVersion profile) {
        if (profile.getProfileJson() == null || profile.getCapabilityViewJson() == null
                || !TemplateChecksum.sha256(profile.getProfileJson()).equalsIgnoreCase(profile.getChecksum())
                || !TemplateChecksum.sha256(profile.getCapabilityViewJson()).equalsIgnoreCase(profile.getCapabilityViewChecksum())) {
                throw new ConflictException("Template Profile integrity check failed");
        }
        if ((profile.getMaterialParseBindingJson() == null) != (profile.getMaterialParseBindingChecksum() == null)
                || (profile.getMaterialParseBindingJson() != null
                && !TemplateChecksum.sha256(profile.getMaterialParseBindingJson())
                .equalsIgnoreCase(profile.getMaterialParseBindingChecksum()))) {
            throw new ConflictException("Template Profile material parse binding integrity check failed");
        }
        if (profile.getStatus() == TemplateProfileStatus.CONFIRMED && !profile.getChecksum().equalsIgnoreCase(profile.getConfirmedChecksum())) {
            throw new ConflictException("Confirmed Template Profile checksum integrity check failed");
        }
    }

    private TemplateProfileStatus readyStatusFor(TemplateProfileOrigin origin) {
        // A validated, immutable Analyzer Candidate is usable by the system
        // without a teacher Profile Review/Confirm action. Manual drafts keep
        // the legacy CANDIDATE -> REVIEW -> CONFIRMED lifecycle.
        return origin == TemplateProfileOrigin.ANALYZER_CANDIDATE
                ? TemplateProfileStatus.READY : TemplateProfileStatus.CANDIDATE;
    }

    /**
     * Binds a READY profile to server-owned material parse facts.  Template
     * source identity remains separate in TemplateSourceVersion; this payload
     * contains only material identities re-read from the current project.
     */
    private String requireMaterialParseBinding(Long projectId, TemplateSourceVersion templateSource) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ConflictException("MATERIAL_PARSE_BINDING_PROJECT_MISSING"));
        Long ownerId = project.getOwnerUserId() != null
                ? project.getOwnerUserId()
                : templateSource.getCreatedByUserId();
        if (ownerId == null) {
            throw new ConflictException("MATERIAL_PARSE_BINDING_OWNER_MISSING");
        }
        ArrayNode bindings = objectMapper.createArrayNode();
        for (UploadedMaterial material : materialRepository.findByProjectIdOrderByCreatedAtAsc(projectId)) {
            List<MaterialPurpose> purposes = materialPurposeRepository.findByMaterialIdOrderByIdAsc(material.getId());
            if (purposes.isEmpty()) {
                continue;
            }
            if (material.getParseStatus() != com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus.SUCCEEDED) {
                throw new ConflictException("MATERIAL_PARSE_BINDING_NOT_READY: material " + material.getId());
            }
            ParseResult result = materialParseService.requireLatestBoundParseResult(projectId, material.getId());
            ObjectNode binding = objectMapper.createObjectNode();
            binding.put("projectId", projectId);
            binding.put("ownerUserId", ownerId);
            binding.put("templateSourceVersionId", templateSource.getId());
            binding.put("materialId", material.getId());
            binding.put("analysisRunId", result.getAnalysisRunId());
            binding.put("sourceVersionId", result.getSourceVersionId());
            binding.put("parserSnapshotChecksum", result.getParserSnapshotChecksum());
            binding.put("originalFileName", material.getOriginalFileName());
            binding.put("storageKey", material.getFilePath());
            if (material.getFileSize() != null) binding.put("fileSize", material.getFileSize());
            if (material.getContentType() != null) binding.put("contentType", material.getContentType());
            ArrayNode usageTypes = binding.putArray("purposeTypes");
            purposes.stream().map(MaterialPurpose::getPurposeType).filter(java.util.Objects::nonNull)
                    .map(Enum::name).forEach(usageTypes::add);
            if (usageTypes.isEmpty()) {
                throw new ConflictException("MATERIAL_PARSE_BINDING_PURPOSE_INVALID: material " + material.getId());
            }
            bindings.add(binding);
        }
        if (bindings.isEmpty()) {
            throw new ConflictException("MATERIAL_PARSE_BINDING_NOT_READY: no purpose-bound parsed material");
        }
        return TemplateChecksum.canonicalJson(objectMapper, bindings);
    }
}
