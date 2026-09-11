package com.auvdidao.a12teachingagent.ai.connection;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "model_connections", indexes = {
        @Index(name = "idx_model_connections_owner", columnList = "owner_user_id")
}, uniqueConstraints = @UniqueConstraint(name = "uk_model_connections_owner_name", columnNames = {"owner_user_id", "name"}))
public class ModelConnectionEntity extends BaseAuditableEntity {
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ModelConnectionProtocol protocol = ModelConnectionProtocol.OPENAI_COMPATIBLE;

    @Column(name = "base_url", nullable = false, length = 2048)
    private String baseUrl;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(name = "key_hint", nullable = false, length = 4)
    private String keyHint;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 16)
    private ModelConnectionVerificationStatus verificationStatus = ModelConnectionVerificationStatus.UNVERIFIED;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public ModelConnectionProtocol getProtocol() { return protocol; }
    public void setProtocol(ModelConnectionProtocol value) { protocol = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getModelId() { return modelId; }
    public void setModelId(String value) { modelId = value; }
    public String getEncryptedApiKey() { return encryptedApiKey; }
    public void setEncryptedApiKey(String value) { encryptedApiKey = value; }
    public String getKeyHint() { return keyHint; }
    public void setKeyHint(String value) { keyHint = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public ModelConnectionVerificationStatus getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(ModelConnectionVerificationStatus value) { verificationStatus = value; }
    public LocalDateTime getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(LocalDateTime value) { lastVerifiedAt = value; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime value) { lastUsedAt = value; }
}
