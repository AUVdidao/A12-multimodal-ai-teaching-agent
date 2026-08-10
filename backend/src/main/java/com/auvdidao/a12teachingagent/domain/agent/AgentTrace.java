package com.auvdidao.a12teachingagent.domain.agent;

import com.auvdidao.a12teachingagent.agent.runtime.AgentFailureKind;
import com.auvdidao.a12teachingagent.agent.runtime.AgentName;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.agent.runtime.AgentStage;
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
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "agent_traces",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_traces_run_id", columnNames = "run_id"),
        indexes = {
                @Index(name = "idx_agent_traces_project", columnList = "project_id"),
                @Index(name = "idx_agent_traces_trace", columnList = "trace_id"),
                @Index(name = "idx_agent_traces_run", columnList = "run_id")
        }
)
public class AgentTrace extends BaseCreatedEntity {

    @Column(name = "trace_id", nullable = false, length = 128)
    private String traceId;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_name", nullable = false, length = 40)
    private AgentName agentName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AgentStage stage;

    @Column(length = 128)
    private String provider;

    @Column(length = 128)
    private String model;

    @Column(name = "request_id", length = 256)
    private String requestId;

    @Column(name = "input_hash", length = 256)
    private String inputHash;

    @Column(name = "output_hash", length = 256)
    private String outputHash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "agent_trace_retrieved_chunks",
            joinColumns = @JoinColumn(name = "trace_id")
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "chunk_identifier", nullable = false, length = 128)
    private List<String> retrievedChunkIds = new ArrayList<>();

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentRunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_kind", length = 40)
    private AgentFailureKind failureKind;

    public AgentTrace() {
    }

    public Long getId() {
        return super.getId();
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

    public AgentName getAgentName() {
        return agentName;
    }

    public void setAgentName(AgentName agentName) {
        this.agentName = agentName;
    }

    public AgentStage getStage() {
        return stage;
    }

    public void setStage(AgentStage stage) {
        this.stage = stage;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        if (requestId != null && (requestId.isBlank() || requestId.length() > 256)) {
            throw new IllegalArgumentException("requestId must be blank or at most 256 characters");
        }
        this.requestId = requestId;
    }

    public String getInputHash() {
        return inputHash;
    }

    public void setInputHash(String inputHash) {
        this.inputHash = inputHash;
    }

    public String getOutputHash() {
        return outputHash;
    }

    public void setOutputHash(String outputHash) {
        this.outputHash = outputHash;
    }

    public List<String> getRetrievedChunkIds() {
        return List.copyOf(retrievedChunkIds);
    }

    public void setRetrievedChunkIds(List<String> retrievedChunkIds) {
        this.retrievedChunkIds = boundedStrings(retrievedChunkIds, 50, 128, "retrievedChunkIds");
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

    public AgentRunStatus getStatus() {
        return status;
    }

    public void setStatus(AgentRunStatus status) {
        this.status = status;
    }

    public AgentFailureKind getFailureKind() {
        return failureKind;
    }

    public void setFailureKind(AgentFailureKind failureKind) {
        this.failureKind = failureKind;
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
