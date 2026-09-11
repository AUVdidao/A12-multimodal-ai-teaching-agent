package com.auvdidao.a12teachingagent.domain.template.repository;

import com.auvdidao.a12teachingagent.domain.template.TemplateStructuralSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TemplateStructuralSnapshotRepository extends JpaRepository<TemplateStructuralSnapshot, Long> {
    Optional<TemplateStructuralSnapshot> findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(Long sourceVersionId);
}


