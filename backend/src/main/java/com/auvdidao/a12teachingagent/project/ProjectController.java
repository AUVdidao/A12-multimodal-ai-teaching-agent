package com.auvdidao.a12teachingagent.project;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.modelmode.dto.ModelModeDtos.ModelModeRequest;
import com.auvdidao.a12teachingagent.project.dto.ProjectDtos.ProjectRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping("/projects")
    public ApiResponse<?> createProject(@Valid @RequestBody ProjectRequest request) {
        return ApiResponse.success(projectService.create(request));
    }

    @GetMapping("/projects")
    public ApiResponse<?> listProjects() {
        return ApiResponse.success(projectService.list());
    }

    @GetMapping("/projects/{projectId}")
    public ApiResponse<?> getProject(@PathVariable Long projectId) {
        return ApiResponse.success(projectService.get(projectId));
    }

    @PutMapping("/projects/{projectId}")
    public ApiResponse<?> updateProject(
            @PathVariable Long projectId,
            @Valid @RequestBody ProjectRequest request
    ) {
        return ApiResponse.success(projectService.update(projectId, request));
    }

    @GetMapping("/model-modes")
    public ApiResponse<?> listModelModes() {
        return ApiResponse.success(projectService.listModelModes());
    }

    @PutMapping("/projects/{projectId}/model-mode")
    public ApiResponse<?> saveProjectModelMode(
            @PathVariable Long projectId,
            @Valid @RequestBody ModelModeRequest request
    ) {
        return ApiResponse.success(projectService.saveModelMode(projectId, request.mode()));
    }

    @GetMapping("/projects/{projectId}/model-mode")
    public ApiResponse<?> getProjectModelMode(@PathVariable Long projectId) {
        return ApiResponse.success(projectService.getModelMode(projectId));
    }
}
