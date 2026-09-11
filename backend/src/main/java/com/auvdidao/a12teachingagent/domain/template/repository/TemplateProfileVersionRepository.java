package com.auvdidao.a12teachingagent.domain.template.repository;

import com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TemplateProfileVersionRepository extends JpaRepository<TemplateProfileVersion, Long> {
    List<TemplateProfileVersion> findByTemplateIdOrderByVersionNumberDesc(Long templateId);
    Optional<TemplateProfileVersion> findByIdAndTemplateIdAndProjectId(Long id, Long templateId, Long projectId);
    Optional<TemplateProfileVersion> findByIdAndProjectId(Long id, Long projectId);
    Optional<TemplateProfileVersion> findTopByTemplateIdAndStatusOrderByVersionNumberDesc(Long templateId, TemplateProfileStatus status);
    Optional<TemplateProfileVersion> findTopByTemplateIdOrderByVersionNumberDesc(Long templateId);
    Optional<TemplateProfileVersion> findByProjectIdAndTemplateIdAndSourceVersionIdAndAnalysisRunIdAndOrigin(
            Long projectId, Long templateId, Long sourceVersionId, String analysisRunId, com.auvdidao.a12teachingagent.domain.template.TemplateProfileOrigin origin);

    @Query("""
            select profile from TemplateProfileVersion profile, Project project
            where profile.projectId = project.id
              and project.ownerUserId = :ownerUserId
              and profile.sourceVersionId = :sourceVersionId
              and profile.status = :status
            order by profile.versionNumber desc, profile.id desc
            """)
    List<TemplateProfileVersion> findReadyByOwnerAndSource(
            @Param("ownerUserId") Long ownerUserId,
            @Param("sourceVersionId") Long sourceVersionId,
            @Param("status") TemplateProfileStatus status
    );
}
