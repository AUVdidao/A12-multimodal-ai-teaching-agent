package com.auvdidao.a12teachingagent.pptskill;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.pptskill.harness.PptHarnessGenerationService;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/artifacts")
public class PptSkillArtifactController {

    private final PptGenerationOrchestrator orchestrator;
    private final PptHarnessGenerationService harnessGenerationService;
    private final PptGeneratorProperties properties;

    public PptSkillArtifactController(PptGenerationOrchestrator orchestrator,
                                      PptHarnessGenerationService harnessGenerationService,
                                      PptGeneratorProperties properties) {
        this.orchestrator = orchestrator;
        this.harnessGenerationService = harnessGenerationService;
        this.properties = properties;
    }

    @PostMapping("/generate-ppt")
    public ApiResponse<?> generate(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        if (properties.isHarnessEnabled()) {
            return ApiResponse.success(harnessGenerationService.start(projectId));
        }
        return ApiResponse.success(orchestrator.generate(projectId));
    }
}
