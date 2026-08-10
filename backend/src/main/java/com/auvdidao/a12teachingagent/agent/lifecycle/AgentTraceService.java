package com.auvdidao.a12teachingagent.agent.lifecycle;

import com.auvdidao.a12teachingagent.agent.runtime.AgentRunSnapshot;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.agent.runtime.BusinessRef;
import com.auvdidao.a12teachingagent.agent.runtime.ToolResult;
import com.auvdidao.a12teachingagent.agent.model.ModelExecutionContext;
import com.auvdidao.a12teachingagent.agent.model.ModelTraceObservation;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.agent.AgentRun;
import com.auvdidao.a12teachingagent.domain.agent.AgentTrace;
import com.auvdidao.a12teachingagent.domain.agent.ToolCallTrace;
import com.auvdidao.a12teachingagent.domain.agent.repository.AgentTraceRepository;
import com.auvdidao.a12teachingagent.domain.agent.repository.ToolCallTraceRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.util.List;

@Service
public class AgentTraceService {
    private static final int MAX_RECENT_LIMIT = 100;

    private final AgentTraceRepository traceRepository;
    private final ToolCallTraceRepository toolCallTraceRepository;
    private final ProjectAccessService projectAccessService;

    public AgentTraceService(
            AgentTraceRepository traceRepository,
            ToolCallTraceRepository toolCallTraceRepository,
            ProjectAccessService projectAccessService
    ) {
        this.traceRepository = traceRepository;
        this.toolCallTraceRepository = toolCallTraceRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional
    AgentTrace recordRunStarted(AgentRun run) {
        AgentTrace trace = traceRepository.findByRunId(run.getRunId()).orElseGet(AgentTrace::new);
        applyRunIdentity(trace, run);
        trace.setStartedAt(run.getStartedAt());
        trace.setEndedAt(null);
        trace.setDurationMs(null);
        trace.setStatus(AgentRunStatus.RUNNING);
        trace.setFailureKind(null);
        trace.setOutputHash(null);
        return traceRepository.save(trace);
    }

    @Transactional
    AgentTrace recordRunCompleted(AgentRun run) {
        AgentTrace trace = traceRepository.findByRunId(run.getRunId()).orElseGet(AgentTrace::new);
        applyRunIdentity(trace, run);
        if (trace.getStartedAt() == null) {
            trace.setStartedAt(run.getStartedAt());
        }
        trace.setEndedAt(run.getEndedAt());
        trace.setDurationMs(durationMillis(trace.getStartedAt(), trace.getEndedAt()));
        trace.setStatus(run.getStatus());
        trace.setFailureKind(run.getFailureKind());
        trace.setOutputHash(run.getOutputHash());
        return traceRepository.save(trace);
    }

    @Transactional
    public ToolCallTrace recordToolCall(
            AgentRunSnapshot run,
            String toolName,
            Instant startedAt,
            Instant endedAt,
            ToolResult<?> result
    ) {
        if (run == null || result == null) {
            throw new BadRequestException("run and result are required");
        }
        projectAccessService.requireAccess(run.projectId());
        if (!run.traceId().equals(result.traceId())) {
            throw new BadRequestException("tool result traceId does not match run traceId");
        }
        if (startedAt == null || endedAt == null || endedAt.isBefore(startedAt)) {
            throw new BadRequestException("tool call timestamps are invalid");
        }
        ToolCallTrace trace = new ToolCallTrace();
        trace.setToolCallId(result.toolCallId());
        trace.setTraceId(run.traceId());
        trace.setRunId(run.runId());
        trace.setProjectId(run.projectId());
        trace.setToolName(SafeTraceText.normalize(toolName, 128, "toolName"));
        trace.setStartedAt(toLocalDateTime(startedAt));
        trace.setEndedAt(toLocalDateTime(endedAt));
        trace.setDurationMs(result.durationMs());
        trace.setSuccess(result.success());
        trace.setFailureKind(result.errorKind());
        trace.setRetryable(result.retryable());
        trace.setSourceRefs(result.sourceRefs().stream().map(this::sourceIdentifier).toList());
        trace.setWarnings(result.warnings());
        return toolCallTraceRepository.save(trace);
    }

    /** Records safe model-call metadata on the already-created AgentTrace; prompt and response bodies stay out. */
    @Transactional
    public void recordModelCall(ModelExecutionContext context, ModelTraceObservation observation) {
        if (context == null || observation == null) {
            throw new BadRequestException("model context and observation are required");
        }
        projectAccessService.requireAccess(context.projectId());
        traceRepository.findByProjectIdAndRunId(context.projectId(), context.runId()).ifPresent(trace -> {
            trace.setProvider(observation.provider().name());
            trace.setModel(SafeTraceText.normalize(observation.model(), 128, "model"));
            trace.setRequestId(SafeTraceText.normalize(observation.requestId(), 256, "requestId"));
            trace.setDurationMs(observation.durationMs());
            if (observation.success()) {
                trace.setFailureKind(null);
            } else {
                trace.setStatus(AgentRunStatus.FAILED);
                trace.setFailureKind(observation.failureKind().toAgentFailureKind());
            }
            traceRepository.save(trace);
        });
    }

    @Transactional(readOnly = true)
    public AgentTrace find(Long projectId, String runId) {
        projectAccessService.requireAccess(projectId);
        return traceRepository.findByProjectIdAndRunId(projectId, runId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent trace not found for project: " + projectId));
    }

    @Transactional(readOnly = true)
    public List<ToolCallTrace> findToolCalls(Long projectId, String runId) {
        projectAccessService.requireAccess(projectId);
        return toolCallTraceRepository.findByProjectIdAndRunIdOrderByCreatedAtAscIdAsc(projectId, runId);
    }

    @Transactional(readOnly = true)
    public List<AgentTrace> findRecent(Long projectId, int limit) {
        projectAccessService.requireAccess(projectId);
        if (limit < 1 || limit > MAX_RECENT_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_RECENT_LIMIT);
        }
        return traceRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectId, PageRequest.of(0, limit));
    }

    private void applyRunIdentity(AgentTrace trace, AgentRun run) {
        trace.setTraceId(run.getTraceId());
        trace.setRunId(run.getRunId());
        trace.setProjectId(run.getProjectId());
        trace.setAgentName(run.getAgentName());
        trace.setStage(run.getStage());
        trace.setInputHash(run.getInputHash());
    }

    private String sourceIdentifier(BusinessRef ref) {
        return ref.type().name() + ":" + ref.id();
    }

    private long durationMillis(LocalDateTime startedAt, LocalDateTime endedAt) {
        if (startedAt == null || endedAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(startedAt, endedAt).toMillis());
    }

    private LocalDateTime toLocalDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }
}
