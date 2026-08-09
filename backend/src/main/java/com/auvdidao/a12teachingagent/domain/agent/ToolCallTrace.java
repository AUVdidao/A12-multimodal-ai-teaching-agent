package com.auvdidao.a12teachingagent.domain.agent;

import com.auvdidao.a12teachingagent.agent.runtime.AgentFailureKind;
import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "agent_tool_call_traces",
        indexes = {
                @Index(name = "idx_agent_tool_traces_project", columnList = "project_id"),
                @Index(name = "idx_agent_tool_traces_trace", columnList = "trace_id"),
                @Index(name = "idx_agent_tool_traces_run", columnList = "run_id"),
                @Index(name = "idx_agent_tool_traces_tool_call", columnList = "tool_call_id")
        }
)
public class ToolCallTrace extends BaseCreatedEntity {

    @Column(name = "tool_call_id", nullable = false, length = 128)
    private String toolCallId;

    @Column(name = "trace_id", nullable = false, length = 128)
    private String traceId;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(nullable = false)
    private boolean success;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_kind", length = 40)
    private AgentFailureKind failureKind;

    @Column(nullable = false)
    private boolean retryable;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "agent_tool_trace_source_refs",
            joinColumns = @JoinColumn(name = "tool_trace_id")
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "source_identifier", nullable = false, length = 512)
    private List<String> sourceRefs = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "agent_tool_trace_warnings",
            joinColumns = @JoinColumn(name = "tool_trace_id")
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "warning_text", nullable = false, length = 512)
    private List<String> warnings = new ArrayList<>();

    public ToolCallTrace() {
    }

    public Long getId() {
        return super.getId();
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        this.durationMs = durationMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public AgentFailureKind getFailureKind() {
        return failureKind;
    }

    public void setFailureKind(AgentFailureKind failureKind) {
        this.failureKind = failureKind;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public void setRetryable(boolean retryable) {
        this.retryable = retryable;
    }

    public List<String> getSourceRefs() {
        return List.copyOf(sourceRefs);
    }

    public void setSourceRefs(List<String> sourceRefs) {
        this.sourceRefs = boundedStrings(sourceRefs, 50, 512, "sourceRefs");
    }

    public List<String> getWarnings() {
        return List.copyOf(warnings);
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = boundedStrings(warnings, 20, 512, "warnings");
    }

    private static List<String> boundedStrings(List<String> values, int maxItems, int maxLength, String field) {
        List<String> copy = values == null ? List.of() : List.copyOf(values);
        if (copy.size() > maxItems) {
            throw new IllegalArgumentException(field + " must contain at most " + maxItems + " items");
        }
        for (String value : copy) {
            if (value == null || value.isBlank() || value.length() > maxLength) {
                throw new IllegalArgumentException(field + " contains an invalid value");
            }
        }
        return new ArrayList<>(copy);
    }
}
