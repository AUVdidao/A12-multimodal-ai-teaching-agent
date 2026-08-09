package com.auvdidao.a12teachingagent.domain.agent;

import com.auvdidao.a12teachingagent.agent.runtime.AgentFailureKind;
import com.auvdidao.a12teachingagent.agent.runtime.AgentName;
import com.auvdidao.a12teachingagent.agent.runtime.AgentRunStatus;
import com.auvdidao.a12teachingagent.agent.runtime.AgentStage;
import com.auvdidao.a12teachingagent.agent.runtime.BusinessRef;
import com.auvdidao.a12teachingagent.agent.runtime.BusinessRefType;
import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "agent_runs",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_runs_run_id", columnNames = "run_id"),
        indexes = {
                @Index(name = "idx_agent_runs_project", columnList = "project_id"),
                @Index(name = "idx_agent_runs_trace", columnList = "trace_id"),
                @Index(name = "idx_agent_runs_parent", columnList = "parent_run_id"),
                @Index(name = "idx_agent_runs_project_created", columnList = "project_id, created_at"),
                @Index(name = "idx_agent_runs_status", columnList = "status")
        }
)
public class AgentRun extends BaseAuditableEntity {

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "trace_id", nullable = false, length = 128)
    private String traceId;

    @Column(name = "parent_run_id", length = 36)
    private String parentRunId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "actor_id", nullable = false, length = 128)
    private String actorId;

    @Column(name = "actor_role", nullable = false, length = 64)
    private String actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_name", nullable = false, length = 40)
    private AgentName agentName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AgentStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentRunStatus status;

    @Column(nullable = false)
    private Integer attempt;

    @Column(name = "input_hash", length = 256)
    private String inputHash;

    @Column(name = "output_hash", length = 256)
    private String outputHash;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_kind", length = 40)
    private AgentFailureKind failureKind;

    @Column(name = "error_message_safe", length = 1_024)
    private String errorMessageSafe;

    @Column(name = "waiting_reason", length = 256)
    private String waitingReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_target_type", length = 40)
    private BusinessRefType approvalTargetType;

    @Column(name = "approval_target_id", length = 128)
    private String approvalTargetId;

    @Column(name = "approval_target_version", length = 64)
    private String approvalTargetVersion;

    @Column(name = "approval_target_hash", length = 256)
    private String approvalTargetHash;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    public AgentRun() {
    }

    public Long getId() {
        return super.getId();
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public void setParentRunId(String parentRunId) {
        this.parentRunId = parentRunId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
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

    public AgentRunStatus getStatus() {
        return status;
    }

    public void setStatus(AgentRunStatus status) {
        this.status = status;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
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

    public AgentFailureKind getFailureKind() {
        return failureKind;
    }

    public void setFailureKind(AgentFailureKind failureKind) {
        this.failureKind = failureKind;
    }

    public String getErrorMessageSafe() {
        return errorMessageSafe;
    }

    public void setErrorMessageSafe(String errorMessageSafe) {
        this.errorMessageSafe = errorMessageSafe;
    }

    public String getWaitingReason() {
        return waitingReason;
    }

    public void setWaitingReason(String waitingReason) {
        this.waitingReason = waitingReason;
    }

    public BusinessRefType getApprovalTargetType() {
        return approvalTargetType;
    }

    public void setApprovalTargetType(BusinessRefType approvalTargetType) {
        this.approvalTargetType = approvalTargetType;
    }

    public String getApprovalTargetId() {
        return approvalTargetId;
    }

    public void setApprovalTargetId(String approvalTargetId) {
        this.approvalTargetId = approvalTargetId;
    }

    public String getApprovalTargetVersion() {
        return approvalTargetVersion;
    }

    public void setApprovalTargetVersion(String approvalTargetVersion) {
        this.approvalTargetVersion = approvalTargetVersion;
    }

    public String getApprovalTargetHash() {
        return approvalTargetHash;
    }

    public void setApprovalTargetHash(String approvalTargetHash) {
        this.approvalTargetHash = approvalTargetHash;
    }

    public Long getLockVersion() {
        return lockVersion;
    }

    public BusinessRef getApprovalTargetRef() {
        if (approvalTargetType == null) {
            return null;
        }
        return new BusinessRef(
                approvalTargetType,
                approvalTargetId,
                approvalTargetVersion,
                approvalTargetHash
        );
    }

    public void setApprovalTargetRef(BusinessRef approvalTargetRef) {
        if (approvalTargetRef == null) {
            approvalTargetType = null;
            approvalTargetId = null;
            approvalTargetVersion = null;
            approvalTargetHash = null;
            return;
        }
        approvalTargetType = approvalTargetRef.type();
        approvalTargetId = approvalTargetRef.id();
        approvalTargetVersion = approvalTargetRef.version();
        approvalTargetHash = approvalTargetRef.hash();
    }
}
