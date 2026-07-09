package com.auvdidao.a12teachingagent.project;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.project.dto.ProjectCreateRequest;
import com.auvdidao.a12teachingagent.project.dto.ProjectResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;

    public ProjectController(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @PostMapping
    public ApiResponse<ProjectResponse> create(@Valid @RequestBody ProjectCreateRequest request) {
        Project project = new Project();
        project.setProjectName(request.projectName().trim());
        project.setCourseName(normalize(request.courseName()));
        project.setChapterTopic(normalize(request.chapterTopic()));
        project.setTargetAudience(normalize(request.targetAudience()));
        project.setLessonDurationMinutes(request.lessonDurationMinutes());
        project.setGenerationMode(request.generationMode());
        project.setStatus(ProjectStatus.CREATED);

        return ApiResponse.success(toResponse(projectRepository.save(project)));
    }

    private ProjectResponse toResponse(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getProjectName(),
                project.getCourseName(),
                project.getChapterTopic(),
                project.getTargetAudience(),
                project.getLessonDurationMinutes(),
                project.getGenerationMode(),
                project.getStatus(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
