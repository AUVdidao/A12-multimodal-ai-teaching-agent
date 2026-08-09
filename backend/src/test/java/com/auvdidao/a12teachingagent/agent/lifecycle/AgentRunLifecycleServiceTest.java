package com.auvdidao.a12teachingagent.agent.lifecycle;

import com.auvdidao.a12teachingagent.agent.runtime.AgentActorContext;
import com.auvdidao.a12teachingagent.agent.runtime.AgentContext;
import com.auvdidao.a12teachingagent.agent.runtime.AgentFailureKind;
import com.auvdidao.a12teachingagent.agent.runtime.AgentName;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunSnapshot;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.agent.runtime.AgentStage;
import com.auvdidao.a12teachingagent.agent.runtime.BusinessRef;
import com.auvdidao.a12teachingagent.agent.runtime.BusinessRefType;
import com.auvdidao.a12teachingagent.agent.runtime.RuntimeMetadata;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.domain.agent.AgentRun;
import com.auvdidao.a12teachingagent.domain.agent.repository.AgentRunRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunLifecycleServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T01:02:03Z");

    @Mock
    private AgentRunRepository runRepository;

    @Mock
    private AgentTraceService traceService;

    @Mock
    private ProjectAccessService projectAccessService;

    private final Map<String, AgentRun> runs = new HashMap<>();
    private AgentRunLifecycleService service;

    @BeforeEach
    void setUp() {
        AtomicLong ids = new AtomicLong(1);
        lenient().when(runRepository.existsByRunId(anyString())).thenAnswer(invocation -> runs.containsKey(invocation.getArgument(0)));
        lenient().when(runRepository.findByProjectIdAndRunId(anyLong(), anyString())).thenAnswer(invocation -> {
            AgentRun run = runs.get(invocation.getArgument(1));
            return run == null || !invocation.getArgument(0, Long.class).equals(run.getProjectId())
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(run);
        });
        lenient().when(runRepository.saveAndFlush(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(ids.getAndIncrement());
            }
            runs.put(run.getRunId(), run);
            return run;
        });
        service = new AgentRunLifecycleService(
                runRepository,
                traceService,
                projectAccessService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void persistsPENDINGThenTraversesWaitAndResumeStates() {
        AgentRunSnapshot created = service.create(context(UUID.randomUUID().toString(), null));
        assertEquals(AgentRunStatus.PENDING, created.status());
        assertEquals(null, created.startedAt());
        assertEquals(null, created.endedAt());

        AgentRunSnapshot started = service.start(7L, created.runId());
        assertEquals(AgentRunStatus.RUNNING, started.status());
        assertNotNull(started.startedAt());
        assertEquals(null, started.endedAt());

        AgentRunSnapshot waitingForTool = service.waitForTool(7L, created.runId(), "dense index is not ready");
        assertEquals(AgentRunStatus.WAITING_FOR_TOOL, waitingForTool.status());
        assertEquals(AgentFailureKind.RETRIEVAL_NOT_READY.retryableDefault(), false);

        AgentRunSnapshot resumed = service.resume(7L, created.runId());
        assertEquals(AgentRunStatus.RUNNING, resumed.status());

        AgentRunSnapshot waitingForHuman = service.waitForHuman(
                7L,
                created.runId(),
                "teacher approval required",
                new BusinessRef(BusinessRefType.TEACHING_INTENT, "intent-1", null, null)
        );
        assertEquals(AgentRunStatus.WAITING_FOR_HUMAN, waitingForHuman.status());
        assertEquals(null, waitingForHuman.endedAt());

        AgentRunSnapshot resumedAfterApproval = service.resume(7L, created.runId());
        assertEquals(AgentRunStatus.RUNNING, resumedAfterApproval.status());
    }

    @Test
    void succeedsIdempotentlyButRejectsDifferentTerminalTransition() {
        AgentRunSnapshot created = service.create(context(UUID.randomUUID().toString(), null));
        service.start(7L, created.runId());

        AgentRunSnapshot succeeded = service.succeed(7L, created.runId(), "sha256:output");
        AgentRunSnapshot repeated = service.succeed(7L, created.runId(), "sha256:output");

        assertEquals(AgentRunStatus.SUCCEEDED, succeeded.status());
        assertEquals(succeeded.runId(), repeated.runId());
        assertNotNull(succeeded.endedAt());
        assertThrows(
                ConflictException.class,
                () -> service.succeed(7L, created.runId(), "sha256:different")
        );
        assertThrows(
                ConflictException.class,
                () -> service.fail(7L, created.runId(), AgentFailureKind.INTERNAL_FAILURE, "too late")
        );
    }

    @Test
    void humanRejectionFailsOldRunAndAllowsNewChildRun() {
        AgentRunSnapshot parent = service.create(context(UUID.randomUUID().toString(), null));
        service.start(7L, parent.runId());
        service.waitForHuman(7L, parent.runId(), "review", null);

        AgentRunSnapshot rejected = service.fail(
                7L,
                parent.runId(),
                AgentFailureKind.HUMAN_REJECTED,
                "teacher requested a new draft"
        );
        assertEquals(AgentRunStatus.FAILED, rejected.status());
        assertEquals(AgentFailureKind.HUMAN_REJECTED, rejected.errorKind());
        assertNotNull(rejected.endedAt());

        assertThrows(
                ConflictException.class,
                () -> service.resume(7L, parent.runId())
        );

        AgentRunSnapshot next = service.create(context(UUID.randomUUID().toString(), null));
        assertFalse(next.runId().equals(parent.runId()));
        assertEquals(AgentRunStatus.PENDING, next.status());
    }

    @Test
    void createsChildWithSameTraceAndParentLineage() {
        AgentRunSnapshot parent = service.create(context(UUID.randomUUID().toString(), null));
        service.start(7L, parent.runId());
        service.succeed(7L, parent.runId(), "sha256:parent");

        AgentRunSnapshot child = service.createChildRun(
                7L,
                parent.runId(),
                AgentName.LESSON_PLAN,
                AgentStage.LESSON_PLAN,
                new AgentActorContext("teacher-2", "TEACHER")
        );

        assertEquals(parent.traceId(), child.traceId());
        assertEquals(parent.runId(), child.parentRunId());
        assertEquals(parent.projectId(), child.projectId());
        assertFalse(parent.runId().equals(child.runId()));
        assertEquals(1, child.attempt());
        assertThrows(
                com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException.class,
                () -> service.createChildRun(
                        8L,
                        parent.runId(),
                        AgentName.LESSON_PLAN,
                        AgentStage.LESSON_PLAN,
                        new AgentActorContext("teacher-2", "TEACHER")
                )
        );
    }

    @Test
    void cancelsPendingRunWithEndedAtAndCancelFailureKind() {
        AgentRunSnapshot created = service.create(context(UUID.randomUUID().toString(), null));

        AgentRunSnapshot cancelled = service.cancel(7L, created.runId(), "teacher cancelled");

        assertEquals(AgentRunStatus.CANCELLED, cancelled.status());
        assertEquals(AgentFailureKind.CANCELLED, cancelled.errorKind());
        assertEquals(null, cancelled.startedAt());
        assertNotNull(cancelled.endedAt());
        AgentRunSnapshot repeated = service.cancel(7L, created.runId());
        assertEquals(cancelled.endedAt(), repeated.endedAt());
    }

    @Test
    void normalizesAndBoundsSafeErrorMessages() {
        AgentRunSnapshot created = service.create(context(UUID.randomUUID().toString(), null));
        service.start(7L, created.runId());

        AgentRunSnapshot failed = service.fail(
                7L,
                created.runId(),
                AgentFailureKind.INTERNAL_FAILURE,
                "  bounded\n" + "x".repeat(2_000)
        );

        assertNotNull(failed.errorMessageSafe());
        assertEquals(1_024, failed.errorMessageSafe().length());
        assertThrows(
                ConflictException.class,
                () -> service.fail(7L, created.runId(), AgentFailureKind.INTERNAL_FAILURE, "different")
        );
    }

    @Test
    void scopesReadAndWriteThroughProjectAccessService() {
        doThrow(new ForbiddenException("wrong project"))
                .when(projectAccessService)
                .requireAccess(99L);

        assertThrows(
                ForbiddenException.class,
                () -> service.get(99L, UUID.randomUUID().toString())
        );
        assertThrows(
                ForbiddenException.class,
                () -> service.create(contextForProject(99L, UUID.randomUUID().toString()))
        );
        verify(runRepository, never()).findByProjectIdAndRunId(anyLong(), anyString());
    }

    @Test
    void protectsSecretLikeAndStackTracePatterns() {
        AgentRunSnapshot created = service.create(context(UUID.randomUUID().toString(), null));
        service.start(7L, created.runId());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.fail(7L, created.runId(), AgentFailureKind.INTERNAL_FAILURE, "apiKey=hidden")
        );
    }

    private AgentContext context(String runId, String parentRunId) {
        return contextForProject(7L, runId, parentRunId);
    }

    private AgentContext contextForProject(Long projectId, String runId) {
        return contextForProject(projectId, runId, null);
    }

    private AgentContext contextForProject(Long projectId, String runId, String parentRunId) {
        return new AgentContext(
                "trace-001",
                runId,
                parentRunId,
                projectId,
                "teacher-1",
                "TEACHER",
                AgentName.REQUIREMENT_CLARIFICATION,
                AgentStage.REQUIREMENT_CLARIFICATION,
                1,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                RuntimeMetadata.empty()
        );
    }
}
