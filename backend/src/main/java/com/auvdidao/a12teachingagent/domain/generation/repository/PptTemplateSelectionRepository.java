package com.auvdidao.a12teachingagent.domain.generation.repository;

import com.auvdidao.a12teachingagent.domain.generation.PptTemplateSelection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PptTemplateSelectionRepository extends JpaRepository<PptTemplateSelection, Long> {
    Optional<PptTemplateSelection> findFirstByProjectIdOrderByUpdatedAtDescIdDesc(Long projectId);
}
