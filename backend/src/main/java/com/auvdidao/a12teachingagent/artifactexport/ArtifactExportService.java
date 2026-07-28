package com.auvdidao.a12teachingagent.artifactexport;

import com.auvdidao.a12teachingagent.artifactexport.ArtifactExportDtos.ExportCatalog;
import com.auvdidao.a12teachingagent.artifactexport.ArtifactExportDtos.ExportOption;
import com.auvdidao.a12teachingagent.artifactexport.ArtifactExportDtos.GeneratedExport;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.ExportType;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.exportrecord.ExportRecord;
import com.auvdidao.a12teachingagent.domain.exportrecord.repository.ExportRecordRepository;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.teachingtask.repository.TeachingTaskRepository;
import com.auvdidao.a12teachingagent.pptskill.PptSkillFileStore;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ArtifactExportService {

    static final String PPTX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    static final String DOCX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final List<ExportType> SUPPORTED_FORMATS = List.of(ExportType.PPTX, ExportType.DOCX);
    private static final int MAX_FILENAME_STEM_LENGTH = 80;

    private final CurrentUserService currentUserService;
    private final ProjectRepository projectRepository;
    private final TeachingTaskRepository taskRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final ArtifactVersionRepository versionRepository;
    private final ExportRecordRepository exportRecordRepository;
    private final ArtifactGenerator renderer;
    private final PptSkillFileStore pptSkillFileStore;

    public ArtifactExportService(
            CurrentUserService currentUserService,
            ProjectRepository projectRepository,
            TeachingTaskRepository taskRepository,
            GeneratedArtifactRepository artifactRepository,
            ArtifactVersionRepository versionRepository,
            ExportRecordRepository exportRecordRepository,
            ArtifactGenerator renderer,
            PptSkillFileStore pptSkillFileStore
    ) {
        this.currentUserService = currentUserService;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.artifactRepository = artifactRepository;
        this.versionRepository = versionRepository;
        this.exportRecordRepository = exportRecordRepository;
        this.renderer = renderer;
        this.pptSkillFileStore = pptSkillFileStore;
    }

    @Transactional(readOnly = true)
    public ExportCatalog listAvailable(Long projectId) {
        AuthenticatedUser teacher = requireTeacher();
        Project project = requireProject(projectId);
        requireProjectAssignment(teacher, projectId);
        Map<Long, Integer> versionNumbers = versionNumbers(projectId);
        Map<ExportType, GeneratedArtifact> available = latestArtifacts(projectId, versionNumbers);
        List<ExportOption> formats = new ArrayList<>();

        for (ExportType format : SUPPORTED_FORMATS) {
            GeneratedArtifact artifact = available.get(format);
            if (artifact != null) {
                formats.add(toOption(project, artifact, format, versionNumbers.get(artifact.getVersionId())));
            }
        }
        return new ExportCatalog(project.getId(), project.getProjectName(), List.copyOf(formats));
    }

    @Transactional
    public GeneratedExport generate(Long projectId, String requestedFormat) {
        AuthenticatedUser teacher = requireTeacher();
        Project project = requireProject(projectId);
        requireProjectAssignment(teacher, projectId);
        ExportType format = parseFormat(requestedFormat);
        Map<Long, Integer> versionNumbers = versionNumbers(projectId);
        GeneratedArtifact artifact = latestArtifacts(projectId, versionNumbers).get(format);
        if (artifact == null) {
            throw new ResourceNotFoundException(format + " artifact not found for project: " + projectId);
        }

        String filename = filename(project, format);
        byte[] content = switch (format) {
            case PPTX -> artifact.getFilePath() == null
                    ? renderer.renderPptx(project, artifact)
                    : pptSkillFileStore.readManaged(artifact.getFilePath());
            case DOCX -> renderer.renderDocx(project, artifact);
            default -> throw unsupportedFormat(requestedFormat);
        };

        ExportRecord record = new ExportRecord();
        record.setProjectId(projectId);
        record.setExportType(format);
        record.setFileName(filename);
        record.setFilePath(null);
        exportRecordRepository.save(record);

        return new GeneratedExport(filename, mediaType(format), content);
    }

    private AuthenticatedUser requireTeacher() {
        return currentUserService.requireRole(UserRole.TEACHER);
    }

    private void requireProjectAssignment(AuthenticatedUser teacher, Long projectId) {
        Project project = requireProject(projectId);
        if (teacher.userId().equals(project.getOwnerUserId())) {
            return;
        }
        boolean assigned = taskRepository.findByAssigneeIdOrderByUpdatedAtDesc(teacher.userId()).stream()
                .anyMatch(task -> projectId.equals(task.getLinkedProjectId()));
        if (!assigned) {
            throw new ForbiddenException("The project is not assigned to the current teacher");
        }
    }

    private Project requireProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        if (project.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
        return project;
    }

    private Map<ExportType, GeneratedArtifact> latestArtifacts(
            Long projectId,
            Map<Long, Integer> versionNumbers
    ) {
        Comparator<GeneratedArtifact> comparator = Comparator
                .comparingInt((GeneratedArtifact artifact) ->
                        versionNumbers.getOrDefault(artifact.getVersionId(), 0))
                .thenComparing(GeneratedArtifact::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(GeneratedArtifact::getId,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
        Map<ExportType, GeneratedArtifact> result = new EnumMap<>(ExportType.class);

        for (GeneratedArtifact artifact : artifactRepository.findByProjectIdOrderByCreatedAtAsc(projectId)) {
            ExportType format = exportFormat(artifact.getArtifactType());
            if (format == null) {
                continue;
            }
            GeneratedArtifact current = result.get(format);
            if (current == null || comparator.compare(artifact, current) > 0) {
                result.put(format, artifact);
            }
        }
        return result;
    }

    private Map<Long, Integer> versionNumbers(Long projectId) {
        Map<Long, Integer> result = new java.util.HashMap<>();
        for (ArtifactVersion version : versionRepository.findByProjectIdOrderByCreatedAtAsc(projectId)) {
            if (version.getId() != null && version.getVersionNumber() != null) {
                result.put(version.getId(), version.getVersionNumber());
            }
        }
        return result;
    }

    private ExportOption toOption(
            Project project,
            GeneratedArtifact artifact,
            ExportType format,
            Integer versionNumber
    ) {
        return new ExportOption(
                format,
                format == ExportType.PPTX ? "PPTX 课件" : "DOCX 教案",
                format == ExportType.PPTX ? "可编辑的 PowerPoint 教学课件" : "可编辑的 Word 课程教案",
                mediaType(format),
                format.name().toLowerCase(Locale.ROOT),
                artifact.getId(),
                artifact.getVersionId(),
                versionNumber,
                filename(project, format),
                "/api/v1/projects/" + project.getId() + "/exports/" + format.name().toLowerCase(Locale.ROOT)
        );
    }

    private static ExportType parseFormat(String value) {
        if (value == null || value.isBlank()) {
            throw unsupportedFormat(value);
        }
        try {
            ExportType format = ExportType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (SUPPORTED_FORMATS.contains(format)) {
                return format;
            }
        } catch (IllegalArgumentException ignored) {
            // Converted below to the API's validation response.
        }
        throw unsupportedFormat(value);
    }

    private static BadRequestException unsupportedFormat(String value) {
        return new BadRequestException(
                "Unsupported export format: " + (value == null ? "null" : value) + ". Supported formats: PPTX, DOCX"
        );
    }

    private static ExportType exportFormat(ArtifactType type) {
        if (type == ArtifactType.PPT) {
            return ExportType.PPTX;
        }
        if (type == ArtifactType.DOCX) {
            return ExportType.DOCX;
        }
        return null;
    }

    private static String mediaType(ExportType format) {
        return format == ExportType.PPTX ? PPTX_MEDIA_TYPE : DOCX_MEDIA_TYPE;
    }

    private static String filename(Project project, ExportType format) {
        String source = firstNonBlank(project.getProjectName(), project.getChapterTopic(), project.getCourseName());
        String stem = source == null ? "project-" + project.getId() : source;
        stem = stem.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("[. ]+$", "");
        if (stem.isBlank()) {
            stem = "project-" + project.getId();
        }
        if (stem.length() > MAX_FILENAME_STEM_LENGTH) {
            stem = stem.substring(0, MAX_FILENAME_STEM_LENGTH);
        }
        return stem + "." + format.name().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
