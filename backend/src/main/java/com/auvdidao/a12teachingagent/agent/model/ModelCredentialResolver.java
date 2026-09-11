package com.auvdidao.a12teachingagent.agent.model;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.auvdidao.a12teachingagent.ai.connection.ModelConnectionService;
import com.auvdidao.a12teachingagent.ai.connection.ResolvedModelConnection;
import com.auvdidao.a12teachingagent.ai.credential.AiApiCredentialService;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Resolves user-owned active credentials without depending on an HTTP thread or SecurityContext. */
@Service
public class ModelCredentialResolver {
    private final AiApiCredentialService credentialService;
    private final KimiAssistantProperties kimiProperties;
    private final ModelConnectionService modelConnectionService;

    public ModelCredentialResolver(
            AiApiCredentialService credentialService,
            KimiAssistantProperties kimiProperties
    ) { this(credentialService, kimiProperties, null); }

    @org.springframework.beans.factory.annotation.Autowired
    public ModelCredentialResolver(
            AiApiCredentialService credentialService,
            KimiAssistantProperties kimiProperties,
            ModelConnectionService modelConnectionService
    ) {
        this.credentialService = credentialService;
        this.kimiProperties = kimiProperties;
        this.modelConnectionService = modelConnectionService;
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
        throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_CREDENTIAL_MISSING", 503,
                "No active model credential is configured");
    }

    public ResolvedModelConnection resolveConnection(ModelExecutionContext context) {
        if (context == null || context.modelConnectionId() == null) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_CONNECTION_REQUIRED", 400,
                    "A model connection is required for USER_BYOK execution");
        }
        if (modelConnectionService == null) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_CONNECTION_SERVICE_UNAVAILABLE", 503,
                    "Model connection service is unavailable");
        }
        try {
            long owner = Long.parseLong(context.actorId());
            return modelConnectionService.resolveForExecution(owner, context.modelConnectionId());
        } catch (NumberFormatException exception) {
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_ACTOR_INVALID", 400,
                    "The model actor is invalid");
        } catch (RuntimeException exception) {
            if (exception instanceof ModelFailureException modelFailure) throw modelFailure;
            if (exception instanceof ResourceNotFoundException) {
                throw new ResourceNotFoundException("MODEL_CONNECTION_NOT_FOUND");
            }
            if (exception instanceof ConflictException conflict) {
                throw new ConflictException(conflict.getMessage());
            }
            if (exception instanceof BadRequestException badRequest) {
                throw new BadRequestException(badRequest.getMessage());
            }
            throw new ModelFailureException(ModelFailureKind.CONFIGURATION, "MODEL_CONNECTION_UNAVAILABLE", 503,
                    "Model connection is unavailable");
        }
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
