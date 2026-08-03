package com.auvdidao.a12teachingagent.ai.credential;

import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "a12.credentials")
public class AiCredentialProperties {

    private String encryptionKey;

    public byte[] keyMaterial() {
        if (!StringUtils.hasText(encryptionKey)) {
            throw new IllegalStateException("A12_CREDENTIAL_ENCRYPTION_KEY is not configured");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptionKey.strip());
            if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
                throw new IllegalStateException("A12_CREDENTIAL_ENCRYPTION_KEY must decode to 16, 24, or 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("A12_CREDENTIAL_ENCRYPTION_KEY must be valid base64", exception);
        }
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }
}
