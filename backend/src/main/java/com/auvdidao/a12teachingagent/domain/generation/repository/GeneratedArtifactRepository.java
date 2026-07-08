package com.auvdidao.a12teachingagent.domain.generation.repository;

import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratedArtifactRepository extends JpaRepository<GeneratedArtifact, Long> {

    List<GeneratedArtifact> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
