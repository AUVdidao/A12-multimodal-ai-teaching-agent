package com.auvdidao.a12teachingagent.pptskill;

import com.auvdidao.a12teachingagent.domain.project.Project;
import com.fasterxml.jackson.databind.JsonNode;

public interface PptOutlineProvider {
    String providerId();

    JsonNode getOutline(Project project);
}
