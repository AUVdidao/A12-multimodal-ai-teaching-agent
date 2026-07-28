package com.auvdidao.a12teachingagent.pptskill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PptOutlineSchemaValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PptOutlineSchemaValidator validator = new PptOutlineSchemaValidator(objectMapper);

    @Test
    void acceptsTheApprovedPhotosynthesisFixture() throws Exception {
        assertDoesNotThrow(() -> validator.validate(readFixture()));
    }

    @Test
    void rejectsMalformedOutlineAndUnsupportedVariant() throws Exception {
        JsonNode malformed = objectMapper.readTree("{\"title\":\"Missing required slides\"}");
        assertEquals("INVALID_OUTLINE", assertThrows(PptSkillGenerationException.class,
                () -> validator.validate(malformed)).getCode());

        JsonNode unsupported = readFixture().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsupported.path("slides").get(0)).put("variant", "generated-image");
        assertEquals("INVALID_OUTLINE", assertThrows(PptSkillGenerationException.class,
                () -> validator.validate(unsupported)).getCode());
    }

    private JsonNode readFixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/pptskill/grade-8-biology-photosynthesis-outline.json")) {
            return objectMapper.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
