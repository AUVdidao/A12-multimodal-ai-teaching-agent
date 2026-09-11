package com.auvdidao.a12teachingagent.domain.template.repository;

import com.auvdidao.a12teachingagent.domain.template.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TemplateRepository extends JpaRepository<Template, Long> {
    List<Template> findByProjectIdOrderByCreatedAtAsc(Long projectId);
    Optional<Template> findByIdAndProjectId(Long id, Long projectId);
    Optional<Template> findByProjectIdAndNameIgnoreCase(Long projectId, String name);
}


