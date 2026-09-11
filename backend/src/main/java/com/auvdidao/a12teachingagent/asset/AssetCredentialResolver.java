package com.auvdidao.a12teachingagent.asset;

import com.auvdidao.a12teachingagent.agent.model.ModelProvider;
import com.auvdidao.a12teachingagent.ai.credential.AiApiCredentialService;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import org.springframework.stereotype.Service;

/** Stage 8 resolver: only the authenticated owner's encrypted provider credential is eligible. */
@Service
public class AssetCredentialResolver {
    private final AiApiCredentialService credentials;

    public AssetCredentialResolver(AiApiCredentialService credentials) { this.credentials = credentials; }

    public String require(Long ownerUserId, ModelProvider provider) {
        if (ownerUserId == null || ownerUserId <= 0 || provider == null || provider == ModelProvider.MOCK) {
            throw new ConflictException("ASSET_PROVIDER_OR_IDENTITY_INVALID");
        }
        String key = credentials.activeApiKey(ownerUserId, provider);
        if (key == null || key.isBlank()) {
            throw new ConflictException("ASSET_PROVIDER_CREDENTIAL_MISSING");
        }
        return key;
    }
}
