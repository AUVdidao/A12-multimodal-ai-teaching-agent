package com.auvdidao.a12teachingagent.pptskill;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PptOutlineProviderRouter {

    private final PptGeneratorProperties properties;
    private final Map<String, PptOutlineProvider> providers;

    public PptOutlineProviderRouter(PptGeneratorProperties properties, List<PptOutlineProvider> providers) {
        this.properties = properties;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                provider -> provider.providerId().toUpperCase(Locale.ROOT), Function.identity()));
    }

    public JsonNode getOutline(Project project) {
        String selected = properties.getOutlineProvider() == null ? "" : properties.getOutlineProvider().trim().toUpperCase(Locale.ROOT);
        PptOutlineProvider provider = providers.get(selected);
        if (provider == null) {
            throw new PptSkillGenerationException("INVALID_OUTLINE",
                    "PPT outline provider is not configured: " + selected, HttpStatus.CONFLICT);
        }
        return provider.getOutline(project);
    }
}
