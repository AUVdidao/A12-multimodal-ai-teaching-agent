package com.auvdidao.a12teachingagent.agent.model;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.auvdidao.a12teachingagent.ai.credential.AiApiCredentialService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCredentialResolverTest {
    @Test
    void resolvesCredentialOnlyForContextActor() {
        AiApiCredentialService credentials = mock(AiApiCredentialService.class);
        when(credentials.activeApiKey(100L)).thenReturn("owner-key");
        ModelCredentialResolver resolver = new ModelCredentialResolver(credentials, new KimiAssistantProperties());

        ResolvedModelCredential resolved = resolver.resolve(context("100"), ModelProvider.KIMI);

        assertThat(resolved.value()).isEqualTo("owner-key");
        assertThat(resolved.source()).isEqualTo(CredentialSource.ACTIVE_USER_CREDENTIAL);
    }

    @Test
    void missingActorCredentialAndEnvironmentCredentialFailClosed() {
        AiApiCredentialService credentials = mock(AiApiCredentialService.class);
        when(credentials.activeApiKey(200L)).thenReturn("");
        ModelCredentialResolver resolver = new ModelCredentialResolver(credentials, new KimiAssistantProperties());

        assertThatThrownBy(() -> resolver.resolve(context("200"), ModelProvider.KIMI))
                .isInstanceOf(ModelFailureException.class)
                .hasMessageContaining("No active model credential is configured");
    }

    private ModelExecutionContext context(String actorId) {
        return new ModelExecutionContext(9L, actorId, "TEACHER", "trace", "run", "planning", 1000, java.util.Map.of());
    }
}
