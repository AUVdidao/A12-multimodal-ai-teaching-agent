package com.auvdidao.a12teachingagent.agent.model;

import com.auvdidao.a12teachingagent.agent.lifecycle.AgentTraceService;
import com.auvdidao.a12teachingagent.agent.runtime.AgentFailureKind;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.domain.agent.AgentTrace;
import com.auvdidao.a12teachingagent.domain.agent.repository.AgentTraceRepository;
import com.auvdidao.a12teachingagent.domain.agent.repository.ToolCallTraceRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelTraceServiceTest {
    @Test
    void recordsOnlySafeModelMetadataAndMapsFailureKind() {
        AgentTraceRepository traces = mock(AgentTraceRepository.class);
        ToolCallTraceRepository toolCalls = mock(ToolCallTraceRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        AgentTrace trace = new AgentTrace();
        trace.setStatus(AgentRunStatus.RUNNING);
        when(traces.findByProjectIdAndRunId(7L, "11111111-1111-1111-1111-111111111111"))
                .thenReturn(Optional.of(trace));
        when(traces.save(any(AgentTrace.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AgentTraceService service = new AgentTraceService(traces, toolCalls, access);
        ModelExecutionContext context = new ModelExecutionContext(7, "11", "TEACHER", "trace-1",
                "11111111-1111-1111-1111-111111111111", "COURSE_OUTLINE", 5_000L, null);

        service.recordModelCall(context, ModelTraceObservation.failure(
                ModelProvider.KIMI, "kimi-k2.6", "request-1", 123L, ModelFailureKind.INVALID_OUTPUT
        ));

        assertEquals("KIMI", trace.getProvider());
        assertEquals("kimi-k2.6", trace.getModel());
        assertEquals("request-1", trace.getRequestId());
        assertEquals(123L, trace.getDurationMs());
        assertEquals(AgentRunStatus.FAILED, trace.getStatus());
        assertEquals(AgentFailureKind.MODEL_INVALID_OUTPUT, trace.getFailureKind());
        verify(access).requireAccess(7L);
        verify(traces).save(trace);
    }
}
