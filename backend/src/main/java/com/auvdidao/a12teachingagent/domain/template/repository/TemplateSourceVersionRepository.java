package com.auvdidao.a12teachingagent.domain.template.repository;

import com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TemplateSourceVersionRepository extends JpaRepository<TemplateSourceVersion, Long> {
    List<TemplateSourceVersion> findByTemplateIdOrderByVersionNumberDesc(Long templateId);
    Optional<TemplateSourceVersion> findByIdAndTemplateIdAndProjectId(Long id, Long templateId, Long projectId);
    Optional<TemplateSourceVersion> findByTemplateIdAndSha256(Long templateId, String sha256);
    Optional<TemplateSourceVersion> findTopByTemplateIdOrderByVersionNumberDesc(Long templateId);

    @Query("""
            select source from TemplateSourceVersion source, Template template, Project project
            where source.templateId = template.id
              and source.projectId = project.id
              and project.ownerUserId = :ownerUserId
              and lower(source.sha256) = lower(:sha256)
            order by source.id desc
            """)
    List<TemplateSourceVersion> findByOwnerUserIdAndSha256(
            @Param("ownerUserId") Long ownerUserId,
            @Param("sha256") String sha256
    );
}

