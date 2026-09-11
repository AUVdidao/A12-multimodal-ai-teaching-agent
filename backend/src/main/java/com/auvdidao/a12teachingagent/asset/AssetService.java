package com.auvdidao.a12teachingagent.asset;

import com.auvdidao.a12teachingagent.agent.model.ModelProvider;
import com.auvdidao.a12teachingagent.asset.dto.AssetDtos;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.asset.ApprovedAssetManifest;
import com.auvdidao.a12teachingagent.domain.asset.AssetAuditEvent;
import com.auvdidao.a12teachingagent.domain.asset.CandidateAsset;
import com.auvdidao.a12teachingagent.domain.asset.repository.ApprovedAssetManifestRepository;
import com.auvdidao.a12teachingagent.domain.asset.repository.AssetAuditEventRepository;
import com.auvdidao.a12teachingagent.domain.asset.repository.CandidateAssetRepository;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AssetService {
    private final CandidateAssetRepository candidates;
    private final ApprovedAssetManifestRepository manifests;
    private final AssetAuditEventRepository audits;
    private final UploadedMaterialRepository materials;
    private final FileStorageService storage;
    private final ProjectAccessService access;
    private final CurrentUserService users;
    private final AssetCredentialResolver credentialResolver;

    public AssetService(CandidateAssetRepository candidates, ApprovedAssetManifestRepository manifests,
                        AssetAuditEventRepository audits, UploadedMaterialRepository materials,
                        FileStorageService storage, ProjectAccessService access, CurrentUserService users,
                        AssetCredentialResolver credentialResolver) {
        this.candidates = candidates; this.manifests = manifests; this.audits = audits; this.materials = materials;
        this.storage = storage; this.access = access; this.users = users; this.credentialResolver = credentialResolver;
    }

    @Transactional
    public AssetDtos.CandidateResponse createUpload(Long projectId, AssetDtos.CreateUploadCandidateRequest request) {
        AuthenticatedUser user = access.requireAuthenticatedTeacherAccess(projectId);
        if (request == null || request.sha256() == null || !request.sha256().matches("[0-9a-fA-F]{64}")) throw new BadRequestException("sha256 must be SHA-256 hex");
        UploadedMaterial material = materials.findByIdAndProjectId(request.materialId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Uploaded material is not in this project"));
        storage.loadAndVerify(material.getFilePath(), request.sha256());
        CandidateAsset candidate = new CandidateAsset();
        candidate.setProjectId(projectId); candidate.setOwnerUserId(user.userId());
        String assetKey = normalize(request.assetKey());
        candidate.setAssetKey(assetKey); candidate.setVersionNumber(candidates.maxVersion(projectId, assetKey) + 1);
        candidate.setRequirementKey(normalize(request.requirementKey())); candidate.setPlacementIntent(normalize(request.placementIntent()));
        candidate.setSourceType("TEACHER_UPLOAD"); candidate.setSourceReference("uploaded-material:" + material.getId());
        candidate.setStorageKey(material.getFilePath()); candidate.setOriginalFileReference(String.valueOf(material.getId()));
        candidate.setMimeType(material.getContentType() == null ? "application/octet-stream" : material.getContentType());
        candidate.setFileSize(material.getFileSize() == null ? 0L : material.getFileSize()); candidate.setSha256(request.sha256().toLowerCase());
        candidate.setProvider("UPLOAD"); candidate.setModel("teacher-upload"); candidate.setStatus(AssetStatus.CANDIDATE);
        CandidateAsset saved = candidates.save(candidate); audit(saved, user.userId(), "CREATED", null, AssetStatus.CANDIDATE.name(), null);
        return candidate(saved);
    }

    @Transactional(readOnly = true)
    public List<AssetDtos.CandidateResponse> listCandidates(Long projectId) {
        access.requireAuthenticatedTeacherAccess(projectId);
        return candidates.findByProjectIdOrderByAssetKeyAscVersionNumberDesc(projectId).stream().map(this::candidate).toList();
    }

    @Transactional
    public AssetDtos.CandidateResponse submitReview(Long projectId, Long id, AssetDtos.ReviewRequest request) {
        CandidateAsset asset = locked(projectId, id); Long actor = users.requireRole(com.auvdidao.a12teachingagent.domain.common.UserRole.TEACHER).userId();
        if (asset.getStatus() != AssetStatus.CANDIDATE) throw new ConflictException("Only a CANDIDATE asset can enter review");
        asset.setStatus(AssetStatus.IN_REVIEW); asset.setReviewerUserId(null); asset.setReviewedAt(null); asset.setReviewReason(null);
        CandidateAsset saved = candidates.save(asset); audit(saved, actor, "SUBMITTED_FOR_REVIEW", AssetStatus.CANDIDATE.name(), AssetStatus.IN_REVIEW.name(), null); return candidate(saved);
    }

    @Transactional
    public AssetDtos.ManifestResponse approve(Long projectId, Long id, AssetDtos.ReviewRequest request) {
        CandidateAsset asset = locked(projectId, id); Long actor = users.requireRole(com.auvdidao.a12teachingagent.domain.common.UserRole.TEACHER).userId();
        FileStorageService.StoredFileIdentity approvedIdentity = verifyApprovalHashAndCurrentFile(asset, request); if (asset.getStatus() != AssetStatus.IN_REVIEW) throw new ConflictException("Only an IN_REVIEW asset can be approved");
        if (manifests.existsByCandidateAssetIdAndCandidateVersion(asset.getId(), asset.getVersionNumber())) throw new ConflictException("Asset version was already reviewed");
        ApprovedAssetManifest current = manifests.findFirstByProjectIdAndAssetKeyAndStatusOrderByManifestVersionDesc(projectId, asset.getAssetKey(), ManifestStatus.ACTIVE).orElse(null);
        if (current != null) {
            current.setStatus(ManifestStatus.REVOKED);
            current.setActiveKey(null);
            current.setManifestChecksum(checksum(current));
            manifests.save(current);
        }
        asset.setStatus(AssetStatus.APPROVED); asset.setReviewerUserId(actor); asset.setReviewedAt(LocalDateTime.now()); asset.setReviewReason(reason(request)); candidates.save(asset);
        ApprovedAssetManifest manifest = new ApprovedAssetManifest();
        manifest.setProjectId(projectId); manifest.setOwnerUserId(asset.getOwnerUserId()); manifest.setCandidateAssetId(asset.getId()); manifest.setAssetKey(asset.getAssetKey());
        manifest.setManifestVersion(manifests.maxVersion(projectId, asset.getAssetKey()) + 1); manifest.setCandidateVersion(asset.getVersionNumber());
        manifest.setRequirementKey(asset.getRequirementKey()); manifest.setPlacementIntent(asset.getPlacementIntent()); manifest.setSourceType(asset.getSourceType()); manifest.setSourceReference(asset.getSourceReference());
        manifest.setStorageKey(asset.getStorageKey()); manifest.setMimeType(asset.getMimeType()); manifest.setFileSize(asset.getFileSize()); manifest.setSha256(asset.getSha256());
        manifest.setApprovedFileLastModifiedUtc(approvedIdentity.lastModifiedUtc());
        manifest.setProvider(asset.getProvider()); manifest.setModel(asset.getModel()); manifest.setApprovedBy(actor); manifest.setApprovedAt(LocalDateTime.now()); manifest.setStatus(ManifestStatus.ACTIVE); manifest.setActiveKey(asset.getAssetKey());
        if (current != null) manifest.setSupersedesManifestId(current.getId());
        manifest.setManifestChecksum(checksum(manifest)); ApprovedAssetManifest saved = manifests.save(manifest);
        audit(asset, actor, "APPROVED", AssetStatus.IN_REVIEW.name(), AssetStatus.APPROVED.name(), reason(request)); return manifest(saved);
    }

    @Transactional
    public AssetDtos.CandidateResponse reject(Long projectId, Long id, AssetDtos.ReviewRequest request) {
        CandidateAsset asset = locked(projectId, id); Long actor = users.requireRole(com.auvdidao.a12teachingagent.domain.common.UserRole.TEACHER).userId();
        expectedHash(asset, request); if (asset.getStatus() != AssetStatus.IN_REVIEW) throw new ConflictException("Only an IN_REVIEW asset can be rejected");
        asset.setStatus(AssetStatus.REJECTED); asset.setReviewerUserId(actor); asset.setReviewedAt(LocalDateTime.now()); asset.setReviewReason(reason(request)); CandidateAsset saved = candidates.save(asset);
        audit(saved, actor, "REJECTED", AssetStatus.IN_REVIEW.name(), AssetStatus.REJECTED.name(), reason(request)); return candidate(saved);
    }

    @Transactional
    public AssetDtos.ManifestResponse revoke(Long projectId, Long id, AssetDtos.ReviewRequest request) {
        CandidateAsset asset = locked(projectId, id); Long actor = users.requireRole(com.auvdidao.a12teachingagent.domain.common.UserRole.TEACHER).userId();
        expectedHash(asset, request); if (asset.getStatus() != AssetStatus.APPROVED) throw new ConflictException("Only an APPROVED asset can be revoked");
        ApprovedAssetManifest current = manifests.findByProjectIdAndCandidateAssetIdAndCandidateVersionAndStatus(projectId, asset.getId(), asset.getVersionNumber(), ManifestStatus.ACTIVE)
                .orElseThrow(() -> new ConflictException("Candidate has no current active manifest to revoke"));
        if (!asset.getAssetKey().equals(current.getAssetKey()) || !asset.getAssetKey().equals(current.getActiveKey())
                || !asset.getId().equals(current.getCandidateAssetId()) || !asset.getVersionNumber().equals(current.getCandidateVersion())) {
            throw new ConflictException("Candidate and active manifest binding is stale");
        }
        current.setStatus(ManifestStatus.REVOKED); current.setActiveKey(null); current.setManifestChecksum(checksum(current)); manifests.save(current);
        asset.setStatus(AssetStatus.REVOKED); asset.setReviewerUserId(actor); asset.setReviewedAt(LocalDateTime.now()); asset.setReviewReason(reason(request)); candidates.save(asset);
        ApprovedAssetManifest revoked = copyManifest(current); revoked.setManifestVersion(manifests.maxVersion(projectId, asset.getAssetKey()) + 1); revoked.setStatus(ManifestStatus.REVOKED); revoked.setApprovedBy(actor); revoked.setApprovedAt(LocalDateTime.now()); revoked.setSupersedesManifestId(current.getId()); revoked.setManifestChecksum(checksum(revoked));
        ApprovedAssetManifest saved = manifests.save(revoked); audit(asset, actor, "REVOKED", AssetStatus.APPROVED.name(), AssetStatus.REVOKED.name(), reason(request)); return manifest(saved);
    }

    @Transactional(readOnly = true)
    public List<AssetDtos.ManifestResponse> listManifest(Long projectId) { access.requireAuthenticatedTeacherAccess(projectId); return manifests.findByProjectIdOrderByAssetKeyAscManifestVersionDesc(projectId).stream().map(this::manifest).toList(); }

    /** Read-only gate used by the Specification boundary. It never accepts candidate or credential data. */
    @Transactional(readOnly = true)
    public void requireApprovedForSpecification(Long projectId, String assetKey, String source, String placementIntent) {
        access.requireAuthenticatedTeacherAccess(projectId);
        ApprovedAssetManifest manifest = manifests.findFirstByProjectIdAndAssetKeyAndStatusOrderByManifestVersionDesc(projectId, assetKey, ManifestStatus.ACTIVE)
                .orElseThrow(() -> new ConflictException("SPECIFICATION_ASSET_NOT_APPROVED"));
        if (!assetKey.equals(manifest.getActiveKey()) || !manifest.getPlacementIntent().equals(placementIntent) || (!manifest.getSourceReference().equals(source) && !manifest.getSourceType().equals(source)) || !checksum(manifest).equalsIgnoreCase(manifest.getManifestChecksum())) {
            throw new ConflictException("SPECIFICATION_ASSET_BINDING_STALE");
        }
    }

    /**
     * Strict, non-secret snapshot gate for the independent PPT Engine adapter.
     * Candidate identity and active Manifest identity are both checked; callers never receive credentials.
     */
    @Transactional(readOnly = true)
    public EngineManifest requireApprovedManifestForEngine(Long projectId, String assetKey, String source, String placementIntent) {
        access.requireAuthenticatedTeacherAccess(projectId);
        ApprovedAssetManifest manifest = manifests.findFirstByProjectIdAndAssetKeyAndStatusOrderByManifestVersionDesc(projectId, assetKey, ManifestStatus.ACTIVE)
                .orElseThrow(() -> new ConflictException("ENGINE_ASSET_NOT_ACTIVE"));
        CandidateAsset candidate = candidates.findByIdAndProjectId(manifest.getCandidateAssetId(), projectId)
                .orElseThrow(() -> new ConflictException("ENGINE_ASSET_CANDIDATE_MISSING"));
        if (candidate.getStatus() != AssetStatus.APPROVED
                || !manifest.getOwnerUserId().equals(candidate.getOwnerUserId())
                || !manifest.getAssetKey().equals(candidate.getAssetKey())
                || !manifest.getCandidateVersion().equals(candidate.getVersionNumber())
                || !manifest.getActiveKey().equals(assetKey)
                || !manifest.getPlacementIntent().equals(placementIntent)
                || (!manifest.getSourceReference().equals(source) && !manifest.getSourceType().equals(source))
                || !checksum(manifest).equalsIgnoreCase(manifest.getManifestChecksum())) {
            throw new ConflictException("ENGINE_ASSET_BINDING_STALE");
        }
        return new EngineManifest(manifest.getId(), manifest.getProjectId(), manifest.getOwnerUserId(), manifest.getCandidateAssetId(),
                manifest.getAssetKey(), manifest.getManifestVersion(), manifest.getCandidateVersion(), manifest.getRequirementKey(),
                manifest.getPlacementIntent(), manifest.getSourceType(), manifest.getSourceReference(), manifest.getStorageKey(),
                manifest.getMimeType(), manifest.getFileSize(), manifest.getSha256(), manifest.getProvider(), manifest.getModel(),
                manifest.getStatus().name(), manifest.getManifestChecksum(), manifest.getApprovedFileLastModifiedUtc());
    }

    public record EngineManifest(Long id, Long projectId, Long ownerUserId, Long candidateAssetId, String assetKey,
                                 Integer manifestVersion, Integer candidateVersion, String requirementKey,
                                 String placementIntent, String sourceType, String sourceReference, String storageKey,
                                 String mimeType, Long fileSize, String sha256, String provider, String model,
                                 String status, String manifestChecksum, String approvedFileLastModifiedUtc) {
        public EngineManifest(Long id, Long projectId, Long ownerUserId, Long candidateAssetId, String assetKey,
                              Integer manifestVersion, Integer candidateVersion, String requirementKey,
                              String placementIntent, String sourceType, String sourceReference, String storageKey,
                              String mimeType, Long fileSize, String sha256, String provider, String model,
                              String status, String manifestChecksum) {
            this(id, projectId, ownerUserId, candidateAssetId, assetKey, manifestVersion, candidateVersion,
                    requirementKey, placementIntent, sourceType, sourceReference, storageKey, mimeType, fileSize,
                    sha256, provider, model, status, manifestChecksum, null);
        }
    }

    /** Explicitly fail closed until a real provider transport is installed; credential resolution is still exercised. */
    @Transactional
    public void generateWithProvider(Long projectId, AssetDtos.GenerateCandidateRequest request) {
        AuthenticatedUser user = access.requireAuthenticatedTeacherAccess(projectId);
        validateProviderModel(request.provider(), request.model());
        credentialResolver.require(user.userId(), request.provider());
        throw new ConflictException("ASSET_PROVIDER_TRANSPORT_NOT_CONFIGURED");
    }

    private void validateProviderModel(ModelProvider provider, String model) {
        if (provider == null || model == null || model.isBlank()) throw new BadRequestException("Provider and model are required");
        String normalized = model.trim().toLowerCase(java.util.Locale.ROOT);
        boolean valid = switch (provider) {
            case OPENAI_COMPATIBLE -> false;
            case KIMI -> normalized.startsWith("kimi") || normalized.startsWith("moonshot");
            case DEEPSEEK -> normalized.startsWith("deepseek");
            case GLM -> normalized.startsWith("glm") || normalized.startsWith("chatglm");
            case MOCK -> false;
        };
        if (!valid) throw new ConflictException("ASSET_PROVIDER_MODEL_MISMATCH");
    }

    private CandidateAsset locked(Long projectId, Long id) { access.requireAuthenticatedTeacherAccess(projectId); return candidates.findByIdAndProjectIdForUpdate(id, projectId).orElseThrow(() -> new ResourceNotFoundException("Candidate asset not found")); }
    private void expectedHash(CandidateAsset asset, AssetDtos.ReviewRequest request) { if (request != null && request.expectedSha256() != null && !asset.getSha256().equalsIgnoreCase(request.expectedSha256())) throw new ConflictException("ASSET_HASH_STALE"); }
    private FileStorageService.StoredFileIdentity verifyApprovalHashAndCurrentFile(CandidateAsset asset, AssetDtos.ReviewRequest request) {
        if (request == null || request.expectedSha256() == null || !request.expectedSha256().matches("[0-9a-fA-F]{64}")) throw new BadRequestException("Approval requires expectedSha256");
        if (!asset.getSha256().equalsIgnoreCase(request.expectedSha256())) throw new ConflictException("ASSET_HASH_STALE");
        storage.loadAndVerify(asset.getStorageKey(), asset.getSha256());
        FileStorageService.StoredFileIdentity identity = storage.identity(asset.getStorageKey());
        if (identity == null || identity.size() != asset.getFileSize()
                || !identity.sha256().equalsIgnoreCase(asset.getSha256())
                || identity.lastModifiedUtc() == null || identity.lastModifiedUtc().isBlank()) {
            throw new ConflictException("ASSET_APPROVAL_IDENTITY_UNAVAILABLE");
        }
        return identity;
    }
    private String reason(AssetDtos.ReviewRequest request) { return request == null ? null : request.reason(); }
    private String normalize(String value) { if (value == null || value.isBlank() || value.length() > 500) throw new BadRequestException("Asset binding value is invalid"); return value.trim(); }
    private CandidateAsset copyCandidate(CandidateAsset a) { return a; }
    private ApprovedAssetManifest copyManifest(ApprovedAssetManifest source) { ApprovedAssetManifest target = new ApprovedAssetManifest(); target.setProjectId(source.getProjectId()); target.setOwnerUserId(source.getOwnerUserId()); target.setCandidateAssetId(source.getCandidateAssetId()); target.setAssetKey(source.getAssetKey()); target.setCandidateVersion(source.getCandidateVersion()); target.setRequirementKey(source.getRequirementKey()); target.setPlacementIntent(source.getPlacementIntent()); target.setSourceType(source.getSourceType()); target.setSourceReference(source.getSourceReference()); target.setStorageKey(source.getStorageKey()); target.setMimeType(source.getMimeType()); target.setFileSize(source.getFileSize()); target.setSha256(source.getSha256()); target.setApprovedFileLastModifiedUtc(source.getApprovedFileLastModifiedUtc()); target.setProvider(source.getProvider()); target.setModel(source.getModel()); target.setActiveKey(null); return target; }
    private void audit(CandidateAsset asset, Long actor, String action, String from, String to, String reason) { AssetAuditEvent e = new AssetAuditEvent(); e.setProjectId(asset.getProjectId()); e.setAssetId(asset.getId()); e.setActorUserId(actor); e.setAction(action); e.setFromStatus(from); e.setToStatus(to); e.setProvider(asset.getProvider()); e.setModel(asset.getModel()); e.setSha256(asset.getSha256()); e.setReason(reason); e.setEventChecksum(checksum(e)); audits.save(e); }
    private String checksum(ApprovedAssetManifest m) { return sha256(String.join("|", String.valueOf(m.getProjectId()), String.valueOf(m.getOwnerUserId()), m.getAssetKey(), String.valueOf(m.getActiveKey()), String.valueOf(m.getManifestVersion()), String.valueOf(m.getCandidateVersion()), m.getRequirementKey(), m.getPlacementIntent(), m.getSourceReference(), m.getStorageKey(), String.valueOf(m.getFileSize()), m.getSha256(), String.valueOf(m.getApprovedFileLastModifiedUtc()), m.getProvider(), m.getModel(), String.valueOf(m.getStatus()))); }
    private String checksum(AssetAuditEvent e) { return sha256(String.join("|", String.valueOf(e.getProjectId()), String.valueOf(e.getAssetId()), String.valueOf(e.getActorUserId()), e.getAction(), String.valueOf(e.getFromStatus()), e.getToStatus(), e.getProvider(), e.getModel(), e.getSha256(), String.valueOf(e.getReason()))); }
    private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException x) { throw new IllegalStateException(x); } }
    private AssetDtos.CandidateResponse candidate(CandidateAsset a) { return new AssetDtos.CandidateResponse(a.getId(), a.getProjectId(), a.getAssetKey(), a.getVersionNumber(), a.getRequirementKey(), a.getPlacementIntent(), a.getSourceType(), a.getSourceReference(), a.getMimeType(), a.getFileSize(), a.getSha256(), a.getProvider(), a.getModel(), a.getStatus(), a.getReviewerUserId(), a.getReviewedAt(), a.getReviewReason()); }
    private AssetDtos.ManifestResponse manifest(ApprovedAssetManifest m) { return new AssetDtos.ManifestResponse(m.getId(), m.getProjectId(), m.getAssetKey(), m.getManifestVersion(), m.getCandidateVersion(), m.getRequirementKey(), m.getPlacementIntent(), m.getSourceType(), m.getSourceReference(), m.getMimeType(), m.getFileSize(), m.getSha256(), m.getProvider(), m.getModel(), m.getStatus(), m.getApprovedBy(), m.getApprovedAt(), m.getManifestChecksum()); }
}
