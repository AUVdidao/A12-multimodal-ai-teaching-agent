package com.auvdidao.a12teachingagent.domain.generation.repository;

import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArtifactVersionRepository extends JpaRepository<ArtifactVersion, Long> {

    List<ArtifactVersion> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    Optional<ArtifactVersion> findFirstByProjectIdAndGenerationPlanIdOrderByVersionNumberAsc(
            Long projectId,
            Long generationPlanId
    );
}
