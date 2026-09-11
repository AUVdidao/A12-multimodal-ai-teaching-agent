package com.auvdidao.a12teachingagent.ai.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "model_call_audits", indexes = {
        @Index(name = "idx_model_call_audits_actor_time", columnList = "actor_user_id, occurred_at"),
        @Index(name = "idx_model_call_audits_connection_time", columnList = "model_connection_id, occurred_at")
})
public class ModelCallAuditEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;
    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;
    @Column(name = "model_connection_id", nullable = false)
    private Long modelConnectionId;
    @Column(nullable = false, length = 32)
    private String protocol;
    @Column(name = "base_url_host", nullable = false, length = 255)
    private String baseUrlHost;
    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;
    @Column(nullable = false, length = 64)
    private String purpose;
    @Column(name = "credential_source", nullable = false, length = 32)
    private String credentialSource;
    @Column(name = "http_status", nullable = false)
    private int httpStatus;
    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long value) { actorUserId = value; }
    public Long getModelConnectionId() { return modelConnectionId; }
    public void setModelConnectionId(Long value) { modelConnectionId = value; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String value) { protocol = value; }
    public String getBaseUrlHost() { return baseUrlHost; }
    public void setBaseUrlHost(String value) { baseUrlHost = value; }
    public String getModelId() { return modelId; }
    public void setModelId(String value) { modelId = value; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    public String getCredentialSource() { return credentialSource; }
    public void setCredentialSource(String value) { credentialSource = value; }
    public int getHttpStatus() { return httpStatus; }
    public void setHttpStatus(int value) { httpStatus = value; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long value) { latencyMs = value; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime value) { occurredAt = value; }
}
