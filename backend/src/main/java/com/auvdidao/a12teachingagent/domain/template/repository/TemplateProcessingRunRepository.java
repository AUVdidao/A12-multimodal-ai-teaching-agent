package com.auvdidao.a12teachingagent.domain.template.repository;

import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingOperation;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TemplateProcessingRunRepository extends JpaRepository<TemplateProcessingRun, Long> {
    List<TemplateProcessingRun> findBySourceVersionIdOrderByCreatedAtAsc(Long sourceVersionId);
    long countBySourceVersionIdAndOperation(Long sourceVersionId, TemplateProcessingOperation operation);
}


