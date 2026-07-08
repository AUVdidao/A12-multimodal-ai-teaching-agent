package com.auvdidao.a12teachingagent.domain.generation.repository;

import com.auvdidao.a12teachingagent.domain.generation.GenerationPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GenerationPlanRepository extends JpaRepository<GenerationPlan, Long> {

    Optional<GenerationPlan> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);
}
