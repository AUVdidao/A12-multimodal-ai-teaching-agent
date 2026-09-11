package com.auvdidao.a12teachingagent.domain.asset.repository;

import com.auvdidao.a12teachingagent.asset.AssetStatus;
import com.auvdidao.a12teachingagent.domain.asset.CandidateAsset;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface CandidateAssetRepository extends JpaRepository<CandidateAsset, Long> {
    List<CandidateAsset> findByProjectIdOrderByAssetKeyAscVersionNumberDesc(Long projectId);
    Optional<CandidateAsset> findByIdAndProjectId(Long id, Long projectId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CandidateAsset a where a.id = :id and a.projectId = :projectId")
    Optional<CandidateAsset> findByIdAndProjectIdForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);
    @Query("select coalesce(max(a.versionNumber), 0) from CandidateAsset a where a.projectId = :projectId and a.assetKey = :assetKey")
    int maxVersion(@Param("projectId") Long projectId, @Param("assetKey") String assetKey);
}
