package com.auvdidao.a12teachingagent.domain.generation.repository;

import com.auvdidao.a12teachingagent.domain.generation.GenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, Long> {
    Optional<GenerationJob> findByProjectIdAndIdempotencyKey(Long projectId, String idempotencyKey);
}
