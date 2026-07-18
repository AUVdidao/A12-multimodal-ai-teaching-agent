package com.auvdidao.a12teachingagent.domain.generation.repository;

import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GeneratedArtifactRepository extends JpaRepository<GeneratedArtifact, Long> {

    List<GeneratedArtifact> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    List<GeneratedArtifact> findByProjectIdAndGenerationPlanIdOrderByCreatedAtAsc(Long projectId, Long generationPlanId);

    List<GeneratedArtifact> findByProjectIdAndVersionIdOrderByCreatedAtAsc(Long projectId, Long versionId);

    Optional<GeneratedArtifact> findByIdAndProjectId(Long id, Long projectId);

    Optional<GeneratedArtifact> findByProjectIdAndVersionIdAndArtifactType(
            Long projectId,
            Long versionId,
            ArtifactType artifactType
    );
}
