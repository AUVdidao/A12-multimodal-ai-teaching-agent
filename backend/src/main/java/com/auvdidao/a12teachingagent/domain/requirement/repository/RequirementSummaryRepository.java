package com.auvdidao.a12teachingagent.domain.requirement.repository;

import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RequirementSummaryRepository extends JpaRepository<RequirementSummary, Long> {

    Optional<RequirementSummary> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<RequirementSummary> findFirstByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);
}
