package com.auvdidao.a12teachingagent.workspace;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.KnowledgeWorkspaceSearchRequest;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.KnowledgeWorkspaceSearchResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.MaterialWorkspaceResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.ProjectOverviewResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.ProjectPageResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.RequirementSummaryWorkspaceResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.RequirementWorkspaceResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.TeacherWorkspaceResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.TeachingIntentWorkspaceResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.TeachingIntentWorkspaceUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final KnowledgeWorkspaceService knowledgeWorkspaceService;

    public WorkspaceController(
            WorkspaceService workspaceService,
            KnowledgeWorkspaceService knowledgeWorkspaceService
    ) {
        this.workspaceService = workspaceService;
        this.knowledgeWorkspaceService = knowledgeWorkspaceService;
    }

    @GetMapping("/workspace/overview")
    public ApiResponse<TeacherWorkspaceResponse> teacherWorkspace() {
        return ApiResponse.success(workspaceService.teacherWorkspace());
    }

    @GetMapping("/workspace/projects")
    public ApiResponse<ProjectPageResponse> projects(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "UPDATED_DESC") String sort
    ) {
        return ApiResponse.success(workspaceService.projects(query, stage, page, size, sort));
    }

    @GetMapping("/projects/{projectId}/workspace-overview")
    public ApiResponse<ProjectOverviewResponse> projectOverview(
            @PathVariable @Positive Long projectId
    ) {
        return ApiResponse.success(workspaceService.projectOverview(projectId));
    }

    @GetMapping("/projects/{projectId}/requirements/workspace")
    public ApiResponse<RequirementWorkspaceResponse> requirementWorkspace(
            @PathVariable @Positive Long projectId
    ) {
        return ApiResponse.success(workspaceService.requirementWorkspace(projectId));
    }

    @GetMapping("/projects/{projectId}/requirement-summaries/workspace")
    public ApiResponse<RequirementSummaryWorkspaceResponse> requirementSummaryWorkspace(
            @PathVariable @Positive Long projectId
    ) {
        return ApiResponse.success(workspaceService.requirementSummaryWorkspace(projectId));
    }

    @GetMapping("/projects/{projectId}/materials/workspace")
    public ApiResponse<MaterialWorkspaceResponse> materialWorkspace(
            @PathVariable @Positive Long projectId
    ) {
        return ApiResponse.success(workspaceService.materialWorkspace(projectId));
    }

    @PostMapping("/projects/{projectId}/knowledge/workspace-search")
    public ApiResponse<KnowledgeWorkspaceSearchResponse> knowledgeWorkspaceSearch(
            @PathVariable @Positive Long projectId,
            @Valid @RequestBody KnowledgeWorkspaceSearchRequest request
    ) {
        return ApiResponse.success(knowledgeWorkspaceService.search(projectId, request));
    }

    @GetMapping("/projects/{projectId}/teaching-intents/workspace")
    public ApiResponse<TeachingIntentWorkspaceResponse> teachingIntentWorkspace(
            @PathVariable @Positive Long projectId
    ) {
        return ApiResponse.success(workspaceService.teachingIntentWorkspace(projectId));
    }

    @PutMapping("/projects/{projectId}/teaching-intents/{intentId}/workspace")
    public ApiResponse<TeachingIntentWorkspaceResponse> updateTeachingIntentWorkspace(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long intentId,
            @Valid @RequestBody TeachingIntentWorkspaceUpdateRequest request
    ) {
        return ApiResponse.success(workspaceService.updateTeachingIntent(projectId, intentId, request));
    }
}
