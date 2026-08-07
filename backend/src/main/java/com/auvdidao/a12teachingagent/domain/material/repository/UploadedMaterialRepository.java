package com.auvdidao.a12teachingagent.domain.material.repository;

import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UploadedMaterialRepository extends JpaRepository<UploadedMaterial, Long> {

    List<UploadedMaterial> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    Optional<UploadedMaterial> findByIdAndProjectId(Long id, Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select material
            from UploadedMaterial material
            where material.id = :materialId
              and material.projectId = :projectId
            """)
    Optional<UploadedMaterial> findByIdAndProjectIdForUpdate(
            @Param("materialId") Long materialId,
            @Param("projectId") Long projectId
    );

    Optional<UploadedMaterial> findByProjectIdAndOriginalFileNameIgnoreCase(Long projectId, String originalFileName);
}
