package com.auvdidao.a12teachingagent.domain.material.repository;

import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialPurposeRepository extends JpaRepository<MaterialPurpose, Long> {

    List<MaterialPurpose> findByMaterialId(Long materialId);
}
