package com.auvdidao.a12teachingagent.pptskill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@Component
public class PptOutlineSchemaValidator {

    private final JsonNode schemaNode;
    private final JsonSchema schema;

    public PptOutlineSchemaValidator(ObjectMapper objectMapper) {
        try (InputStream input = PptOutlineSchemaValidator.class.getResourceAsStream(
                "/pptskill/phase-1-outline.schema.json")) {
            if (input == null) {
                throw new IllegalStateException("PPT outline schema is unavailable");
            }
            schemaNode = objectMapper.readTree(input);
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
        } catch (IOException exception) {
            throw new IllegalStateException("PPT outline schema could not be loaded", exception);
        }
    }

    public JsonNode schemaForModel() {
        return schemaNode.deepCopy();
    }

    public void validate(JsonNode outline) {
        Set<ValidationMessage> errors = schema.validate(outline);
        if (!errors.isEmpty()) {
            String summary = errors.stream().limit(3).map(ValidationMessage::getMessage)
                    .reduce((left, right) -> left + "; " + right).orElse("outline is invalid");
            throw new PptSkillGenerationException("INVALID_OUTLINE", "Kimi returned an invalid PPT outline: " + summary,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
