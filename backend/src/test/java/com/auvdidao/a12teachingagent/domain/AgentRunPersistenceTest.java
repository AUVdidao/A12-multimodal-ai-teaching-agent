package com.auvdidao.a12teachingagent.domain;

import com.auvdidao.a12teachingagent.agent.runtime.AgentFailureKind;
import com.auvdidao.a12teachingagent.agent.runtime.AgentName;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.agent.runtime.AgentStage;
import com.auvdidao.a12teachingagent.agent.runtime.BusinessRef;
import com.auvdidao.a12teachingagent.agent.runtime.BusinessRefType;
import com.auvdidao.a12teachingagent.domain.agent.AgentRun;
import com.auvdidao.a12teachingagent.domain.agent.AgentTrace;
import com.auvdidao.a12teachingagent.domain.agent.ToolCallTrace;
import com.auvdidao.a12teachingagent.domain.agent.repository.AgentRunRepository;
import com.auvdidao.a12teachingagent.domain.agent.repository.AgentTraceRepository;
import com.auvdidao.a12teachingagent.domain.agent.repository.ToolCallTraceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class AgentRunPersistenceTest {
    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentTraceRepository traceRepository;

    @Autowired
    private ToolCallTraceRepository toolCallTraceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void roundTripsRunTraceAndToolTraceWithEnumsAndTimestamps() {
        AgentRun run = pendingRun();
        AgentRun savedRun = runRepository.saveAndFlush(run);

        AgentTrace trace = new AgentTrace();
        trace.setTraceId(savedRun.getTraceId());
        trace.setRunId(savedRun.getRunId());
        trace.setProjectId(7L);
        trace.setAgentName(AgentName.COURSE_OUTLINE);
        trace.setStage(AgentStage.COURSE_OUTLINE);
        trace.setInputHash("sha256:input");
        trace.setRetrievedChunkIds(List.of("101", "102"));
        trace.setStartedAt(LocalDateTime.of(2026, 8, 9, 1, 2));
        trace.setEndedAt(LocalDateTime.of(2026, 8, 9, 1, 2, 1));
        trace.setDurationMs(1_000L);
        trace.setStatus(AgentRunStatus.SUCCEEDED);
        traceRepository.saveAndFlush(trace);

        ToolCallTrace toolTrace = new ToolCallTrace();
        toolTrace.setToolCallId("tool-call-1");
        toolTrace.setTraceId(savedRun.getTraceId());
        toolTrace.setRunId(savedRun.getRunId());
        toolTrace.setProjectId(7L);
        toolTrace.setToolName("dense-search");
        toolTrace.setStartedAt(LocalDateTime.of(2026, 8, 9, 1, 2));
        toolTrace.setEndedAt(LocalDateTime.of(2026, 8, 9, 1, 2, 1));
        toolTrace.setDurationMs(1_000L);
        toolTrace.setSuccess(true);
        toolTrace.setRetryable(false);
        toolTrace.setSourceRefs(List.of("TEACHING_INTENT:intent-1"));
        toolTrace.setWarnings(List.of("bounded"));
        toolCallTraceRepository.saveAndFlush(toolTrace);

        entityManager.clear();

        AgentRun reloadedRun = runRepository.findByProjectIdAndRunId(7L, savedRun.getRunId()).orElseThrow();
        AgentTrace reloadedTrace = traceRepository.findByProjectIdAndRunId(7L, savedRun.getRunId()).orElseThrow();
        ToolCallTrace reloadedToolTrace = toolCallTraceRepository
                .findByProjectIdAndRunIdOrderByCreatedAtAscIdAsc(7L, savedRun.getRunId())
                .get(0);

        assertEquals(AgentRunStatus.PENDING, reloadedRun.getStatus());
        assertEquals(AgentName.COURSE_OUTLINE, reloadedTrace.getAgentName());
        assertEquals(List.of("101", "102"), reloadedTrace.getRetrievedChunkIds());
        assertEquals(List.of("TEACHING_INTENT:intent-1"), reloadedToolTrace.getSourceRefs());
        assertEquals(List.of("bounded"), reloadedToolTrace.getWarnings());
        assertNotNull(reloadedRun.getCreatedAt());
        assertNotNull(reloadedRun.getUpdatedAt());
        assertNotNull(reloadedRun.getLockVersion());
    }

    @Test
    void enforcesTraceCollectionBounds() {
        AgentTrace trace = new AgentTrace();
        assertThrows(
                IllegalArgumentException.class,
                () -> trace.setRetrievedChunkIds(java.util.stream.IntStream.range(0, 51).mapToObj(String::valueOf).toList())
        );

        ToolCallTrace toolTrace = new ToolCallTrace();
        assertThrows(
                IllegalArgumentException.class,
                () -> toolTrace.setWarnings(java.util.stream.IntStream.range(0, 21).mapToObj(String::valueOf).toList())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> toolTrace.setDurationMs(-1L)
        );
    }

    @Test
    void storesApprovalTargetAsBusinessRefColumns() {
        AgentRun run = pendingRun();
        BusinessRef approvalTarget = new BusinessRef(BusinessRefType.TEACHING_INTENT, "intent-1", null, null);
        run.setApprovalTargetRef(approvalTarget);

        AgentRun saved = runRepository.saveAndFlush(run);
        entityManager.clear();
        AgentRun reloaded = runRepository.findByProjectIdAndRunId(7L, saved.getRunId()).orElseThrow();

        assertEquals(approvalTarget, reloaded.getApprovalTargetRef());
        assertEquals("TEACHING_INTENT", reloaded.getApprovalTargetType().name());
    }

    @Test
    void traceEntitiesHaveNoSecretBearingFieldNames() {
        List<Class<?>> types = List.of(AgentRun.class, AgentTrace.class, ToolCallTrace.class);
        List<String> forbiddenFragments = List.of(
                "reasoning",
                "chainofthought",
                "thinkingtext",
                "rawprompt",
                "rawresponse",
                "credential",
                "token",
                "apikey",
                "bearer",
                "password"
        );

        for (Class<?> type : types) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String fieldName = field.getName().toLowerCase();
                assertTrue(
                        forbiddenFragments.stream().noneMatch(fieldName::contains),
                        () -> type.getSimpleName() + " contains forbidden field " + field.getName()
                );
                assertFalse(fieldName.equals("rawprompt"));
            }
        }
    }

    private AgentRun pendingRun() {
        AgentRun run = new AgentRun();
        run.setRunId("11111111-1111-1111-1111-111111111111");
        run.setTraceId("trace-persistence");
        run.setProjectId(7L);
        run.setActorId("teacher-1");
        run.setActorRole("TEACHER");
        run.setAgentName(AgentName.COURSE_OUTLINE);
        run.setStage(AgentStage.COURSE_OUTLINE);
        run.setStatus(AgentRunStatus.PENDING);
        run.setAttempt(1);
        run.setInputHash("sha256:input");
        return run;
    }
}
