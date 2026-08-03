package com.auvdidao.a12teachingagent.ai.credential;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "ai_api_credentials",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_api_credential_owner_provider_slot",
                columnNames = {"owner_user_id", "provider", "key_slot"}
        )
)
public class AiApiCredentialEntity extends BaseAuditableEntity {

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "key_slot", nullable = false)
    private int keySlot;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider = "KIMI";

    @Column(name = "key_version", nullable = false)
    private int keyVersion = 1;

    @Lob
    @Column(name = "encrypted_value", nullable = false)
    private String encryptedValue;

    @Column(name = "key_hint", nullable = false, length = 4)
    private String keyHint;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    public AiApiCredentialEntity() {
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public int getKeySlot() {
        return keySlot;
    }

    public void setKeySlot(int keySlot) {
        this.keySlot = keySlot;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public String getEncryptedValue() {
        return encryptedValue;
    }

    public void setEncryptedValue(String encryptedValue) {
        this.encryptedValue = encryptedValue;
    }

    public String getKeyHint() {
        return keyHint;
    }

    public void setKeyHint(String keyHint) {
        this.keyHint = keyHint;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
