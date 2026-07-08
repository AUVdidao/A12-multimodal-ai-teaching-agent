package com.auvdidao.a12teachingagent.domain.material.repository;

import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UploadedMaterialRepository extends JpaRepository<UploadedMaterial, Long> {

    List<UploadedMaterial> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
