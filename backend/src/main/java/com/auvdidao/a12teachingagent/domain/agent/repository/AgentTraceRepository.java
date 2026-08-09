package com.auvdidao.a12teachingagent.domain.agent.repository;

import com.auvdidao.a12teachingagent.domain.agent.AgentTrace;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentTraceRepository extends JpaRepository<AgentTrace, Long> {
    Optional<AgentTrace> findByRunId(String runId);

    Optional<AgentTrace> findByProjectIdAndRunId(Long projectId, String runId);

    List<AgentTrace> findByProjectIdAndTraceIdOrderByCreatedAtAscIdAsc(Long projectId, String traceId);

    List<AgentTrace> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId, Pageable pageable);
}
