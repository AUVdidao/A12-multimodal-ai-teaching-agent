package com.auvdidao.a12teachingagent.pptskill;

import com.fasterxml.jackson.databind.JsonNode;

public interface PptSkillRunnerClient {
    PptSkillRunnerDtos.RunnerResult generate(JsonNode outline, String stylePreset);
}
