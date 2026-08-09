package com.auvdidao.a12teachingagent.agent.lifecycle;

import com.auvdidao.a12teachingagent.agent.runtime.AgentActorContext;
import com.auvdidao.a12teachingagent.agent.runtime.AgentContext;
import com.auvdidao.a12teachingagent.agent.runtime.AgentFailureKind;
import com.auvdidao.a12teachingagent.agent.runtime.AgentName;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunSnapshot;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.agent.runtime.AgentStage;
import com.auvdidao.a12teachingagent.agent.runtime.BusinessRef;
import com.auvdidao.a12teachingagent.agent.runtime.RuntimeMetadata;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.agent.AgentRun;
import com.auvdidao.a12teachingagent.domain.agent.repository.AgentRunRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.OptimisticLockException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AgentRunLifecycleService {
    private static final int MAX_RECENT_LIMIT = 100;

    private final AgentRunRepository runRepository;
    private final AgentTraceService traceService;
    private final ProjectAccessService projectAccessService;
    private final Clock clock;

    @Autowired
    public AgentRunLifecycleService(
            AgentRunRepository runRepository,
            AgentTraceService traceService,
            ProjectAccessService projectAccessService
    ) {
        this(runRepository, traceService, projectAccessService, Clock.systemUTC());
    }

    AgentRunLifecycleService(
            AgentRunRepository runRepository,
            AgentTraceService traceService,
            ProjectAccessService projectAccessService,
            Clock clock
    ) {
        this.runRepository = runRepository;
        this.traceService = traceService;
        this.projectAccessService = projectAccessService;
        this.clock = clock;
    }

    @Transactional
    public AgentRunSnapshot create(AgentContext context) {
        if (context == null) {
            throw new BadRequestException("AgentContext is required");
        }
        projectAccessService.requireAccess(context.projectId());
        if (runRepository.existsByRunId(context.runId())) {
            throw new ConflictException("Agent run already exists: " + context.runId());
        }
        if (context.parentRunId() != null) {
            AgentRun parent = loadScoped(context.projectId(), context.parentRunId());
            if (!Objects.equals(parent.getTraceId(), context.traceId())) {
                throw new ConflictException("Child run must preserve the parent traceId");
            }
            if (parent.getStatus() != AgentRunStatus.SUCCEEDED) {
                throw new ConflictException("Only a SUCCEEDED run may create a child run");
            }
        }
        AgentRun run = new AgentRun();
        run.setRunId(context.runId());
        run.setTraceId(context.traceId());
        run.setParentRunId(context.parentRunId());
        run.setProjectId(context.projectId());
        run.setActorId(context.actorId());
        run.setActorRole(context.actorRole());
        run.setAgentName(context.agentName());
        run.setStage(context.stage());
        run.setStatus(AgentRunStatus.PENDING);
        run.setAttempt(context.attempt());
        run.setInputHash(null);
        run.setOutputHash(null);
        run.setStartedAt(null);
        run.setEndedAt(null);
        run.setFailureKind(null);
        run.setErrorMessageSafe(null);
        return AgentRunMapper.toSnapshot(saveNew(run));
    }

    @Transactional
    public AgentRunSnapshot createChildRun(
            Long projectId,
            String parentRunId,
            AgentName targetAgent,
            AgentStage targetStage,
            AgentActorContext actor
    ) {
        if (targetAgent == null || targetStage == null || actor == null) {
            throw new BadRequestException("target agent, stage and actor are required");
        }
        AgentRun parent = load(projectId, parentRunId);
        if (parent.getStatus() != AgentRunStatus.SUCCEEDED) {
            throw new ConflictException("Only a SUCCEEDED run may create a child run");
        }
        AgentContext childContext = new AgentContext(
                parent.getTraceId(),
                UUID.randomUUID().toString(),
                parent.getRunId(),
                parent.getProjectId(),
                actor.actorId(),
                actor.actorRole(),
                targetAgent,
                targetStage,
                1,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                RuntimeMetadata.empty()
        );
        return create(childContext);
    }

    @Transactional
    public AgentRunSnapshot start(Long projectId, String runId) {
        AgentRun run = load(projectId, runId);
        if (run.getStatus() == AgentRunStatus.RUNNING) {
            return AgentRunMapper.toSnapshot(run);
        }
        AgentRunTransitionPolicy.requireAllowed(run.getStatus(), AgentRunStatus.RUNNING);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setStartedAt(now());
        run.setEndedAt(null);
        run.setFailureKind(null);
        run.setErrorMessageSafe(null);
        run.setWaitingReason(null);
        run.setApprovalTargetRef(null);
        AgentRun saved = saveTransition(run);
        traceService.recordRunStarted(saved);
        return AgentRunMapper.toSnapshot(saved);
    }

    @Transactional
    public AgentRunSnapshot waitForTool(Long projectId, String runId) {
        return waitForTool(projectId, runId, null);
    }

    @Transactional
    public AgentRunSnapshot waitForTool(Long projectId, String runId, String waitingReason) {
        AgentRun run = load(projectId, runId);
        if (run.getStatus() == AgentRunStatus.WAITING_FOR_TOOL) {
            return AgentRunMapper.toSnapshot(run);
        }
        AgentRunTransitionPolicy.requireAllowed(run.getStatus(), AgentRunStatus.WAITING_FOR_TOOL);
        run.setStatus(AgentRunStatus.WAITING_FOR_TOOL);
        run.setWaitingReason(SafeTraceText.normalize(waitingReason, 256, "waitingReason"));
        run.setApprovalTargetRef(null);
        AgentRun saved = saveTransition(run);
        return AgentRunMapper.toSnapshot(saved);
    }

    @Transactional
    public AgentRunSnapshot waitForHuman(
            Long projectId,
            String runId,
            String waitingReason,
            BusinessRef approvalTargetRef
    ) {
        AgentRun run = load(projectId, runId);
        String safeReason = SafeTraceText.normalize(waitingReason, 256, "waitingReason");
        if (run.getStatus() == AgentRunStatus.WAITING_FOR_HUMAN) {
            if (Objects.equals(run.getWaitingReason(), safeReason)
                    && Objects.equals(run.getApprovalTargetRef(), approvalTargetRef)) {
                return AgentRunMapper.toSnapshot(run);
            }
            throw new ConflictException("WAITING_FOR_HUMAN run already has a different approval request");
        }
        AgentRunTransitionPolicy.requireAllowed(run.getStatus(), AgentRunStatus.WAITING_FOR_HUMAN);
        run.setStatus(AgentRunStatus.WAITING_FOR_HUMAN);
        run.setWaitingReason(safeReason);
        run.setApprovalTargetRef(approvalTargetRef);
        AgentRun saved = saveTransition(run);
        return AgentRunMapper.toSnapshot(saved);
    }

    @Transactional
    public AgentRunSnapshot resume(Long projectId, String runId) {
        AgentRun run = load(projectId, runId);
        AgentRunTransitionPolicy.requireAllowed(run.getStatus(), AgentRunStatus.RUNNING);
        if (run.getStartedAt() == null) {
            throw new ConflictException("A waiting run must have startedAt before resume");
        }
        run.setStatus(AgentRunStatus.RUNNING);
        run.setEndedAt(null);
        run.setFailureKind(null);
        run.setErrorMessageSafe(null);
        run.setWaitingReason(null);
        run.setApprovalTargetRef(null);
        AgentRun saved = saveTransition(run);
        return AgentRunMapper.toSnapshot(saved);
    }

    @Transactional
    public AgentRunSnapshot succeed(Long projectId, String runId, String outputHash) {
        AgentRun run = load(projectId, runId);
        String normalizedHash = SafeTraceText.hash(outputHash);
        if (run.getStatus() == AgentRunStatus.SUCCEEDED) {
            if (Objects.equals(run.getOutputHash(), normalizedHash)) {
                return AgentRunMapper.toSnapshot(run);
            }
            throw new ConflictException("SUCCEEDED run has a different output hash");
        }
        AgentRunTransitionPolicy.requireAllowed(run.getStatus(), AgentRunStatus.SUCCEEDED);
        run.setStatus(AgentRunStatus.SUCCEEDED);
        run.setOutputHash(normalizedHash);
        run.setEndedAt(now());
        run.setFailureKind(null);
        run.setErrorMessageSafe(null);
        run.setWaitingReason(null);
        run.setApprovalTargetRef(null);
        AgentRun saved = saveTransition(run);
        traceService.recordRunCompleted(saved);
        return AgentRunMapper.toSnapshot(saved);
    }

    @Transactional
    public AgentRunSnapshot fail(
            Long projectId,
            String runId,
            AgentFailureKind failureKind,
            String errorMessageSafe
    ) {
        if (failureKind == null || failureKind == AgentFailureKind.CANCELLED) {
            throw new BadRequestException("A non-CANCELLED failureKind is required");
        }
        AgentRun run = load(projectId, runId);
        String safeMessage = SafeTraceText.normalize(errorMessageSafe, 1_024, "errorMessageSafe");
        if (run.getStatus() == AgentRunStatus.FAILED) {
            if (run.getFailureKind() == failureKind && Objects.equals(run.getErrorMessageSafe(), safeMessage)) {
                return AgentRunMapper.toSnapshot(run);
            }
            throw new ConflictException("FAILED run already has a different failure");
        }
        if (run.getStatus() == AgentRunStatus.WAITING_FOR_HUMAN
                && failureKind != AgentFailureKind.HUMAN_REJECTED) {
            throw new ConflictException("Human rejection is the only failure transition from WAITING_FOR_HUMAN");
        }
        AgentRunTransitionPolicy.requireAllowed(run.getStatus(), AgentRunStatus.FAILED);
        run.setStatus(AgentRunStatus.FAILED);
        run.setFailureKind(failureKind);
        run.setErrorMessageSafe(safeMessage);
        run.setEndedAt(now());
        run.setWaitingReason(null);
        run.setApprovalTargetRef(null);
        AgentRun saved = saveTransition(run);
        traceService.recordRunCompleted(saved);
        return AgentRunMapper.toSnapshot(saved);
    }

    @Transactional
    public AgentRunSnapshot cancel(Long projectId, String runId) {
        return cancel(projectId, runId, null);
    }

    @Transactional
    public AgentRunSnapshot cancel(Long projectId, String runId, String reason) {
        AgentRun run = load(projectId, runId);
        if (run.getStatus() == AgentRunStatus.CANCELLED) {
            return AgentRunMapper.toSnapshot(run);
        }
        AgentRunTransitionPolicy.requireAllowed(run.getStatus(), AgentRunStatus.CANCELLED);
        run.setStatus(AgentRunStatus.CANCELLED);
        run.setFailureKind(AgentFailureKind.CANCELLED);
        run.setErrorMessageSafe(SafeTraceText.normalize(reason, 1_024, "errorMessageSafe"));
        run.setEndedAt(now());
        run.setWaitingReason(null);
        run.setApprovalTargetRef(null);
        AgentRun saved = saveTransition(run);
        traceService.recordRunCompleted(saved);
        return AgentRunMapper.toSnapshot(saved);
    }

    @Transactional(readOnly = true)
    public AgentRunSnapshot get(Long projectId, String runId) {
        return AgentRunMapper.toSnapshot(load(projectId, runId));
    }

    @Transactional(readOnly = true)
    public List<AgentRunSnapshot> findRecent(Long projectId, int limit) {
        projectAccessService.requireAccess(projectId);
        if (limit < 1 || limit > MAX_RECENT_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_RECENT_LIMIT);
        }
        return runRepository.findByProjectIdOrderByCreatedAtDescIdDesc(
                        projectId,
                        org.springframework.data.domain.PageRequest.of(0, limit)
                )
                .stream()
                .map(AgentRunMapper::toSnapshot)
                .toList();
    }

    private AgentRun load(Long projectId, String runId) {
        validateProjectId(projectId);
        validateRunId(runId);
        projectAccessService.requireAccess(projectId);
        return loadScoped(projectId, runId);
    }

    private AgentRun loadScoped(Long projectId, String runId) {
        return runRepository.findByProjectIdAndRunId(projectId, runId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent run not found for project: " + projectId));
    }

    private AgentRun saveNew(AgentRun run) {
        try {
            return runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Agent run already exists: " + run.getRunId());
        }
    }

    private AgentRun saveTransition(AgentRun run) {
        try {
            return runRepository.saveAndFlush(run);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw new ConflictException("Agent run was changed by another request; reload before retrying");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    private void validateProjectId(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BadRequestException("projectId must be greater than 0");
        }
    }

    private void validateRunId(String runId) {
        try {
            UUID.fromString(runId);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BadRequestException("runId must be a UUID");
        }
    }
}
