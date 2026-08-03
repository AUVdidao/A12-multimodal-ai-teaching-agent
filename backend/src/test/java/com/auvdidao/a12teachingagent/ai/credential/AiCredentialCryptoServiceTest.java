package com.auvdidao.a12teachingagent.ai.credential;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class AiCredentialCryptoServiceTest {

    @Test
    void roundTripUsesRandomNonceAndRejectsTampering() {
        AiCredentialProperties props = new AiCredentialProperties();
        props.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        AiCredentialCryptoService service = new AiCredentialCryptoService(props);

        String first = service.encrypt("kimi-secret");
        String second = service.encrypt("kimi-secret");

        assertNotEquals(first, second);
        assertEquals("kimi-secret", service.decrypt(first));

        byte[] payload = Base64.getDecoder().decode(first);
        payload[payload.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(payload);
        assertThrows(AiCredentialDecryptionException.class, () -> service.decrypt(tampered));
    }

    @Test
    void acceptsAesKeyLengths() {
        for (int length : new int[]{16, 24, 32}) {
            AiCredentialProperties props = new AiCredentialProperties();
            props.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[length]));
            AiCredentialCryptoService service = new AiCredentialCryptoService(props);
            assertDoesNotThrow(() -> service.encrypt("x"));
        }
    }

    @Test
    void rejectsInvalidMasterKeyLength() {
        AiCredentialProperties props = new AiCredentialProperties();
        props.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[10]));
        AiCredentialCryptoService service = new AiCredentialCryptoService(props);
        assertThrows(IllegalStateException.class, () -> service.encrypt("x"));
    }

    @Test
    void masksShortKeysWithoutRevealingAllCharacters() {
        AiCredentialProperties props = new AiCredentialProperties();
        props.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        AiCredentialCryptoService service = new AiCredentialCryptoService(props);
        assertEquals("****", service.hint("abcd"));
        assertEquals("bcde", service.hint("abcde"));
    }
}
