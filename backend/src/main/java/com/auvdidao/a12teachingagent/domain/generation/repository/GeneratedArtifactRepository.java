package com.auvdidao.a12teachingagent.domain.generation.repository;

import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GeneratedArtifactRepository extends JpaRepository<GeneratedArtifact, Long> {

    List<GeneratedArtifact> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    List<GeneratedArtifact> findByProjectIdAndGenerationPlanIdOrderByCreatedAtAsc(Long projectId, Long generationPlanId);

    Optional<GeneratedArtifact> findByIdAndProjectId(Long id, Long projectId);
}
