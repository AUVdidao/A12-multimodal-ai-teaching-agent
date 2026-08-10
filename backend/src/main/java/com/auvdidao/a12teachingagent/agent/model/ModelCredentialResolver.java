package com.auvdidao.a12teachingagent.agent.model;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.auvdidao.a12teachingagent.ai.credential.AiApiCredentialService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Resolves user-owned active credentials without depending on an HTTP thread or SecurityContext. */
@Service
public class ModelCredentialResolver {
    private final AiApiCredentialService credentialService;
    private final KimiAssistantProperties kimiProperties;

    public ModelCredentialResolver(
            AiApiCredentialService credentialService,
            KimiAssistantProperties kimiProperties
    ) {
        this.credentialService = credentialService;
        this.kimiProperties = kimiProperties;
    }

    public ResolvedModelCredential resolve(ModelExecutionContext context, ModelProvider provider) {
        if (context == null || provider == null) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_CONTEXT_INVALID", 0,
                    "Model execution context is invalid");
        }
        if (provider != ModelProvider.KIMI) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_PROVIDER_UNSUPPORTED", 0,
                    "Credential resolution is not required for this provider");
        }

        String userCredential = userCredential(context.actorId());
        if (StringUtils.hasText(userCredential)) {
            return new ResolvedModelCredential(ModelProvider.KIMI, CredentialSource.ACTIVE_USER_CREDENTIAL,
                    userCredential.strip());
        }
        if (StringUtils.hasText(kimiProperties.getApiKey())) {
            return new ResolvedModelCredential(ModelProvider.KIMI, CredentialSource.SERVER_ENVIRONMENT,
                    kimiProperties.getApiKey().strip());
        }
        throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_CREDENTIAL_MISSING", 503,
                "No active model credential is configured");
    }

    private String userCredential(String actorId) {
        try {
            long ownerUserId = Long.parseLong(actorId);
            if (ownerUserId <= 0) {
                return "";
            }
            return credentialService.activeApiKey(ownerUserId);
        } catch (NumberFormatException exception) {
            return "";
        }
    }
}
