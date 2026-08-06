package com.auvdidao.a12teachingagent.workspace;

import com.auvdidao.a12teachingagent.clarification.ClarificationField;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.dialog.DialogMessage;
import com.auvdidao.a12teachingagent.domain.dialog.repository.DialogMessageRepository;
import com.auvdidao.a12teachingagent.domain.exportrecord.ExportRecord;
import com.auvdidao.a12teachingagent.domain.exportrecord.repository.ExportRecordRepository;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntentEvidence;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementInputRepository;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.material.MaterialLabels;
import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.Activity;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.DialogMessageView;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.IntentEvidenceView;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.IntentOption;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.MaterialItem;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.MaterialStatistics;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.MaterialWorkspaceResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.ParsePreview;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.PendingTask;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.ProjectBrief;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.ProjectCounts;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.ProjectOverviewMetrics;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.ProjectOverviewResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.ProjectPageResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.PurposeOption;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.QuickAction;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.RequirementCompleteness;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.RequirementFieldState;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.RequirementInputView;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.RequirementSourceView;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.RequirementSummaryView;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.RequirementSummaryWorkspaceResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.RequirementWorkspaceResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.Suggestion;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.TeacherWorkspaceResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.TeachingIntentOptions;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.TeachingIntentView;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.TeachingIntentWorkspaceResponse;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.TeachingIntentWorkspaceUpdateRequest;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.TimelineStep;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.UploadPolicy;
import com.auvdidao.a12teachingagent.workspace.dto.WorkspaceDtos.WorkspaceMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class WorkspaceService {

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            "pdf", "docx", "ppt", "pptx", "xlsx", "txt", "md", "png", "jpg", "jpeg", "mp4"
    );

    private static final List<IntentOption> GENERATION_GOAL_OPTIONS = List.of(
            new IntentOption("KNOWLEDGE_UNDERSTANDING", "知识理解"),
            new IntentOption("CONCEPT_MASTERY", "概念掌握"),
            new IntentOption("APPLICATION_ABILITY", "应用能力"),
            new IntentOption("THINKING_DEVELOPMENT", "思维提升"),
            new IntentOption("VALUE_SHAPING", "价值塑造")
    );
    private static final List<IntentOption> CONTENT_BASIS_OPTIONS = List.of(
            new IntentOption("CONFIRMED_REQUIREMENT", "已确认教学需求"),
            new IntentOption("OFFICIAL_OUTLINE", "官方课程大纲"),
            new IntentOption("LOCAL_KNOWLEDGE", "本地知识库"),
            new IntentOption("MATERIAL_EVIDENCE", "上传资料证据")
    );
    private static final List<IntentOption> TEACHING_FORMAT_OPTIONS = List.of(
            new IntentOption("OFFLINE", "线下课堂教学"),
            new IntentOption("ONLINE", "线上教学"),
            new IntentOption("MIXED", "线上线下混合式教学")
    );
    private static final List<IntentOption> OUTPUT_TYPE_OPTIONS = List.of(
            new IntentOption("OUTLINE", "教学大纲"),
            new IntentOption("PPT", "教学PPT"),
            new IntentOption("ACTIVITY", "课堂活动"),
            new IntentOption("ASSESSMENT", "习题与测评"),
            new IntentOption("CASE_LIBRARY", "案例库"),
            new IntentOption("REFERENCE", "参考资料")
    );

    private final ProjectRepository projectRepository;
    private final RequirementInputRepository requirementRepository;
    private final RequirementSummaryRepository summaryRepository;
    private final DialogMessageRepository dialogRepository;
    private final UploadedMaterialRepository materialRepository;
    private final MaterialPurposeRepository purposeRepository;
    private final ParseResultRepository parseResultRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final TeachingIntentRepository intentRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final ArtifactVersionRepository versionRepository;
    private final ExportRecordRepository exportRepository;
    private final StorageProperties storageProperties;
    private final ProjectAccessService projectAccessService;

    public WorkspaceService(
            ProjectRepository projectRepository,
            RequirementInputRepository requirementRepository,
            RequirementSummaryRepository summaryRepository,
            DialogMessageRepository dialogRepository,
            UploadedMaterialRepository materialRepository,
            MaterialPurposeRepository purposeRepository,
            ParseResultRepository parseResultRepository,
            KnowledgeChunkRepository chunkRepository,
            TeachingIntentRepository intentRepository,
            GeneratedArtifactRepository artifactRepository,
            ArtifactVersionRepository versionRepository,
            ExportRecordRepository exportRepository,
            StorageProperties storageProperties,
            ProjectAccessService projectAccessService
    ) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.summaryRepository = summaryRepository;
        this.dialogRepository = dialogRepository;
        this.materialRepository = materialRepository;
        this.purposeRepository = purposeRepository;
        this.parseResultRepository = parseResultRepository;
        this.chunkRepository = chunkRepository;
        this.intentRepository = intentRepository;
        this.artifactRepository = artifactRepository;
        this.versionRepository = versionRepository;
        this.exportRepository = exportRepository;
        this.storageProperties = storageProperties;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(readOnly = true)
    public TeacherWorkspaceResponse teacherWorkspace() {
        List<Snapshot> snapshots = projectAccessService.filterAccessibleProjects(
                        projectRepository.findAllByDeletedAtIsNullOrderByUpdatedAtDescCreatedAtDesc()
                ).stream()
                .map(this::snapshot)
                .toList();
        List<ProjectBrief> projects = snapshots.stream().map(this::projectBrief).toList();
        List<PendingTask> tasks = snapshots.stream()
                .filter(value -> value.project().getStatus() != ProjectStatus.FINALIZED)
                .map(this::pendingTask)
                .limit(12)
                .toList();
        List<Activity> activities = snapshots.stream()
                .flatMap(value -> activities(value).stream())
                .sorted(Comparator.comparing(Activity::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(12)
                .toList();
        List<Suggestion> suggestions = tasks.stream().limit(3).map(task -> new Suggestion(
                "CONTINUE_" + task.code(),
                task.projectId(),
                task.title(),
                task.description(),
                task.actionPath()
        )).toList();
        long pendingTaskCount = snapshots.stream()
                .filter(value -> value.project().getStatus() != ProjectStatus.FINALIZED)
                .count();
        WorkspaceMetrics metrics = new WorkspaceMetrics(
                projects.size(),
                pendingTaskCount,
                pendingTaskCount,
                snapshots.stream().mapToLong(value -> value.materials().size()).sum(),
                snapshots.stream().filter(value -> isConfirmed(value.intent())).count(),
                snapshots.stream().mapToLong(value -> value.artifacts().size()).sum()
        );
        return new TeacherWorkspaceResponse(metrics, projects.stream().limit(5).toList(), tasks, activities, suggestions, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public ProjectPageResponse projects(String query, String stage, int page, int size, String sort) {
        if (page < 0) {
            throw new BadRequestException("page must be at least 0");
        }
        if (size < 1 || size > 100) {
            throw new BadRequestException("size must be between 1 and 100");
        }
        String normalizedQuery = trimToNull(query);
        String normalizedStage = trimToNull(stage);
        String normalizedSort = normalizeSort(sort);
        Comparator<ProjectBrief> comparator = projectComparator(normalizedSort);

        List<ProjectBrief> filtered = projectAccessService.filterAccessibleProjects(projectRepository.findAll()).stream()
                .map(this::snapshot)
                .map(this::projectBrief)
                .filter(project -> matchesQuery(project, normalizedQuery))
                .filter(project -> matchesStage(project, normalizedStage))
                .sorted(comparator)
                .toList();
        int fromIndex = Math.min(page * size, filtered.size());
        int toIndex = Math.min(fromIndex + size, filtered.size());
        int totalPages = filtered.isEmpty() ? 0 : (int) Math.ceil((double) filtered.size() / size);
        return new ProjectPageResponse(
                filtered.subList(fromIndex, toIndex), page, size, filtered.size(), totalPages,
                normalizedSort, normalizedQuery, normalizedStage
        );
    }

    @Transactional(readOnly = true)
    public ProjectOverviewResponse projectOverview(Long projectId) {
        Snapshot value = snapshot(requireProject(projectId));
        ProjectOverviewMetrics metrics = new ProjectOverviewMetrics(
                progress(value),
                value.artifacts().stream().filter(item -> item.getArtifactType() == ArtifactType.PPT).count(),
                value.artifacts().stream().filter(item -> item.getArtifactType() == ArtifactType.DOCX).count(),
                value.artifacts().stream().filter(item -> item.getArtifactType() == ArtifactType.INTERACTION).count(),
                value.materials().size(),
                parsedMaterialCount(value),
                indexedMaterialCount(value),
                value.chunks().size(),
                value.versions().size(),
                value.versions().stream().map(ArtifactVersion::getVersionNumber).filter(Objects::nonNull).max(Integer::compareTo).orElse(null),
                value.versions().stream().anyMatch(item -> Boolean.TRUE.equals(item.getFinalVersion())),
                value.exports().size()
        );
        return new ProjectOverviewResponse(
                projectBrief(value),
                timeline(value),
                metrics,
                activities(value).stream()
                        .sorted(Comparator.comparing(Activity::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(20)
                        .toList(),
                quickActions(value)
        );
    }

    @Transactional(readOnly = true)
    public RequirementWorkspaceResponse requirementWorkspace(Long projectId) {
        Snapshot value = snapshot(requireProject(projectId));
        RequirementCompleteness completeness = completeness(value.project(), value.requirement());
        List<String> questions = completeness.fields().stream()
                .filter(field -> !field.completed())
                .map(field -> questionFor(field.code()))
                .toList();
        List<DialogMessageView> dialogues = dialogRepository.findByProjectIdOrderByCreatedAtAscIdAsc(projectId).stream()
                .map(this::dialogView)
                .toList();
        return new RequirementWorkspaceResponse(
                projectBrief(value),
                requirementView(value.requirement()),
                dialogues,
                completeness,
                questions,
                value.requirement() != null && completeness.complete()
        );
    }

    @Transactional(readOnly = true)
    public RequirementSummaryWorkspaceResponse requirementSummaryWorkspace(Long projectId) {
        Snapshot value = snapshot(requireProject(projectId));
        RequirementSummary summary = value.summary();
        boolean confirmed = summary != null && summary.getStatus() == RequirementSummaryStatus.CONFIRMED;
        return new RequirementSummaryWorkspaceResponse(
                projectBrief(value),
                summaryView(summary),
                summary == null ? null : new RequirementSourceView(
                        summary.getSourceRequirementId(),
                        "TEACHER_REQUIREMENT",
                        value.requirement() == null ? null : value.requirement().getCreatedAt()
                ),
                summary != null && !confirmed,
                summary != null && !confirmed && summaryConfirmable(summary),
                List.of("教学设计方案", "教学资源生成", "学情分析与个性化建议")
        );
    }

    @Transactional(readOnly = true)
    public MaterialWorkspaceResponse materialWorkspace(Long projectId) {
        Snapshot value = snapshot(requireProject(projectId));
        List<MaterialItem> items = value.materials().stream().map(this::materialItem).toList();
        MaterialStatistics statistics = new MaterialStatistics(
                items.size(),
                items.stream().filter(item -> item.parseStatus() == MaterialParseStatus.PROCESSING).count(),
                items.stream().filter(item -> item.parseStatus() == MaterialParseStatus.SUCCEEDED).count(),
                items.stream().filter(item -> item.parseStatus() == MaterialParseStatus.FAILED).count(),
                indexedMaterialCount(value)
        );
        UploadPolicy policy = new UploadPolicy(
                storageProperties.getMaxFileSize(),
                storageProperties.getMaxFileSize() / (1024 * 1024),
                SUPPORTED_EXTENSIONS,
                true,
                value.summary() != null && value.summary().getStatus() == RequirementSummaryStatus.CONFIRMED
        );
        return new MaterialWorkspaceResponse(projectBrief(value), policy, purposeOptions(), statistics, items);
    }

    @Transactional(readOnly = true)
    public TeachingIntentWorkspaceResponse teachingIntentWorkspace(Long projectId) {
        Snapshot value = snapshot(requireProject(projectId));
        TeachingIntent intent = value.intent();
        boolean canGenerate = value.summary() != null
                && value.summary().getStatus() == RequirementSummaryStatus.CONFIRMED
                && parsedMaterialCount(value) > 0
                && !value.chunks().isEmpty();
        boolean confirmed = isConfirmed(intent);
        return new TeachingIntentWorkspaceResponse(
                projectBrief(value),
                intentView(intent, value.project()),
                new TeachingIntentOptions(
                        GENERATION_GOAL_OPTIONS,
                        CONTENT_BASIS_OPTIONS,
                        TEACHING_FORMAT_OPTIONS,
                        OUTPUT_TYPE_OPTIONS
                ),
                canGenerate,
                intent != null && !confirmed,
                intent != null && !confirmed && intentConfirmable(intent),
                intent == null ? 0 : intent.getEvidenceItems().size()
        );
    }

    @Transactional
    public TeachingIntentWorkspaceResponse updateTeachingIntent(
            Long projectId,
            Long intentId,
            TeachingIntentWorkspaceUpdateRequest request
    ) {
        Project project = requireProject(projectId);
        TeachingIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new ResourceNotFoundException("Teaching intent not found: " + intentId));
        if (!projectId.equals(intent.getProjectId())) {
            throw new ResourceNotFoundException("Teaching intent does not belong to project: " + projectId);
        }
        if (intent.getStatus() == TeachingIntentStatus.CONFIRMED) {
            throw new ConflictException("A confirmed teaching intent cannot be modified");
        }
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }

        List<String> goals = normalizeValues(request.generationGoals());
        List<String> supplementalBasis = normalizeValues(request.supplementalBasis());
        String primaryBasis = trimToNull(request.primaryBasis());
        String audience = trimToNull(request.targetAudience());
        String format = trimToNull(request.teachingFormat());
        List<String> outputs = normalizeValues(request.outputTypes());

        intent.setGenerationGoals(goals);
        intent.setGenerationGoal(String.join("；", goals));
        intent.setPrimaryBasis(primaryBasis);
        intent.setSupplementalBasis(supplementalBasis);
        intent.setContentBasis(buildContentBasis(primaryBasis, supplementalBasis));
        intent.setTargetAudience(audience);
        intent.setTotalHours(request.totalHours());
        intent.setTeachingFormat(format);
        intent.setTeachingApproach("面向" + audience + "，共" + request.totalHours() + "学时，采用" + format + "。");
        intent.setInteractionMode(format);
        intent.setOutputTypes(outputs);
        intent.setStylePreference(trimToNull(request.stylePreference()));
        intent.setNotes(trimToNull(request.notes()));
        intentRepository.save(intent);
        return teachingIntentWorkspace(project.getId());
    }

    private Snapshot snapshot(Project project) {
        return new Snapshot(
                project,
                requirementRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId()).orElse(null),
                summaryRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId()).orElse(null),
                materialRepository.findByProjectIdOrderByCreatedAtAsc(project.getId()),
                chunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(project.getId()),
                intentRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId()).orElse(null),
                artifactRepository.findByProjectIdOrderByCreatedAtAsc(project.getId()),
                versionRepository.findByProjectIdOrderByCreatedAtAsc(project.getId()),
                exportRepository.findByProjectIdOrderByCreatedAtAsc(project.getId())
        );
    }

    private Project requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BadRequestException("projectId must be greater than 0");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        projectAccessService.requireAccess(project);
        return project;
    }

    private ProjectBrief projectBrief(Snapshot value) {
        Project project = value.project();
        return new ProjectBrief(
                project.getId(),
                project.getProjectName(),
                firstNonBlank(project.getProjectDescription(), project.getChapterTopic(), project.getCourseName()),
                project.getCourseName(),
                project.getChapterTopic(),
                project.getTargetAudience(),
                project.getLessonDurationMinutes(),
                lessonDurationLabel(project.getLessonDurationMinutes()),
                normalizeMode(project.getGenerationMode()),
                project.getStatus(),
                stage(value),
                stageLabel(stage(value)),
                progress(value),
                nextAction(value),
                actionPath(value),
                new ProjectCounts(
                        value.materials().size(),
                        parsedMaterialCount(value),
                        value.chunks().size(),
                        value.artifacts().size(),
                        value.versions().size(),
                        value.exports().size()
                ),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private String legacyStage(Snapshot value) {
        int progress = progress(value);
        if (progress >= 100) return "FINALIZED";
        if (progress >= 85) return "CONTENT_GENERATED";
        if (progress >= 70) return "INTENT_CONFIRMED";
        if (progress >= 60) return "KNOWLEDGE_INDEXED";
        if (progress >= 40) return "MATERIAL_ANALYZING";
        if (progress >= 30) return "REQUIREMENT_CONFIRMED";
        return "REQUIREMENT_CLARIFYING";
    }

    private int legacyProgress(Snapshot value) {
        int derived = 5;
        if (value.requirement() != null) {
            derived = Math.min(29, 10 + completeness(value.project(), value.requirement()).percentage() / 5);
        }
        if (value.summary() != null && value.summary().getStatus() == RequirementSummaryStatus.CONFIRMED) derived = Math.max(derived, 30);
        if (!value.materials().isEmpty()) derived = Math.max(derived, 40);
        if (parsedMaterialCount(value) > 0) derived = Math.max(derived, 50);
        if (!value.chunks().isEmpty()) derived = Math.max(derived, 60);
        if (isConfirmed(value.intent())) derived = Math.max(derived, 70);
        if (!value.artifacts().isEmpty()) derived = Math.max(derived, 85);
        if (value.project().getStatus() == ProjectStatus.FINALIZED
                || value.versions().stream().anyMatch(item -> Boolean.TRUE.equals(item.getFinalVersion()))) {
            derived = 100;
        }
        return Math.max(derived, progressForStatus(value.project().getStatus()));
    }

    private static int progressForStatus(ProjectStatus status) {
        if (status == null) return 5;
        return switch (status) {
            case CREATED -> 5;
            case REQUIREMENT_CONFIRMED -> 30;
            case MATERIAL_READY -> 60;
            case INTENT_CONFIRMED -> 70;
            case GENERATED -> 85;
            case FINALIZED -> 100;
        };
    }

    private String legacyNextAction(Snapshot value) {
        if (value.requirement() == null) return "完善教学需求";
        if (!completeness(value.project(), value.requirement()).complete()) return "完善教学需求";
        if (value.summary() == null || value.summary().getStatus() != RequirementSummaryStatus.CONFIRMED) return "确认需求摘要";
        if (value.materials().isEmpty()) return "上传参考资料";
        if (parsedMaterialCount(value) < value.materials().size()) return "完成资料解析";
        if (value.chunks().isEmpty()) return "构建本地知识索引";
        if (value.intent() == null) return "生成教学意图";
        if (!isConfirmed(value.intent())) return "确认教学意图";
        if (value.artifacts().isEmpty()) return "生成教学内容";
        if (progress(value) < 100) return "预览并确认版本";
        return "导出教学成果";
    }

    private String legacyActionPath(Snapshot value) {
        String root = "/projects/" + value.project().getId();
        if (value.requirement() == null) return root + "/requirements";
        if (!completeness(value.project(), value.requirement()).complete()) return root + "/requirements";
        if (value.summary() == null || value.summary().getStatus() != RequirementSummaryStatus.CONFIRMED) return root + "/summary";
        if (value.materials().isEmpty() || parsedMaterialCount(value) < value.materials().size()) return root + "/materials";
        if (value.chunks().isEmpty()) return root + "/knowledge";
        if (value.intent() == null || !isConfirmed(value.intent())) return root + "/intent";
        if (value.artifacts().isEmpty()) return root + "/plan";
        if (progress(value) < 100) return root + "/preview";
        return root + "/export";
    }

    private PendingTask pendingTask(Snapshot value) {
        return new PendingTask(
                stage(value),
                value.project().getId(),
                nextAction(value) + "：《" + value.project().getProjectName() + "》",
                "该任务由当前项目数据与阶段确定性派生，未设置虚构截止时间。",
                progress(value) < 30 ? "HIGH" : progress(value) < 70 ? "MEDIUM" : "LOW",
                actionPath(value),
                true
        );
    }

    private List<Activity> activities(Snapshot value) {
        List<Activity> result = new ArrayList<>();
        Project project = value.project();
        result.add(new Activity("PROJECT_CREATED", project.getId(), "项目已创建", project.getProjectName(), project.getCreatedAt()));
        if (value.requirement() != null) {
            result.add(new Activity("REQUIREMENT_SAVED", project.getId(), "教学需求已保存", value.requirement().getTopic(), value.requirement().getUpdatedAt()));
        }
        if (value.summary() != null) {
            result.add(new Activity(
                    value.summary().getStatus() == RequirementSummaryStatus.CONFIRMED ? "SUMMARY_CONFIRMED" : "SUMMARY_DRAFTED",
                    project.getId(),
                    value.summary().getStatus() == RequirementSummaryStatus.CONFIRMED ? "需求摘要已确认" : "需求摘要已生成",
                    value.summary().getTopic(),
                    firstNonNull(value.summary().getConfirmedAt(), value.summary().getUpdatedAt())
            ));
        }
        value.materials().forEach(material -> result.add(new Activity(
                "MATERIAL_" + normalizedParseStatus(material),
                project.getId(),
                "资料" + parseStatusLabel(material),
                material.getOriginalFileName(),
                material.getUpdatedAt()
        )));
        if (value.intent() != null) {
            result.add(new Activity(
                    isConfirmed(value.intent()) ? "INTENT_CONFIRMED" : "INTENT_DRAFTED",
                    project.getId(),
                    isConfirmed(value.intent()) ? "教学意图已确认" : "教学意图草稿已生成",
                    intentGoalDescription(value.intent()),
                    firstNonNull(value.intent().getConfirmedAt(), value.intent().getUpdatedAt())
            ));
        }
        value.artifacts().forEach(artifact -> result.add(new Activity(
                "ARTIFACT_GENERATED",
                project.getId(),
                "教学成果已生成",
                artifact.getTitle(),
                artifact.getCreatedAt()
        )));
        return result;
    }

    private List<TimelineStep> legacyTimeline(Snapshot value) {
        LocalDateTime parsedAt = value.materials().stream()
                .filter(item -> item.getParseStatus() == MaterialParseStatus.SUCCEEDED)
                .map(UploadedMaterial::getUpdatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        LocalDateTime indexedAt = value.chunks().stream()
                .map(KnowledgeChunk::getCreatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        LocalDateTime generatedAt = value.artifacts().stream()
                .map(GeneratedArtifact::getCreatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        LocalDateTime finalizedAt = value.exports().stream()
                .map(ExportRecord::getCreatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElseGet(() -> value.versions().stream()
                        .filter(item -> Boolean.TRUE.equals(item.getFinalVersion()))
                        .map(ArtifactVersion::getCreatedAt)
                        .filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo)
                        .orElse(null));
        List<StepValue> steps = List.of(
                new StepValue("PROJECT_CREATED", "项目创建", true, value.project().getCreatedAt()),
                new StepValue("REQUIREMENT_CLARIFIED", "需求澄清", value.requirement() != null, value.requirement() == null ? null : value.requirement().getUpdatedAt()),
                new StepValue("REQUIREMENT_CONFIRMED", "需求确认", value.summary() != null && value.summary().getStatus() == RequirementSummaryStatus.CONFIRMED, value.summary() == null ? null : value.summary().getConfirmedAt()),
                new StepValue(
                        "MATERIAL_PARSED",
                        "资料解析",
                        !value.materials().isEmpty() && parsedMaterialCount(value) == value.materials().size(),
                        parsedAt
                ),
                new StepValue("KNOWLEDGE_INDEXED", "知识库增强", !value.chunks().isEmpty(), indexedAt),
                new StepValue("INTENT_CONFIRMED", "教学意图", isConfirmed(value.intent()), value.intent() == null ? null : value.intent().getConfirmedAt()),
                new StepValue("CONTENT_GENERATED", "内容生成", !value.artifacts().isEmpty(), generatedAt),
                new StepValue("FINALIZED", "成果导出", progress(value) >= 100, finalizedAt)
        );
        int firstIncomplete = -1;
        for (int index = 0; index < steps.size(); index++) {
            if (!steps.get(index).completed()) {
                firstIncomplete = index;
                break;
            }
        }
        List<TimelineStep> result = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            StepValue step = steps.get(index);
            boolean completedPrefix = firstIncomplete < 0 || index < firstIncomplete;
            String state = completedPrefix && step.completed()
                    ? "COMPLETED"
                    : index == firstIncomplete ? "CURRENT" : "PENDING";
            LocalDateTime completedAt = "COMPLETED".equals(state) ? step.completedAt() : null;
            result.add(new TimelineStep(step.code(), step.label(), state, completedAt));
        }
        return List.copyOf(result);
    }

    private List<QuickAction> legacyQuickActions(Snapshot value) {
        String root = "/projects/" + value.project().getId();
        return List.of(
                new QuickAction("DIALOGUES", "查看对话记录", root + "/requirements", true),
                new QuickAction("SUMMARY", "需求摘要", root + "/summary", value.requirement() != null),
                new QuickAction("MATERIALS", "管理资料", root + "/materials", value.summary() != null && value.summary().getStatus() == RequirementSummaryStatus.CONFIRMED),
                new QuickAction("KNOWLEDGE", "知识检索", root + "/knowledge", !value.chunks().isEmpty()),
                new QuickAction("INTENT", "确认意图", root + "/intent", !value.chunks().isEmpty()),
                new QuickAction("PREVIEW", "预览内容", root + "/preview", !value.artifacts().isEmpty()),
                new QuickAction("EXPORT", "导出文件", root + "/export", !value.artifacts().isEmpty())
        );
    }

    private String stage(Snapshot value) {
        if (pptComplete(value)) return "PPT";
        if (lessonPlanComplete(value)) return "LESSON_PLAN";
        if (outlineComplete(value)) return "OUTLINE";
        if (materialsComplete(value)) return "MATERIALS";
        return "REQUIREMENTS";
    }

    private int progress(Snapshot value) {
        if (pptComplete(value)) return 100;
        if (lessonPlanComplete(value)) return 80;
        if (outlineComplete(value)) return 60;
        if (materialsComplete(value)) return 40;
        if (requirementsComplete(value)) return 20;
        if (value.requirement() == null) return 5;
        return Math.min(19, Math.max(5, completeness(value.project(), value.requirement()).percentage() / 5));
    }

    private boolean requirementsComplete(Snapshot value) {
        return value.requirement() != null
                && completeness(value.project(), value.requirement()).complete()
                && value.summary() != null
                && value.summary().getStatus() == RequirementSummaryStatus.CONFIRMED;
    }

    private boolean materialsComplete(Snapshot value) {
        return !value.materials().isEmpty()
                && parsedMaterialCount(value) == value.materials().size();
    }

    private boolean outlineComplete(Snapshot value) {
        return isConfirmed(value.intent());
    }

    private boolean lessonPlanComplete(Snapshot value) {
        return value.artifacts().stream().anyMatch(item -> item.getArtifactType() == ArtifactType.DOCX);
    }

    private boolean pptComplete(Snapshot value) {
        return value.artifacts().stream().anyMatch(item -> item.getArtifactType() == ArtifactType.PPT);
    }

    private String nextAction(Snapshot value) {
        if (!requirementsComplete(value)) return "\u5b8c\u5584\u6559\u5b66\u9700\u6c42";
        if (!materialsComplete(value)) return "\u8865\u5145\u8d44\u6599\u4e2d\u5fc3";
        if (!outlineComplete(value)) return "\u786e\u8ba4\u8bfe\u7a0b\u5927\u7eb2";
        if (!lessonPlanComplete(value)) return "\u5b8c\u6210\u6559\u6848\u8bbe\u8ba1";
        if (!pptComplete(value)) return "\u751f\u6210PPT\u6210\u679c";
        return "\u67e5\u770bPPT\u6210\u679c";
    }

    private String actionPath(Snapshot value) {
        String root = "/projects/" + value.project().getId();
        if (!requirementsComplete(value)) return root + "/requirements";
        if (!materialsComplete(value)) return root + "/materials";
        if (!outlineComplete(value)) return root + "/outline";
        if (!lessonPlanComplete(value)) return root + "/lesson-plan";
        return root + "/ppt";
    }

    private List<TimelineStep> timeline(Snapshot value) {
        LocalDateTime materialAt = value.materials().stream()
                .filter(item -> item.getParseStatus() == MaterialParseStatus.SUCCEEDED)
                .map(UploadedMaterial::getUpdatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        LocalDateTime outlineAt = value.intent() == null ? null :
                firstNonNull(value.intent().getConfirmedAt(), value.intent().getUpdatedAt());
        LocalDateTime lessonPlanAt = latestArtifactAt(value, ArtifactType.DOCX);
        LocalDateTime pptAt = latestArtifactAt(value, ArtifactType.PPT);
        List<StepValue> steps = List.of(
                new StepValue("REQUIREMENTS", "\u6559\u5b66\u9700\u6c42", requirementsComplete(value),
                        value.requirement() == null ? null : firstNonNull(value.summary() == null ? null : value.summary().getConfirmedAt(), value.requirement().getUpdatedAt())),
                new StepValue("MATERIALS", "\u8d44\u6599\u4e2d\u5fc3", materialsComplete(value), materialAt),
                new StepValue("OUTLINE", "\u8bfe\u7a0b\u5927\u7eb2", outlineComplete(value), outlineAt),
                new StepValue("LESSON_PLAN", "\u6559\u6848\u8bbe\u8ba1", lessonPlanComplete(value), lessonPlanAt),
                new StepValue("PPT", "PPT\u6210\u679c", pptComplete(value), pptAt)
        );
        return timelineSteps(steps);
    }

    private LocalDateTime latestArtifactAt(Snapshot value, ArtifactType type) {
        return value.artifacts().stream()
                .filter(item -> item.getArtifactType() == type)
                .map(GeneratedArtifact::getCreatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private List<TimelineStep> timelineSteps(List<StepValue> steps) {
        int firstIncomplete = -1;
        for (int index = 0; index < steps.size(); index++) {
            if (!steps.get(index).completed()) {
                firstIncomplete = index;
                break;
            }
        }
        List<TimelineStep> result = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            StepValue step = steps.get(index);
            boolean completedPrefix = firstIncomplete < 0 || index < firstIncomplete;
            String state = completedPrefix && step.completed()
                    ? "COMPLETED"
                    : index == firstIncomplete ? "CURRENT" : "PENDING";
            result.add(new TimelineStep(step.code(), step.label(), state,
                    "COMPLETED".equals(state) ? step.completedAt() : null));
        }
        return List.copyOf(result);
    }

    private List<QuickAction> quickActions(Snapshot value) {
        String root = "/projects/" + value.project().getId();
        return List.of(
                new QuickAction("REQUIREMENTS", "\u6559\u5b66\u9700\u6c42", root + "/requirements", true),
                new QuickAction("MATERIALS", "\u8d44\u6599\u4e2d\u5fc3", root + "/materials", requirementsComplete(value)),
                new QuickAction("OUTLINE", "\u8bfe\u7a0b\u5927\u7eb2", root + "/outline", materialsComplete(value)),
                new QuickAction("LESSON_PLAN", "\u6559\u6848\u8bbe\u8ba1", root + "/lesson-plan", outlineComplete(value)),
                new QuickAction("PPT", "PPT\u6210\u679c", root + "/ppt", lessonPlanComplete(value))
        );
    }

    private RequirementCompleteness completeness(Project project, RequirementInput requirement) {
        String topic = firstNonBlank(requirement == null ? null : requirement.getTopic(), project.getChapterTopic());
        String audience = firstNonBlank(requirement == null ? null : requirement.getGradeLevel(), project.getTargetAudience());
        String duration = firstNonBlank(
                requirement == null ? null : requirement.getLessonDuration(),
                lessonDurationLabel(project.getLessonDurationMinutes())
        );
        String keyDifficulties = requirement == null ? null : firstNonBlank(
                requirement.getDifficultPoints(),
                requirement.getKeyPoints()
        );
        String outputs = requirement == null ? null : String.join("、", requirement.getOutputTypes());
        List<RequirementFieldState> fields = List.of(
                field("topic", "课程主题", topic),
                field("teachingGoals", "教学目标", requirement == null ? null : requirement.getTeachingGoals()),
                field("gradeLevel", "授课对象", audience),
                field("baselineLevel", "基础水平", requirement == null ? null : requirement.getBaselineLevel()),
                field("lessonDuration", "课时长度", duration),
                field("difficultPoints", "重点难点", keyDifficulties),
                field("stylePreference", "教学风格", requirement == null ? null : requirement.getStylePreference()),
                field("interactionType", "互动设计", requirement == null ? null : requirement.getInteractionType()),
                field("outputTypes", "输出内容", outputs)
        );
        List<RequirementFieldState> requiredFields = ClarificationField.REQUIRED_FIELDS.stream()
                .map(field -> fields.stream()
                        .filter(state -> state.code().equals(field.code()))
                        .findFirst()
                        .orElseThrow())
                .toList();
        int collected = (int) requiredFields.stream().filter(RequirementFieldState::completed).count();
        return new RequirementCompleteness(
                collected,
                ClarificationField.REQUIRED_FIELDS.size(),
                collected * 100 / ClarificationField.REQUIRED_FIELDS.size(),
                collected == ClarificationField.REQUIRED_FIELDS.size(),
                requiredFields
        );
    }

    private RequirementInputView requirementView(RequirementInput requirement) {
        if (requirement == null) return null;
        return new RequirementInputView(
                requirement.getId(), requirement.getProjectId(), requirement.getGradeLevel(), requirement.getSubject(),
                requirement.getTopic(), requirement.getBaselineLevel(), requirement.getLessonDuration(),
                requirement.getTeachingGoals(), requirement.getKeyPoints(), requirement.getDifficultPoints(),
                requirement.getStylePreference(), requirement.getInteractionType(), requirement.getOutputTypes(),
                requirement.getRawRequirementText(), requirement.getCreatedAt(), requirement.getUpdatedAt()
        );
    }

    private DialogMessageView dialogView(DialogMessage message) {
        String sender = message.getRole() == null ? null : message.getRole().name();
        if ("ASSISTANT".equals(sender)) sender = "AI";
        return new DialogMessageView(
                message.getId(), message.getSessionId(), sender, message.getContent(), message.getRoundNo(), message.getCreatedAt()
        );
    }

    private RequirementSummaryView summaryView(RequirementSummary summary) {
        if (summary == null) return null;
        return new RequirementSummaryView(
                summary.getId(), summary.getSourceRequirementId(), summary.getGradeLevel(), summary.getSubject(),
                summary.getTopic(), summary.getBaselineLevel(), summary.getLessonDuration(), summary.getTeachingGoals(),
                summary.getKeyPoints(), summary.getDifficultPoints(), summary.getOutputTypes(), summary.getStylePreference(),
                summary.getInteractionType(), normalizeMode(summary.getGenerationMode()), summary.getStatus(),
                summary.getCreatedAt(), summary.getUpdatedAt(), summary.getConfirmedAt()
        );
    }

    private MaterialItem materialItem(UploadedMaterial material) {
        List<MaterialPurpose> purposes = purposeRepository.findByMaterialIdOrderByIdAsc(material.getId());
        ParseResult parseResult = parseResultRepository.findFirstByMaterialIdOrderByCreatedAtDescIdDesc(material.getId()).orElse(null);
        long sectionCount = parseResult == null ? 0L
                : parseResultRepository.countSectionsByParseResultId(parseResult.getId());
        List<String> sectionsPreview = parseResult == null ? List.of()
                : parseResultRepository.findSectionsPreviewByParseResultId(parseResult.getId());
        ParsePreview preview = parseResult == null ? null : new ParsePreview(
                parseResult.getId(), parseResult.getParseStatus(), parseResult.getSummary(), parseResult.getKeywords(),
                parseResult.getApplicableTeachingStages(), parseResult.getFailureReason(), parseResult.getParsedAt(), true,
                previewText(parseResult.getExtractedText()), parseResult.getPageCount(), Math.toIntExact(sectionCount), sectionsPreview,
                parseResult.getChunkCount(), parseResult.getParseDurationMs()
        );
        return new MaterialItem(
                material.getId(), material.getOriginalFileName(), material.getFileExtension(), material.getFileType(),
                material.getContentType(), material.getFileSize() == null ? 0 : material.getFileSize(),
                material.getMaterialDescription(), normalizedParseStatus(material),
                purposes.stream().map(MaterialPurpose::getPurposeType).distinct().toList(),
                purposes.isEmpty() ? null : purposes.get(0).getPurposeDescription(),
                material.getCreatedAt(),
                "/api/projects/" + material.getProjectId() + "/materials/" + material.getId() + "/download",
                preview
        );
    }

    private List<PurposeOption> purposeOptions() {
        return MaterialLabels.SUPPORTED_USAGES.stream().map(type -> new PurposeOption(
                type,
                MaterialLabels.usageLabel(type),
                purposeDescription(type)
        )).toList();
    }

    private TeachingIntentView intentView(TeachingIntent intent, Project project) {
        if (intent == null) return null;
        List<String> goals = intent.getGenerationGoals().isEmpty()
                ? splitLegacyValues(intent.getGenerationGoal())
                : intent.getGenerationGoals();
        String primaryBasis = firstNonBlank(intent.getPrimaryBasis(), intent.getContentBasis());
        List<String> supplementalBasis = intent.getSupplementalBasis().isEmpty()
                ? intent.getEvidenceItems().stream()
                        .map(TeachingIntentEvidence::getSourceFilename)
                        .filter(WorkspaceService::hasText)
                        .distinct()
                        .toList()
                : intent.getSupplementalBasis();
        String audience = firstNonBlank(intent.getTargetAudience(), project.getTargetAudience());
        Integer totalHours = intent.getTotalHours() == null ? hoursForMinutes(project.getLessonDurationMinutes()) : intent.getTotalHours();
        String format = firstNonBlank(intent.getTeachingFormat(), intent.getInteractionMode());
        return new TeachingIntentView(
                intent.getId(), intent.getRequirementSummaryId(), goals, intent.getGenerationGoal(), primaryBasis,
                supplementalBasis, intent.getContentBasis(), audience, totalHours, format,
                intent.getTeachingApproach(), intent.getInteractionMode(), intent.getOutputTypes(), intent.getStylePreference(),
                intent.getNotes(), intent.getEvidenceItems().stream().map(this::evidenceView).toList(), intent.getStatus(),
                intent.getCreatedAt(), intent.getUpdatedAt(), intent.getConfirmedAt()
        );
    }

    private IntentEvidenceView evidenceView(TeachingIntentEvidence evidence) {
        return new IntentEvidenceView(
                evidence.getMaterialId(), evidence.getKnowledgeChunkId(), evidence.getSourceFilename(),
                parseUsages(evidence.getUsageTypes()), evidence.getHitReason(), evidence.getContentExcerpt()
        );
    }

    private static Comparator<ProjectBrief> projectComparator(String sort) {
        return switch (sort) {
            case "UPDATED_ASC" -> Comparator.comparing(ProjectBrief::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder()));
            case "PROGRESS_DESC" -> Comparator.comparingInt(ProjectBrief::progress).reversed()
                    .thenComparing(ProjectBrief::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
            case "NAME_ASC" -> Comparator.comparing(ProjectBrief::projectName, Comparator.nullsLast(String::compareToIgnoreCase));
            default -> Comparator.comparing(ProjectBrief::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        };
    }

    private static String normalizeSort(String sort) {
        String value = trimToNull(sort);
        if (value == null) return "UPDATED_DESC";
        value = value.toUpperCase(Locale.ROOT);
        if (!List.of("UPDATED_DESC", "UPDATED_ASC", "PROGRESS_DESC", "NAME_ASC").contains(value)) {
            throw new BadRequestException("Unsupported sort: " + sort);
        }
        return value;
    }

    private static boolean matchesQuery(ProjectBrief project, String query) {
        if (query == null) return true;
        String value = query.toLowerCase(Locale.ROOT);
        return List.of(project.projectName(), project.courseName(), project.chapterTitle(), project.targetStudents(), project.subtitle())
                .stream()
                .filter(Objects::nonNull)
                .map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(item -> item.contains(value));
    }

    private static boolean matchesStage(ProjectBrief project, String stage) {
        String expected = normalizeStageFilter(stage);
        if (expected == null) return true;
        return normalizeProjectStage(project.stage()).equals(expected)
                || normalizeProjectStage(project.status().name()).equals(expected);
    }

    private String previewText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.substring(0, Math.min(text.length(), 2000));
    }

    private static String normalizeStageFilter(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "ALL", "\u5168\u90e8\u72b6\u6001" -> null;
            case "REQUIREMENTS", "REQUIREMENT", "REQUIREMENT_CLARIFYING", "REQUIREMENT_CONFIRMED",
                    "\u9700\u6c42\u6f84\u6e05\u4e2d" -> "REQUIREMENTS";
            case "MATERIALS", "MATERIAL_ANALYZING", "\u8d44\u6599\u89e3\u6790\u4e2d" -> "MATERIALS";
            case "OUTLINE", "INTENT_CONFIRMED", "KNOWLEDGE_INDEXED", "\u610f\u56fe\u5df2\u786e\u8ba4" -> "OUTLINE";
            case "PPT", "CONTENT_GENERATED", "FINALIZED", "\u5df2\u5b9a\u7a3f" -> "PPT";
            default -> normalized.toUpperCase(Locale.ROOT);
        };
    }

    private static String normalizeProjectStage(String value) {
        return normalizeStageFilter(value);
    }

    private static RequirementFieldState field(String code, String label, String value) {
        String normalized = trimToNull(value);
        return new RequirementFieldState(code, label, normalized, normalized != null);
    }

    private static String questionFor(String field) {
        return switch (field) {
            case "topic" -> "请补充本次课程的具体主题。";
            case "teachingGoals" -> "本次课程希望学生掌握哪些具体能力？";
            case "audience" -> "请说明授课对象、年级或专业背景。";
            case "baselineLevel" -> "学生目前具备哪些基础知识或技能？";
            case "lessonDuration" -> "本次课程计划安排多少课时或分钟？";
            case "keyDifficulties" -> "请补充本课的重点与难点。";
            case "stylePreference" -> "希望采用怎样的教学风格？";
            case "interactionType" -> "是否需要课堂问答、投票或其他互动？";
            case "outputTypes" -> "需要生成哪些教学成果？";
            default -> "请补充" + field + "。";
        };
    }

    private static boolean summaryConfirmable(RequirementSummary summary) {
        return hasText(summary.getGradeLevel())
                && hasText(summary.getSubject())
                && hasText(summary.getTopic())
                && hasText(summary.getLessonDuration())
                && hasText(summary.getTeachingGoals())
                && !summary.getOutputTypes().isEmpty();
    }

    private static boolean intentConfirmable(TeachingIntent intent) {
        return hasText(intent.getGenerationGoal())
                && hasText(intent.getContentBasis())
                && hasText(intent.getTeachingApproach())
                && hasText(intent.getInteractionMode())
                && !intent.getOutputTypes().isEmpty()
                && !intent.getEvidenceItems().isEmpty();
    }

    private static boolean isConfirmed(TeachingIntent intent) {
        return intent != null && intent.getStatus() == TeachingIntentStatus.CONFIRMED;
    }

    private static long parsedMaterialCount(Snapshot value) {
        return value.materials().stream().filter(item -> item.getParseStatus() == MaterialParseStatus.SUCCEEDED).count();
    }

    private static long indexedMaterialCount(Snapshot value) {
        return value.chunks().stream().map(KnowledgeChunk::getMaterialId).filter(Objects::nonNull).distinct().count();
    }

    private static MaterialParseStatus normalizedParseStatus(UploadedMaterial material) {
        return material.getParseStatus() == null ? MaterialParseStatus.NOT_STARTED : material.getParseStatus();
    }

    private static String parseStatusLabel(UploadedMaterial material) {
        return switch (normalizedParseStatus(material)) {
            case NOT_STARTED -> "待解析";
            case PROCESSING -> "解析中";
            case SUCCEEDED -> "解析完成";
            case FAILED -> "解析失败";
        };
    }

    private static String stageLabel(String stage) {
        return switch (stage) {
            case "REQUIREMENTS" -> "\u9700\u6c42\u6f84\u6e05\u4e2d";
            case "MATERIALS" -> "\u8d44\u6599\u89e3\u6790\u4e2d";
            case "OUTLINE" -> "\u610f\u56fe\u5df2\u786e\u8ba4";
            case "LESSON_PLAN" -> "\u6559\u6848\u5df2\u751f\u6210";
            case "PPT" -> "\u5185\u5bb9\u5df2\u751f\u6210";
            case "REQUIREMENT_CLARIFYING" -> "需求澄清中";
            case "REQUIREMENT_CONFIRMED" -> "需求已确认";
            case "MATERIAL_ANALYZING" -> "资料解析中";
            case "KNOWLEDGE_INDEXED" -> "知识库已增强";
            case "INTENT_CONFIRMED" -> "意图已确认";
            case "CONTENT_GENERATED" -> "内容已生成";
            case "FINALIZED" -> "已定稿";
            default -> stage;
        };
    }

    private static String purposeDescription(PurposeType type) {
        return switch (type) {
            case TEXTBOOK_BASIS -> "作为课程内容的理论依据或知识来源";
            case CASE_MATERIAL -> "用于案例教学、课堂讨论或情境分析";
            case EXERCISE_SOURCE -> "用于习题、测评和课堂练习设计";
            case KNOWLEDGE_SUPPLEMENT -> "扩展知识、背景信息或延伸阅读";
            case IMAGE_ASSET -> "用于图示说明、课件展示或视觉辅助";
            default -> MaterialLabels.usageLabel(type);
        };
    }

    private static String normalizeMode(GenerationMode mode) {
        if (mode == null) return "STANDARD";
        return mode == GenerationMode.HIGH_QUALITY ? "QUALITY" : mode.name();
    }

    private static String lessonDurationLabel(Integer minutes) {
        if (minutes == null || minutes <= 0) return null;
        int hours = (int) Math.ceil(minutes / 45.0);
        return hours + " 课时（" + minutes + " 分钟）";
    }

    private static Integer hoursForMinutes(Integer minutes) {
        if (minutes == null || minutes <= 0) return null;
        return (int) Math.ceil(minutes / 45.0);
    }

    private static String joinNonBlank(String first, String second) {
        List<String> values = new ArrayList<>();
        String normalizedFirst = trimToNull(first);
        String normalizedSecond = trimToNull(second);
        if (normalizedFirst != null) values.add(normalizedFirst);
        if (normalizedSecond != null) values.add(normalizedSecond);
        return values.isEmpty() ? null : String.join("；", values);
    }

    private static String buildContentBasis(String primaryBasis, List<String> supplementalBasis) {
        if (supplementalBasis.isEmpty()) return primaryBasis;
        return primaryBasis + "；补充依据：" + String.join("、", supplementalBasis);
    }

    private static String intentGoalDescription(TeachingIntent intent) {
        List<String> labels = intent.getGenerationGoals().stream()
                .map(code -> GENERATION_GOAL_OPTIONS.stream()
                        .filter(option -> option.code().equals(code))
                        .map(IntentOption::label)
                        .findFirst()
                        .orElse(code))
                .toList();
        String value = labels.isEmpty() ? intent.getGenerationGoal() : String.join("；", labels);
        return abbreviate(value, 80);
    }

    private static List<String> normalizeValues(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        values.stream().map(WorkspaceService::trimToNull).filter(Objects::nonNull).forEach(normalized::add);
        return List.copyOf(normalized);
    }

    private static List<String> splitLegacyValues(String value) {
        if (!hasText(value)) return List.of();
        return Arrays.stream(value.split("[；;\\n]+"))
                .map(WorkspaceService::trimToNull)
                .filter(Objects::nonNull)
                .toList();
    }

    private static List<PurposeType> parseUsages(String value) {
        if (!hasText(value)) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(PurposeType::valueOf)
                .toList();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) return normalized;
        }
        return null;
    }

    private static LocalDateTime firstNonNull(LocalDateTime preferred, LocalDateTime fallback) {
        return preferred == null ? fallback : preferred;
    }

    private static String abbreviate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit) + "…";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Snapshot(
            Project project,
            RequirementInput requirement,
            RequirementSummary summary,
            List<UploadedMaterial> materials,
            List<KnowledgeChunk> chunks,
            TeachingIntent intent,
            List<GeneratedArtifact> artifacts,
            List<ArtifactVersion> versions,
            List<ExportRecord> exports
    ) {
    }

    private record StepValue(String code, String label, boolean completed, LocalDateTime completedAt) {
    }
}
