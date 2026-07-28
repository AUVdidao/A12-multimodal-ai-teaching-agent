package com.auvdidao.a12teachingagent.pptskill.harness;

import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.GenerationPlan;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.pptskill.PptSkillFileStore;
import com.auvdidao.a12teachingagent.pptskill.PptSkillGenerationException;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Spring owns authorization and final artifact persistence; Harness owns task execution and QA. */
@Service
public class PptHarnessGenerationService {
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String AUTOMATED_GEOMETRY_ONLY = "AUTOMATED_GEOMETRY_ONLY";

    private final ProjectRepository projectRepository;
    private final TeachingIntentRepository intentRepository;
    private final GenerationPlanRepository planRepository;
    private final ArtifactVersionRepository versionRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final ProjectAccessService projectAccessService;
    private final PptTemplateSelectionService selectionService;
    private final PptHarnessClient harnessClient;
    private final PptSkillFileStore fileStore;
    private final ObjectMapper objectMapper;

    public PptHarnessGenerationService(ProjectRepository projectRepository, TeachingIntentRepository intentRepository,
                                       GenerationPlanRepository planRepository, ArtifactVersionRepository versionRepository,
                                       GeneratedArtifactRepository artifactRepository, ProjectAccessService projectAccessService,
                                       PptTemplateSelectionService selectionService, PptHarnessClient harnessClient,
                                       PptSkillFileStore fileStore, ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.intentRepository = intentRepository;
        this.planRepository = planRepository;
        this.versionRepository = versionRepository;
        this.artifactRepository = artifactRepository;
        this.projectAccessService = projectAccessService;
        this.selectionService = selectionService;
        this.harnessClient = harnessClient;
        this.fileStore = fileStore;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PptHarnessDtos.JobResponse start(Long projectId) {
        Project project = requireProject(projectId);
        PptTemplateSelectionService.Selection template = selectionService.get(projectId);
        return harnessClient.start(new PptHarnessDtos.StartRequest(
                UUID.randomUUID().toString(), projectId, template.templateId(), template.templateVersion(), "zh-CN", 9,
                requirementSnapshot(project)
        ));
    }

    @Transactional
    public PptHarnessDtos.JobResponse statusAndFinalize(Long projectId, String taskId) {
        Project project = requireProject(projectId);
        PptHarnessDtos.JobResponse job = harnessClient.get(taskId);
        if (job.projectId() != projectId) {
            throw new PptSkillGenerationException("HARNESS_TASK_INVALID", "PPT generation task does not belong to this project", HttpStatus.BAD_REQUEST);
        }
        if (!SUCCEEDED.equals(job.status())) return job;
        if (job.artifact() == null || job.artifact().sizeBytes() <= 0 || blank(job.artifact().sha256())) {
            throw new PptSkillGenerationException("PPT_EMPTY_FILE", "PPT harness completed without a valid artifact", HttpStatus.BAD_GATEWAY);
        }
        PptHarnessDtos.QaReport qa = harnessClient.qaReport(taskId);
        if (!qa.passed()) throw new PptSkillGenerationException("PPT_QA_FAILED", "PPT quality gate failed", HttpStatus.UNPROCESSABLE_ENTITY);
        if (!AUTOMATED_GEOMETRY_ONLY.equals(qa.qaLevel())) {
            throw new PptSkillGenerationException("UNSUPPORTED_QA_LEVEL", "PPT QA level is not supported for delivery", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        persistIfNeeded(project, job, qa);
        return job;
    }

    @Transactional
    public PptHarnessDtos.JobResponse status(Long projectId, String taskId) {
        return statusAndFinalize(projectId, taskId);
    }

    @Transactional(readOnly = true)
    public void requireTaskAccess(Long projectId, String taskId) {
        requireProject(projectId);
        PptHarnessDtos.JobResponse job = harnessClient.get(taskId);
        if (job.projectId() != projectId) {
            throw new PptSkillGenerationException("HARNESS_TASK_INVALID", "PPT generation task does not belong to this project", HttpStatus.BAD_REQUEST);
        }
    }

    private void persistIfNeeded(Project project, PptHarnessDtos.JobResponse job, PptHarnessDtos.QaReport qa) {
        List<GeneratedArtifact> existing = artifactRepository.findByProjectIdOrderByCreatedAtAsc(project.getId());
        if (existing.stream().anyMatch(artifact -> harnessTaskId(artifact).filter(job.taskId()::equals).isPresent())) return;

        byte[] bytes = harnessClient.download(job.taskId());
        if (bytes == null || bytes.length == 0) throw new PptSkillGenerationException("PPT_EMPTY_FILE", "PPT harness returned an empty file", HttpStatus.BAD_GATEWAY);
        String sha256 = sha256(bytes);
        if (!sha256.equalsIgnoreCase(job.artifact().sha256()) || bytes.length != job.artifact().sizeBytes()) {
            throw new PptSkillGenerationException("PPT_HASH_MISMATCH", "Generated PPTX integrity check failed", HttpStatus.BAD_GATEWAY);
        }
        Path stored = fileStore.save(project.getId(), bytes);
        try {
            GenerationPlan plan = planRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId()).orElse(null);
            int nextVersion = versionRepository.findByProjectIdOrderByCreatedAtAsc(project.getId()).stream()
                    .map(ArtifactVersion::getVersionNumber).filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
            ArtifactVersion version = new ArtifactVersion();
            version.setProjectId(project.getId());
            version.setGenerationPlanId(plan == null ? null : plan.getId());
            version.setVersionNumber(nextVersion);
            version.setDescription("PPT Harness task " + job.taskId() + " passed automated geometry QA");
            version.setFinalVersion(false);
            version = versionRepository.save(version);
            GeneratedArtifact artifact = new GeneratedArtifact();
            artifact.setProjectId(project.getId());
            artifact.setGenerationPlanId(plan == null ? null : plan.getId());
            artifact.setVersionId(version.getId());
            artifact.setArtifactType(ArtifactType.PPT);
            artifact.setTitle(project.getProjectName() + " PPTX");
            artifact.setSchemaVersion(1);
            artifact.setContentJson(metadata(job, qa, sha256));
            artifact.setFilePath(stored.toString());
            artifactRepository.save(artifact);
            if (project.getStatus() != ProjectStatus.FINALIZED) {
                project.setStatus(ProjectStatus.GENERATED);
                projectRepository.save(project);
            }
        } catch (RuntimeException exception) {
            fileStore.deleteQuietly(stored);
            throw exception;
        }
    }

    private Project requireProject(Long projectId) {
        Project project = projectRepository.findById(projectId).filter(item -> item.getDeletedAt() == null)
                .orElseThrow(() -> new PptSkillGenerationException("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND));
        projectAccessService.requireAccess(project);
        return project;
    }

    private JsonNode requirementSnapshot(Project project) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("projectName", project.getProjectName());
        snapshot.put("courseName", project.getCourseName());
        snapshot.put("chapterTopic", project.getChapterTopic());
        snapshot.put("targetAudience", project.getTargetAudience());
        if (project.getLessonDurationMinutes() != null) snapshot.put("lessonDurationMinutes", project.getLessonDurationMinutes());
        snapshot.put("projectDescription", project.getProjectDescription());
        intentRepository.findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(project.getId(), TeachingIntentStatus.CONFIRMED)
                .or(() -> intentRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId()))
                .ifPresent(intent -> addIntent(snapshot, intent));
        return snapshot;
    }

    private void addIntent(ObjectNode snapshot, TeachingIntent intent) {
        snapshot.put("teachingGoal", intent.getGenerationGoal());
        snapshot.put("teachingApproach", intent.getTeachingApproach());
        snapshot.put("interactionMode", intent.getInteractionMode());
        snapshot.put("stylePreference", intent.getStylePreference());
        ArrayNode goals = snapshot.putArray("generationGoals");
        intent.getGenerationGoals().forEach(goals::add);
        ArrayNode outputs = snapshot.putArray("outputTypes");
        intent.getOutputTypes().forEach(outputs::add);
    }

    private String metadata(PptHarnessDtos.JobResponse job, PptHarnessDtos.QaReport qa, String sha256) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("generator", "HARNESS");
        value.put("harnessTaskId", job.taskId());
        value.put("requestId", job.requestId());
        value.put("fileName", job.artifact().fileName());
        value.put("sizeBytes", job.artifact().sizeBytes());
        value.put("sha256", sha256);
        value.put("qaLevel", qa.qaLevel());
        value.put("qaPassed", qa.passed());
        value.put("qaReport", qa.report());
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new PptSkillGenerationException("PPT_BUILD_FAILED", "PPT metadata could not be saved", HttpStatus.INTERNAL_SERVER_ERROR, exception); }
    }

    private java.util.Optional<String> harnessTaskId(GeneratedArtifact artifact) {
        try { return java.util.Optional.ofNullable(objectMapper.readTree(artifact.getContentJson()).path("harnessTaskId").asText(null)); }
        catch (Exception ignored) { return java.util.Optional.empty(); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
}
