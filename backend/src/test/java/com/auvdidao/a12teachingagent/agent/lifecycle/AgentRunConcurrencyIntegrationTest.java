package com.auvdidao.a12teachingagent.agent.lifecycle;

import com.auvdidao.a12teachingagent.agent.runtime.AgentContext;
import com.auvdidao.a12teachingagent.agent.runtime.AgentName;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunSnapshot;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.agent.runtime.AgentStage;
import com.auvdidao.a12teachingagent.agent.runtime.RuntimeMetadata;
import com.auvdidao.a12teachingagent.domain.agent.repository.AgentRunRepository;
import com.auvdidao.a12teachingagent.domain.agent.repository.AgentTraceRepository;
import com.auvdidao.a12teachingagent.domain.agent.repository.ToolCallTraceRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AgentRunConcurrencyIntegrationTest {
    @Autowired
    private AgentRunLifecycleService lifecycleService;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentTraceRepository traceRepository;

    @Autowired
    private ToolCallTraceRepository toolCallTraceRepository;

    @MockBean
    private ProjectAccessService projectAccessService;

    @BeforeEach
    void setUp() {
        toolCallTraceRepository.deleteAll();
        traceRepository.deleteAll();
        runRepository.deleteAll();
        doNothing().when(projectAccessService).requireAccess(anyLong());
    }

    @Test
    void concurrentSucceedAndCancelHaveOnlyOneSuccessfulTerminalTransition() throws Exception {
        String runId = UUID.randomUUID().toString();
        AgentRunSnapshot created = lifecycleService.create(context(runId));
        lifecycleService.start(7L, created.runId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> succeed = executor.submit(call(() -> lifecycleService.succeed(7L, runId, "sha256:out")));
            Future<Object> cancel = executor.submit(call(() -> lifecycleService.cancel(7L, runId, "cancelled concurrently")));

            Object first = succeed.get();
            Object second = cancel.get();
            long successfulTransitions = List.of(first, second).stream()
                    .filter(AgentRunSnapshot.class::isInstance)
                    .count();

            assertEquals(1L, successfulTransitions);
            assertTrue(first instanceof AgentRunSnapshot || second instanceof AgentRunSnapshot);
            AgentRunStatus finalStatus = runRepository.findByProjectIdAndRunId(7L, runId).orElseThrow().getStatus();
            assertTrue(finalStatus == AgentRunStatus.SUCCEEDED || finalStatus == AgentRunStatus.CANCELLED);
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Object> call(Callable<AgentRunSnapshot> action) {
        return () -> {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                return exception;
            }
        };
    }

    private AgentContext context(String runId) {
        return new AgentContext(
                "trace-concurrency",
                runId,
                null,
                7L,
                "teacher-1",
                "TEACHER",
                AgentName.COURSE_OUTLINE,
                AgentStage.COURSE_OUTLINE,
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
