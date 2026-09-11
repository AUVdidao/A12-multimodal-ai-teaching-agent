package com.auvdidao.a12teachingagent.asset;

import com.auvdidao.a12teachingagent.asset.dto.AssetDtos;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.StorageIntegrityException;
import com.auvdidao.a12teachingagent.domain.asset.ApprovedAssetManifest;
import com.auvdidao.a12teachingagent.domain.asset.CandidateAsset;
import com.auvdidao.a12teachingagent.domain.asset.repository.ApprovedAssetManifestRepository;
import com.auvdidao.a12teachingagent.domain.asset.repository.AssetAuditEventRepository;
import com.auvdidao.a12teachingagent.domain.asset.repository.CandidateAssetRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetServiceTest {
    @Test
    void forgedHashCannotReachApprovalOrManifest() {
        CandidateAsset candidate = candidate(11L, AssetStatus.IN_REVIEW, "a".repeat(64));
        CandidateAssetRepository candidates = mock(CandidateAssetRepository.class);
        when(candidates.findByIdAndProjectIdForUpdate(11L, 7L)).thenReturn(Optional.of(candidate));
        AssetService service = service(candidates, mock(ApprovedAssetManifestRepository.class), mock(AssetAuditEventRepository.class));
        assertThrows(ConflictException.class, () -> service.approve(7L, 11L, new AssetDtos.ReviewRequest("approve", "b".repeat(64))));
        verify(candidates, never()).save(any());
    }

    @Test
    void duplicateApprovalIsRejectedWithoutSecondManifest() {
        CandidateAsset candidate = candidate(11L, AssetStatus.IN_REVIEW, "a".repeat(64));
        CandidateAssetRepository candidates = mock(CandidateAssetRepository.class);
        ApprovedAssetManifestRepository manifests = mock(ApprovedAssetManifestRepository.class);
        when(candidates.findByIdAndProjectIdForUpdate(11L, 7L)).thenReturn(Optional.of(candidate));
        when(manifests.existsByCandidateAssetIdAndCandidateVersion(11L, 1)).thenReturn(true);
        AssetService service = service(candidates, manifests, mock(AssetAuditEventRepository.class));
        assertThrows(ConflictException.class, () -> service.approve(7L, 11L, new AssetDtos.ReviewRequest("duplicate", candidate.getSha256())));
        verify(manifests, never()).save(any());
    }

    @Test
    void approvalRequiresExpectedHashAndDoesNotTouchStateWhenMissing() {
        CandidateAsset candidate = candidate(11L, AssetStatus.IN_REVIEW, "a".repeat(64));
        CandidateAssetRepository candidates = mock(CandidateAssetRepository.class);
        when(candidates.findByIdAndProjectIdForUpdate(11L, 7L)).thenReturn(Optional.of(candidate));
        FileStorageService storage = mock(FileStorageService.class);
        AssetService service = service(candidates, mock(ApprovedAssetManifestRepository.class), mock(AssetAuditEventRepository.class), storage);

        assertThrows(BadRequestException.class, () -> service.approve(7L, 11L, null));
        verify(storage, never()).loadAndVerify(anyString(), anyString());
        verify(candidates, never()).save(any());
    }

    @Test
    void approvalRechecksCurrentStorageBytesBeforeAnyMutation() {
        CandidateAsset candidate = candidate(11L, AssetStatus.IN_REVIEW, "a".repeat(64));
        CandidateAssetRepository candidates = mock(CandidateAssetRepository.class);
        when(candidates.findByIdAndProjectIdForUpdate(11L, 7L)).thenReturn(Optional.of(candidate));
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.loadAndVerify(candidate.getStorageKey(), candidate.getSha256()))
                .thenThrow(new StorageIntegrityException("replaced"));
        AssetService service = service(candidates, mock(ApprovedAssetManifestRepository.class), mock(AssetAuditEventRepository.class), storage);

        assertThrows(StorageIntegrityException.class, () -> service.approve(7L, 11L,
                new AssetDtos.ReviewRequest("approve", candidate.getSha256())));
        verify(candidates, never()).save(any());
    }

    @Test
    void revokeDeactivatesCurrentActiveManifestBeforeCreatingRevokedHistory() {
        CandidateAsset candidate = candidate(11L, AssetStatus.APPROVED, "a".repeat(64));
        CandidateAssetRepository candidates = mock(CandidateAssetRepository.class);
        ApprovedAssetManifestRepository manifests = mock(ApprovedAssetManifestRepository.class);
        ApprovedAssetManifest current = manifest(21L, ManifestStatus.ACTIVE);
        when(candidates.findByIdAndProjectIdForUpdate(11L, 7L)).thenReturn(Optional.of(candidate));
        when(manifests.findByProjectIdAndCandidateAssetIdAndCandidateVersionAndStatus(7L, 11L, 1, ManifestStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(manifests.maxVersion(7L, "hero")).thenReturn(1);
        AssetService service = service(candidates, manifests, mock(AssetAuditEventRepository.class));

        service.revoke(7L, 11L, new AssetDtos.ReviewRequest("withdraw", candidate.getSha256()));

        assertThat(current.getStatus()).isEqualTo(ManifestStatus.REVOKED);
        assertThat(current.getActiveKey()).isNull();
        ArgumentCaptor<ApprovedAssetManifest> captor = ArgumentCaptor.forClass(ApprovedAssetManifest.class);
        verify(manifests, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(ManifestStatus.REVOKED);
        assertThat(captor.getAllValues().get(1).getActiveKey()).isNull();
    }

    @Test
    void supersededCandidateCannotRevokeNewerCandidateActiveManifest() {
        CandidateAsset oldCandidate = candidate(11L, AssetStatus.APPROVED, "a".repeat(64));
        CandidateAsset newCandidate = candidate(12L, AssetStatus.APPROVED, "b".repeat(64));
        newCandidate.setVersionNumber(2);
        ApprovedAssetManifest newerActive = manifest(22L, ManifestStatus.ACTIVE);
        newerActive.setCandidateAssetId(12L); newerActive.setCandidateVersion(2); newerActive.setSha256("b".repeat(64));
        CandidateAssetRepository candidates = mock(CandidateAssetRepository.class);
        ApprovedAssetManifestRepository manifests = mock(ApprovedAssetManifestRepository.class);
        when(candidates.findByIdAndProjectIdForUpdate(11L, 7L)).thenReturn(Optional.of(oldCandidate));
        when(manifests.findByProjectIdAndCandidateAssetIdAndCandidateVersionAndStatus(7L, 11L, 1, ManifestStatus.ACTIVE)).thenReturn(Optional.empty());
        AssetService service = service(candidates, manifests, mock(AssetAuditEventRepository.class));

        assertThrows(ConflictException.class, () -> service.revoke(7L, 11L,
                new AssetDtos.ReviewRequest("old candidate", oldCandidate.getSha256())));

        assertThat(oldCandidate.getStatus()).isEqualTo(AssetStatus.APPROVED);
        assertThat(newCandidate.getStatus()).isEqualTo(AssetStatus.APPROVED);
        assertThat(newerActive.getStatus()).isEqualTo(ManifestStatus.ACTIVE);
        assertThat(newerActive.getActiveKey()).isEqualTo("hero");
        verify(candidates, never()).save(any());
        verify(manifests, never()).save(any());
        verify(manifests, never()).findFirstByProjectIdAndAssetKeyAndStatusOrderByManifestVersionDesc(anyLong(), anyString(), any());
    }

    @Test
    void revokeRejectsManifestReturnedWithMismatchedCandidateBinding() {
        CandidateAsset candidate = candidate(11L, AssetStatus.APPROVED, "a".repeat(64));
        ApprovedAssetManifest wrongManifest = manifest(21L, ManifestStatus.ACTIVE);
        wrongManifest.setCandidateAssetId(12L);
        CandidateAssetRepository candidates = mock(CandidateAssetRepository.class);
        ApprovedAssetManifestRepository manifests = mock(ApprovedAssetManifestRepository.class);
        when(candidates.findByIdAndProjectIdForUpdate(11L, 7L)).thenReturn(Optional.of(candidate));
        when(manifests.findByProjectIdAndCandidateAssetIdAndCandidateVersionAndStatus(7L, 11L, 1, ManifestStatus.ACTIVE)).thenReturn(Optional.of(wrongManifest));
        AssetService service = service(candidates, manifests, mock(AssetAuditEventRepository.class));

        assertThrows(ConflictException.class, () -> service.revoke(7L, 11L,
                new AssetDtos.ReviewRequest("forged binding", candidate.getSha256())));
        verify(candidates, never()).save(any());
        verify(manifests, never()).save(any());
    }

    @Test
    void approvingNewVersionReplacesOldActiveAndKeepsOneActiveMarker() {
        CandidateAsset candidate = candidate(11L, AssetStatus.IN_REVIEW, "a".repeat(64));
        CandidateAssetRepository candidates = mock(CandidateAssetRepository.class);
        ApprovedAssetManifestRepository manifests = mock(ApprovedAssetManifestRepository.class);
        ApprovedAssetManifest current = manifest(21L, ManifestStatus.ACTIVE);
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.identity(candidate.getStorageKey())).thenReturn(
                new FileStorageService.StoredFileIdentity(candidate.getFileSize(), candidate.getSha256(), "2026-08-28T02:00:00Z"));
        when(candidates.findByIdAndProjectIdForUpdate(11L, 7L)).thenReturn(Optional.of(candidate));
        when(manifests.findFirstByProjectIdAndAssetKeyAndStatusOrderByManifestVersionDesc(7L, "hero", ManifestStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(manifests.maxVersion(7L, "hero")).thenReturn(1);
        AssetService service = service(candidates, manifests, mock(AssetAuditEventRepository.class), storage);

        service.approve(7L, 11L, new AssetDtos.ReviewRequest("replace", candidate.getSha256()));

        assertThat(current.getStatus()).isEqualTo(ManifestStatus.REVOKED);
        assertThat(current.getActiveKey()).isNull();
        ArgumentCaptor<ApprovedAssetManifest> captor = ArgumentCaptor.forClass(ApprovedAssetManifest.class);
        verify(manifests, times(2)).save(captor.capture());
        ApprovedAssetManifest replacement = captor.getAllValues().get(1);
        assertThat(replacement.getStatus()).isEqualTo(ManifestStatus.ACTIVE);
        assertThat(replacement.getActiveKey()).isEqualTo("hero");
        assertThat(replacement.getSupersedesManifestId()).isEqualTo(21L);
    }

    private AssetService service(CandidateAssetRepository candidates, ApprovedAssetManifestRepository manifests, AssetAuditEventRepository audits) {
        return service(candidates, manifests, audits, mock(FileStorageService.class));
    }

    private AssetService service(CandidateAssetRepository candidates, ApprovedAssetManifestRepository manifests, AssetAuditEventRepository audits, FileStorageService storage) {
        when(manifests.save(any(ApprovedAssetManifest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ProjectAccessService access = mock(ProjectAccessService.class);
        when(access.requireAuthenticatedTeacherAccess(7L)).thenReturn(new AuthenticatedUser(1L, 9L, "teacher", "Teacher", com.auvdidao.a12teachingagent.domain.common.UserRole.TEACHER));
        CurrentUserService users = mock(CurrentUserService.class);
        when(users.requireRole(com.auvdidao.a12teachingagent.domain.common.UserRole.TEACHER)).thenReturn(new AuthenticatedUser(1L, 9L, "teacher", "Teacher", com.auvdidao.a12teachingagent.domain.common.UserRole.TEACHER));
        return new AssetService(candidates, manifests, audits, mock(UploadedMaterialRepository.class), storage, access, users, mock(AssetCredentialResolver.class));
    }

    private CandidateAsset candidate(Long id, AssetStatus status, String hash) {
        CandidateAsset result = new CandidateAsset(); result.setId(id); result.setProjectId(7L); result.setOwnerUserId(9L); result.setAssetKey("hero"); result.setVersionNumber(1);
        result.setRequirementKey("hero-image"); result.setPlacementIntent("IMAGE"); result.setSourceType("TEACHER_UPLOAD"); result.setSourceReference("uploaded-material:1"); result.setStorageKey("7/file.png"); result.setOriginalFileReference("1"); result.setMimeType("image/png"); result.setFileSize(10L); result.setSha256(hash); result.setProvider("UPLOAD"); result.setModel("teacher-upload"); result.setStatus(status); return result;
    }

    private ApprovedAssetManifest manifest(Long id, ManifestStatus status) {
        ApprovedAssetManifest result = new ApprovedAssetManifest(); result.setId(id); result.setProjectId(7L); result.setOwnerUserId(9L);
        result.setCandidateAssetId(11L); result.setAssetKey("hero"); result.setActiveKey(status == ManifestStatus.ACTIVE ? "hero" : null);
        result.setManifestVersion(1); result.setCandidateVersion(1); result.setRequirementKey("hero-image"); result.setPlacementIntent("IMAGE");
        result.setSourceType("TEACHER_UPLOAD"); result.setSourceReference("uploaded-material:1"); result.setStorageKey("7/file.png");
        result.setMimeType("image/png"); result.setFileSize(10L); result.setSha256("a".repeat(64)); result.setProvider("UPLOAD");
        result.setModel("teacher-upload"); result.setApprovedBy(1L); result.setApprovedAt(java.time.LocalDateTime.now()); result.setStatus(status);
        result.setManifestChecksum("0".repeat(64)); return result;
    }
}
