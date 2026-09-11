package com.auvdidao.a12teachingagent.domain.asset.repository;

import com.auvdidao.a12teachingagent.asset.ManifestStatus;
import com.auvdidao.a12teachingagent.domain.asset.ApprovedAssetManifest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface ApprovedAssetManifestRepository extends JpaRepository<ApprovedAssetManifest, Long> {
    List<ApprovedAssetManifest> findByProjectIdOrderByAssetKeyAscManifestVersionDesc(Long projectId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ApprovedAssetManifest> findFirstByProjectIdAndAssetKeyAndStatusOrderByManifestVersionDesc(Long projectId, String assetKey, ManifestStatus status);
    List<ApprovedAssetManifest> findByProjectIdAndAssetKeyAndStatus(Long projectId, String assetKey, ManifestStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ApprovedAssetManifest> findByProjectIdAndCandidateAssetIdAndCandidateVersionAndStatus(Long projectId, Long candidateId, Integer candidateVersion, ManifestStatus status);
    boolean existsByCandidateAssetIdAndCandidateVersion(Long candidateId, Integer candidateVersion);
    @org.springframework.data.jpa.repository.Query("select coalesce(max(m.manifestVersion), 0) from ApprovedAssetManifest m where m.projectId = :projectId and m.assetKey = :assetKey")
    int maxVersion(@org.springframework.data.repository.query.Param("projectId") Long projectId, @org.springframework.data.repository.query.Param("assetKey") String assetKey);
}
