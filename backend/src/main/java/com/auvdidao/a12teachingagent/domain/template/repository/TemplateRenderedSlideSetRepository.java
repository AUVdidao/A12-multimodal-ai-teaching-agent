package com.auvdidao.a12teachingagent.domain.template.repository;

import com.auvdidao.a12teachingagent.domain.template.TemplateRenderedSlideSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TemplateRenderedSlideSetRepository extends JpaRepository<TemplateRenderedSlideSet, Long> {
    Optional<TemplateRenderedSlideSet> findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(Long sourceVersionId);
}


