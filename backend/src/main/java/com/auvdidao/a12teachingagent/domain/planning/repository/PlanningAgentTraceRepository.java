package com.auvdidao.a12teachingagent.domain.planning.repository;

import com.auvdidao.a12teachingagent.domain.planning.PlanningAgentTrace;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanningAgentTraceRepository extends JpaRepository<PlanningAgentTrace, Long> {
    List<PlanningAgentTrace> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId, Pageable pageable);
    Optional<PlanningAgentTrace> findByTraceId(String traceId);
}

