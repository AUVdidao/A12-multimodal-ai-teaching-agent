package com.auvdidao.a12teachingagent.project;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.modelmode.dto.ModelModeDtos.ModelModeOption;
import com.auvdidao.a12teachingagent.modelmode.dto.ModelModeDtos.ProjectModelModeResponse;
import com.auvdidao.a12teachingagent.project.dto.ProjectDtos.ProjectRequest;
import com.auvdidao.a12teachingagent.project.dto.ProjectDtos.ProjectResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectService {

    private static final List<ModelModeOption> MODEL_MODES = List.of(
            new ModelModeOption("STANDARD", "标准模式", "标准模式，平衡质量和速度。"),
            new ModelModeOption("QUALITY", "高质量模式", "高质量模式，生成更细致但耗时更长。"),
            new ModelModeOption("ECONOMY", "经济模式", "经济模式，速度快、成本低，适合快速草稿。")
    );

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        Project project = new Project();
        applyProjectFields(project, request);
        project.setProjectName(resolveProjectName(request));
        project.setStatus(ProjectStatus.CREATED);
        project.setGenerationMode(GenerationMode.STANDARD);

        return toProjectResponse(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return projectRepository.findAllByOrderByUpdatedAtDescCreatedAtDesc()
                .stream()
                .map(this::toProjectResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long projectId) {
        return toProjectResponse(findProject(projectId));
    }

    @Transactional
    public ProjectResponse update(Long projectId, ProjectRequest request) {
        Project project = findProject(projectId);
        applyProjectFields(project, request);
        project.setProjectName(resolveProjectName(request));

        return toProjectResponse(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ModelModeOption> listModelModes() {
        return MODEL_MODES;
    }

    @Transactional
    public ProjectModelModeResponse saveModelMode(Long projectId, String mode) {
        Project project = findProject(projectId);
        project.setGenerationMode(parseSupportedMode(mode));

        return toProjectModelModeResponse(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public ProjectModelModeResponse getModelMode(Long projectId) {
        return toProjectModelModeResponse(findProject(projectId));
    }

    private Project findProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    private void applyProjectFields(Project project, ProjectRequest request) {
        project.setCourseName(trimToNull(request.courseName()));
        project.setChapterTopic(trimToNull(request.chapterTitle()));
        project.setTargetAudience(trimToNull(request.targetStudents()));
        project.setLessonDurationMinutes(request.lessonDuration());
        project.setProjectDescription(trimToNull(request.description()));
    }

    private String resolveProjectName(ProjectRequest request) {
        String projectName = trimToNull(request.projectName());
        if (projectName != null) {
            return projectName;
        }
        return trimToNull(request.courseName()) + " - " + trimToNull(request.chapterTitle());
    }

    private ProjectResponse toProjectResponse(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getProjectName(),
                project.getCourseName(),
                project.getChapterTopic(),
                project.getTargetAudience(),
                project.getLessonDurationMinutes(),
                project.getProjectDescription(),
                normalizeMode(project.getGenerationMode()),
                project.getStatus(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private ProjectModelModeResponse toProjectModelModeResponse(Project project) {
        String mode = normalizeMode(project.getGenerationMode());
        ModelModeOption option = MODEL_MODES.stream()
                .filter(item -> item.code().equals(mode))
                .findFirst()
                .orElse(MODEL_MODES.get(0));

        return new ProjectModelModeResponse(project.getId(), option.code(), option.name(), option.description());
    }

    private GenerationMode parseSupportedMode(String mode) {
        String normalized = trimToNull(mode);
        if (normalized == null) {
            throw new BadRequestException("mode is required");
        }

        return switch (normalized.toUpperCase()) {
            case "STANDARD" -> GenerationMode.STANDARD;
            case "QUALITY", "HIGH_QUALITY" -> GenerationMode.HIGH_QUALITY;
            case "ECONOMY" -> GenerationMode.ECONOMY;
            default -> throw new BadRequestException("Unsupported model mode: " + mode);
        };
    }

    private String normalizeMode(GenerationMode mode) {
        if (mode == null) {
            return "STANDARD";
        }
        if (mode == GenerationMode.HIGH_QUALITY) {
            return "QUALITY";
        }
        return mode.name();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
