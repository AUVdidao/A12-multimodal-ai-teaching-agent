package com.auvdidao.a12teachingagent.ai.assistant;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/assistant")
public class KimiAssistantController {

    private final KimiAssistantService kimiAssistantService;
    private final ProjectAccessService projectAccessService;

    public KimiAssistantController(KimiAssistantService kimiAssistantService, ProjectAccessService projectAccessService) {
        this.kimiAssistantService = kimiAssistantService;
        this.projectAccessService = projectAccessService;
    }

    @PostMapping("/chat")
    public ApiResponse<KimiAssistantDtos.ChatResponse> chat(
            @PathVariable Long projectId,
            @Valid @RequestBody KimiAssistantDtos.ChatRequest request
    ) {
        projectAccessService.requireAccess(projectId);
        return ApiResponse.success(kimiAssistantService.chat(projectId, request));
    }
}
