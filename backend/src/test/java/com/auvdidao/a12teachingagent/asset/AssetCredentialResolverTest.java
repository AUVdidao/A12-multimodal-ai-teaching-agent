package com.auvdidao.a12teachingagent.asset;

import com.auvdidao.a12teachingagent.agent.model.ModelProvider;
import com.auvdidao.a12teachingagent.ai.credential.AiApiCredentialService;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AssetCredentialResolverTest {
    @Test
    void mockAndMissingCredentialFailClosed() {
        AiApiCredentialService credentials = mock(AiApiCredentialService.class);
        when(credentials.activeApiKey(9L, ModelProvider.DEEPSEEK)).thenReturn("");
        AssetCredentialResolver resolver = new AssetCredentialResolver(credentials);
        assertThrows(ConflictException.class, () -> resolver.require(9L, ModelProvider.MOCK));
        assertThrows(ConflictException.class, () -> resolver.require(9L, ModelProvider.DEEPSEEK));
        verify(credentials).activeApiKey(9L, ModelProvider.DEEPSEEK);
    }

    @Test
    void providerCredentialLookupIsBoundToRequestedProvider() {
        AiApiCredentialService credentials = mock(AiApiCredentialService.class);
        when(credentials.activeApiKey(9L, ModelProvider.GLM)).thenReturn("short-lived");
        AssetCredentialResolver resolver = new AssetCredentialResolver(credentials);
        org.junit.jupiter.api.Assertions.assertEquals("short-lived", resolver.require(9L, ModelProvider.GLM));
        verify(credentials, never()).activeApiKey(9L, ModelProvider.KIMI);
    }
}
