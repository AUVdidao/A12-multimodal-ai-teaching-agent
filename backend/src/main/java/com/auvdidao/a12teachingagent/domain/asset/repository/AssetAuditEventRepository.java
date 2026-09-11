package com.auvdidao.a12teachingagent.domain.asset.repository;

import com.auvdidao.a12teachingagent.domain.asset.AssetAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AssetAuditEventRepository extends JpaRepository<AssetAuditEvent, Long> {
    List<AssetAuditEvent> findByProjectIdAndAssetIdOrderByCreatedAtAsc(Long projectId, Long assetId);
}
