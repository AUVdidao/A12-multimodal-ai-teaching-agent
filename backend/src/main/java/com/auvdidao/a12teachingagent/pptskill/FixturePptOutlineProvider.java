package com.auvdidao.a12teachingagent.pptskill;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class FixturePptOutlineProvider implements PptOutlineProvider {

    private final ObjectMapper objectMapper;
    private final PptGeneratorProperties properties;

    public FixturePptOutlineProvider(ObjectMapper objectMapper, PptGeneratorProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String providerId() {
        return "FIXTURE";
    }

    @Override
    public JsonNode getOutline(Project project) {
        if (!properties.isFixtureEnabled()) {
            throw new PptSkillGenerationException("INVALID_OUTLINE", "PPT outline fixture is disabled", null);
        }
        try (InputStream input = FixturePptOutlineProvider.class.getResourceAsStream(
                "/pptskill/grade-8-biology-photosynthesis-outline.json")) {
            if (input == null) {
                throw new PptSkillGenerationException("INVALID_OUTLINE", "PPT outline fixture is unavailable", null);
            }
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new PptSkillGenerationException("INVALID_OUTLINE", "PPT outline fixture could not be read", null, exception);
        }
    }
}
