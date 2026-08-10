package com.auvdidao.a12teachingagent.agent.model;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.auvdidao.a12teachingagent.ai.credential.AiApiCredentialService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCredentialResolverTest {
    private final AiApiCredentialService credentialService = mock(AiApiCredentialService.class);
    private final KimiAssistantProperties properties = new KimiAssistantProperties();
    private final ModelCredentialResolver resolver = new ModelCredentialResolver(credentialService, properties);

    @Test
    void activeUserCredentialWinsAndUsesExplicitOwner() {
        when(credentialService.activeApiKey(11L)).thenReturn("user-key-a");

        ResolvedModelCredential result = resolver.resolve(context("11"), ModelProvider.KIMI);

        assertEquals(CredentialSource.ACTIVE_USER_CREDENTIAL, result.source());
        assertEquals("user-key-a", result.value());
        verify(credentialService).activeApiKey(11L);
    }

    @Test
    void anotherOwnerCannotReceiveTheFirstOwnersCredential() {
        when(credentialService.activeApiKey(22L)).thenReturn("");
        properties.setApiKey("server-key");

        ResolvedModelCredential result = resolver.resolve(context("22"), ModelProvider.KIMI);

        assertEquals(CredentialSource.SERVER_ENVIRONMENT, result.source());
        assertEquals("server-key", result.value());
        verify(credentialService).activeApiKey(22L);
    }

    @Test
    void missingUserAndEnvironmentCredentialFailsSafely() {
        when(credentialService.activeApiKey(11L)).thenReturn("");

        ModelFailureException exception = assertThrows(
                ModelFailureException.class,
                () -> resolver.resolve(context("11"), ModelProvider.KIMI)
        );

        assertEquals(ModelFailureKind.CONFIGURATION, exception.kind());
        assertEquals("MODEL_CREDENTIAL_MISSING", exception.safeCode());
        assertEquals(false, exception.getMessage().contains("user-key"));
    }

    private ModelExecutionContext context(String actorId) {
        return new ModelExecutionContext(7, actorId, "TEACHER", "trace-1",
                "11111111-1111-1111-1111-111111111111", "COURSE_OUTLINE", 30_000L, null);
    }
}
