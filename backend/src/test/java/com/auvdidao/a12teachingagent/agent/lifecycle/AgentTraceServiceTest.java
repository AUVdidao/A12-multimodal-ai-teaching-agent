package com.auvdidao.a12teachingagent.agent.lifecycle;

import com.auvdidao.a12teachingagent.agent.runtime.AgentName;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunSnapshot;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.agent.runtime.AgentStage;
import com.auvdidao.a12teachingagent.agent.runtime.BusinessRef;
import com.auvdidao.a12teachingagent.agent.runtime.BusinessRefType;
import com.auvdidao.a12teachingagent.agent.runtime.ToolResult;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.domain.agent.ToolCallTrace;
import com.auvdidao.a12teachingagent.domain.agent.repository.AgentTraceRepository;
import com.auvdidao.a12teachingagent.domain.agent.repository.ToolCallTraceRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class AgentTraceServiceTest {
    @Mock
    private AgentTraceRepository traceRepository;

    @Mock
    private ToolCallTraceRepository toolCallTraceRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    private AgentTraceService service;

    @BeforeEach
    void setUp() {
        lenient().when(toolCallTraceRepository.save(any(ToolCallTrace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new AgentTraceService(traceRepository, toolCallTraceRepository, projectAccessService);
    }

    @Test
    void recordsToolResultWithoutPersistingToolPayload() {
        AgentRunSnapshot run = new AgentRunSnapshot(
                "11111111-1111-1111-1111-111111111111",
                "trace-1",
                null,
                7,
                AgentName.COURSE_OUTLINE,
                AgentStage.COURSE_OUTLINE,
                AgentRunStatus.RUNNING,
                1,
                "sha256:in",
                null,
                Instant.parse("2026-08-09T00:00:00Z"),
                null,
                null,
                null
        );
        ToolResult<String> result = ToolResult.success(
                "tool-call-1",
                "trace-1",
                "this full output must not be persisted",
                31L,
                List.of(new BusinessRef(BusinessRefType.TEACHING_INTENT, "intent-1", null, null)),
                List.of("bounded warning")
        );

        ToolCallTrace trace = service.recordToolCall(
                run,
                "dense-search",
                Instant.parse("2026-08-09T00:00:01Z"),
                Instant.parse("2026-08-09T00:00:02Z"),
                result
        );

        assertEquals("tool-call-1", trace.getToolCallId());
        assertEquals("TEACHING_INTENT:intent-1", trace.getSourceRefs().get(0));
        assertEquals(List.of("bounded warning"), trace.getWarnings());
        verify(projectAccessService).requireAccess(7L);
        verify(toolCallTraceRepository).save(any(ToolCallTrace.class));
    }

    @Test
    void rejectsTraceMismatchAndWrongProjectQuery() {
        AgentRunSnapshot run = new AgentRunSnapshot(
                "11111111-1111-1111-1111-111111111111",
                "trace-1",
                null,
                7,
                AgentName.COURSE_OUTLINE,
                AgentStage.COURSE_OUTLINE,
                AgentRunStatus.RUNNING,
                1,
                null,
                null,
                Instant.parse("2026-08-09T00:00:00Z"),
                null,
                null,
                null
        );
        ToolResult<Void> result = ToolResult.success("tool-call-1", "other-trace", null, 0L);

        assertThrows(
                com.auvdidao.a12teachingagent.common.exception.BadRequestException.class,
                () -> service.recordToolCall(
                        run,
                        "tool",
                        Instant.parse("2026-08-09T00:00:01Z"),
                        Instant.parse("2026-08-09T00:00:02Z"),
                        result
                )
        );

        doThrow(new ForbiddenException("wrong project"))
                .when(projectAccessService)
                .requireAccess(99L);
        assertThrows(ForbiddenException.class, () -> service.find(99L, run.runId()));
        verify(traceRepository, never()).findByProjectIdAndRunId(anyLong(), anyString());
    }
}
