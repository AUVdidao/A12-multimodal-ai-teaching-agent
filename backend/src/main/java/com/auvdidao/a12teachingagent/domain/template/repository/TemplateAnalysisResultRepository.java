package com.auvdidao.a12teachingagent.domain.template.repository;

import com.auvdidao.a12teachingagent.domain.template.TemplateAnalysisResult;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TemplateAnalysisResultRepository extends JpaRepository<TemplateAnalysisResult, Long> {
    Optional<TemplateAnalysisResult> findByAnalysisRunId(String analysisRunId);
    Optional<TemplateAnalysisResult> findTopBySourceVersionIdAndStatusOrderByCreatedAtDescIdDesc(
            Long sourceVersionId, TemplateProcessingStatus status);
}
