package com.auvdidao.a12teachingagent.domain.agent.repository;

import com.auvdidao.a12teachingagent.domain.agent.ToolCallTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolCallTraceRepository extends JpaRepository<ToolCallTrace, Long> {
    List<ToolCallTrace> findByProjectIdAndRunIdOrderByCreatedAtAscIdAsc(Long projectId, String runId);

    List<ToolCallTrace> findByProjectIdAndTraceIdOrderByCreatedAtAscIdAsc(Long projectId, String traceId);
}
