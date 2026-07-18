package com.auvdidao.a12teachingagent.domain.material.repository;

import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialPurposeRepository extends JpaRepository<MaterialPurpose, Long> {

    List<MaterialPurpose> findByMaterialIdOrderByIdAsc(Long materialId);

    void deleteByMaterialId(Long materialId);

    boolean existsByMaterialIdAndPurposeType(Long materialId, PurposeType purposeType);
}
