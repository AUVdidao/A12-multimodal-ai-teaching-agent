package com.auvdidao.a12teachingagent.domain.agent.repository;

import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.domain.agent.AgentRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {
    Optional<AgentRun> findByRunId(String runId);

    Optional<AgentRun> findByProjectIdAndRunId(Long projectId, String runId);

    List<AgentRun> findByProjectIdAndTraceIdOrderByCreatedAtAscIdAsc(Long projectId, String traceId);

    List<AgentRun> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId, Pageable pageable);

    long countByProjectIdAndStatus(Long projectId, AgentRunStatus status);

    boolean existsByRunId(String runId);
}
