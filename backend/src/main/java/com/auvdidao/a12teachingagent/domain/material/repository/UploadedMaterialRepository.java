package com.auvdidao.a12teachingagent.domain.material.repository;

import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UploadedMaterialRepository extends JpaRepository<UploadedMaterial, Long> {

    List<UploadedMaterial> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    Optional<UploadedMaterial> findByIdAndProjectId(Long id, Long projectId);

    Optional<UploadedMaterial> findByProjectIdAndOriginalFileNameIgnoreCase(Long projectId, String originalFileName);
}
