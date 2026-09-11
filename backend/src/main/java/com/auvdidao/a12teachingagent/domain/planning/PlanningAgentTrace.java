package com.auvdidao.a12teachingagent.domain.planning;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.ProposalOperation;
import com.auvdidao.a12teachingagent.planning.PlanningTraceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "planning_agent_traces", indexes = {
        @Index(name = "idx_planning_traces_project", columnList = "project_id"),
        @Index(name = "idx_planning_traces_run", columnList = "run_id")
})
public class PlanningAgentTrace extends BaseCreatedEntity {
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;
    @Column(name = "trace_id", nullable = false, length = 36)
    private String traceId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProposalOperation operation;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PlanningTraceStatus status;
    @Column(length = 32)
    private String requestedProvider;
    @Column(length = 128)
    private String usedProvider;
    @Column(length = 128)
    private String usedModel;
    @Column(length = 64)
    private String capabilityViewChecksum;
    @Column(length = 64)
    private String inputChecksum;
    @Column(length = 64)
    private String outputChecksum;
    @Column(length = 64)
    private String rejectionReason;
    private LocalDateTime completedAt;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long value) { projectId = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId = value; }
    public ProposalOperation getOperation() { return operation; }
    public void setOperation(ProposalOperation value) { operation = value; }
    public PlanningTraceStatus getStatus() { return status; }
    public void setStatus(PlanningTraceStatus value) { status = value; }
    public String getRequestedProvider() { return requestedProvider; }
    public void setRequestedProvider(String value) { requestedProvider = value; }
    public String getUsedProvider() { return usedProvider; }
    public void setUsedProvider(String value) { usedProvider = value; }
    public String getUsedModel() { return usedModel; }
    public void setUsedModel(String value) { usedModel = value; }
    public String getCapabilityViewChecksum() { return capabilityViewChecksum; }
    public void setCapabilityViewChecksum(String value) { capabilityViewChecksum = value; }
    public String getInputChecksum() { return inputChecksum; }
    public void setInputChecksum(String value) { inputChecksum = value; }
    public String getOutputChecksum() { return outputChecksum; }
    public void setOutputChecksum(String value) { outputChecksum = value; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String value) { rejectionReason = value; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime value) { completedAt = value; }
}


