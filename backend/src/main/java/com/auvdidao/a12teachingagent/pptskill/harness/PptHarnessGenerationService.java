package com.auvdidao.a12teachingagent.pptskill.harness;

import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.GenerationPlan;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntentEvidence;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.pptskill.PptSkillFileStore;
import com.auvdidao.a12teachingagent.pptskill.PptSkillGenerationException;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Spring owns authorization and final artifact persistence; Harness owns task execution and QA. */
@Service
public class PptHarnessGenerationService {
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String AUTOMATED_GEOMETRY_ONLY = "AUTOMATED_GEOMETRY_ONLY";
    private static final int MAX_EVIDENCE_ITEMS = 20;
    private static final int MAX_EVIDENCE_TEXT_CHARS = 4_000;
    private static final int MAX_TOTAL_EVIDENCE_CHARS = 24_000;

    private final ProjectRepository projectRepository;
    private final TeachingIntentRepository intentRepository;
    private final GenerationPlanRepository planRepository;
    private final RequirementSummaryRepository requirementSummaryRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final UploadedMaterialRepository uploadedMaterialRepository;
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
                                       PptSkillFileStore fileStore, ObjectMapper objectMapper,
                                       RequirementSummaryRepository requirementSummaryRepository,
                                       KnowledgeChunkRepository knowledgeChunkRepository,
                                       UploadedMaterialRepository uploadedMaterialRepository) {
        this.projectRepository = projectRepository;
        this.intentRepository = intentRepository;
        this.planRepository = planRepository;
        this.requirementSummaryRepository = requirementSummaryRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.uploadedMaterialRepository = uploadedMaterialRepository;
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
        PptHarnessJobSnapshot snapshot = buildJobSnapshot(project, template);
        return harnessClient.start(new PptHarnessDtos.StartRequest(
                UUID.randomUUID().toString(), projectId, template.templateId(), template.templateVersion(), "zh-CN",
                snapshot.generationPreferences().targetSlideCount(), snapshot
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

    private PptHarnessJobSnapshot buildJobSnapshot(Project project, PptTemplateSelectionService.Selection template) {
        Long projectId = project.getId();
        RequirementSummary summary = requirementSummaryRepository
                .findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(projectId, RequirementSummaryStatus.CONFIRMED)
                .filter(item -> Objects.equals(projectId, item.getProjectId()))
                .orElseThrow(() -> contractConflict("PPT_REQUIREMENT_NOT_CONFIRMED", "A confirmed requirement summary is required before PPT generation"));
        TeachingIntent intent = intentRepository
                .findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(projectId, TeachingIntentStatus.CONFIRMED)
                .filter(item -> Objects.equals(projectId, item.getProjectId()))
                .orElseThrow(() -> contractConflict("PPT_INTENT_NOT_CONFIRMED", "A confirmed teaching intent is required before PPT generation"));
        GenerationPlan plan = planRepository.findFirstByProjectIdAndConfirmedTrueOrderByCreatedAtDescIdDesc(projectId)
                .filter(item -> Objects.equals(projectId, item.getProjectId()))
                .orElseThrow(() -> contractConflict("PPT_PLAN_NOT_CONFIRMED", "A confirmed generation plan is required before PPT generation"));

        List<PptHarnessJobSnapshot.PptOutlineSection> outline = readPptOutline(plan.getPptOutline());
        int lessonDuration = firstPositive(parseMinutes(summary.getLessonDuration()), project.getLessonDurationMinutes(),
                intent.getTotalHours() == null ? null : intent.getTotalHours() * 60, 45);
        int targetSlideCount = new PptSlideCountPolicy().calculate(
                null,
                outline.size(),
                lessonDuration,
                countItems(summary.getTeachingGoals()),
                outline.stream().mapToInt(section -> section.points().size()).sum()
        );
        String style = firstNonBlank(intent.getStylePreference(), summary.getStylePreference(), "clear classroom");
        String density = lessonDuration <= 20 ? "compact" : lessonDuration >= 90 ? "expanded" : "standard";

        return new PptHarnessJobSnapshot(
                new PptHarnessJobSnapshot.ProjectSnapshot(
                        projectId,
                        project.getProjectName(),
                        project.getCourseName(),
                        project.getChapterTopic(),
                        project.getTargetAudience(),
                        project.getLessonDurationMinutes(),
                        project.getGenerationMode() == null ? null : project.getGenerationMode().name()
                ),
                new PptHarnessJobSnapshot.RequirementSummarySnapshot(
                        summary.getId(),
                        summary.getSubject(),
                        firstNonBlank(summary.getSubject(), project.getCourseName()),
                        firstNonBlank(summary.getTopic(), project.getChapterTopic()),
                        summary.getGradeLevel(),
                        firstNonBlank(summary.getGradeLevel(), project.getTargetAudience()),
                        summary.getLessonDuration(),
                        summary.getTeachingGoals(),
                        summary.getKeyPoints(),
                        summary.getDifficultPoints(),
                        summary.getStylePreference(),
                        summary.getInteractionType(),
                        summary.getOutputTypes()
                ),
                new PptHarnessJobSnapshot.TeachingIntentSnapshot(
                        intent.getId(),
                        intent.getGenerationGoal(),
                        intent.getGenerationGoals(),
                        intent.getContentBasis(),
                        intent.getPrimaryBasis(),
                        intent.getSupplementalBasis(),
                        firstNonBlank(intent.getTargetAudience(), summary.getGradeLevel(), project.getTargetAudience()),
                        intent.getTotalHours(),
                        intent.getTeachingApproach(),
                        intent.getInteractionMode(),
                        intent.getTeachingFormat(),
                        intent.getOutputTypes(),
                        intent.getStylePreference(),
                        intent.getNotes()
                ),
                new PptHarnessJobSnapshot.GenerationPlanSnapshot(
                        plan.getId(),
                        plan.getProvider(),
                        outline,
                        readStringList(plan.getInteractionPlan())
                ),
                boundedEvidence(projectId, intent),
                new PptHarnessDtos.TemplateSelection(template.templateId(), template.templateVersion()),
                new PptHarnessDtos.GenerationPreferences("zh-CN", style, density, targetSlideCount)
        );
    }

    private List<PptHarnessDtos.MaterialEvidence> boundedEvidence(Long projectId, TeachingIntent intent) {
        List<PptHarnessDtos.MaterialEvidence> result = new ArrayList<>();
        int totalChars = 0;
        List<TeachingIntentEvidence> grounded = intent.getEvidenceItems();
        if (!grounded.isEmpty()) {
            for (TeachingIntentEvidence evidence : grounded) {
                PptHarnessDtos.MaterialEvidence item = evidenceSnapshot(projectId, evidence);
                int next = item == null ? totalChars : totalChars + item.text().length();
                if (item == null || result.size() >= MAX_EVIDENCE_ITEMS || next > MAX_TOTAL_EVIDENCE_CHARS) continue;
                result.add(item);
                totalChars = next;
            }
            return List.copyOf(result);
        }

        if (knowledgeChunkRepository == null) return List.of();
        for (KnowledgeChunk chunk : knowledgeChunkRepository.findByProjectIdOrderByMaterialIdAscChunkNoAsc(projectId)) {
            PptHarnessDtos.MaterialEvidence item = evidenceSnapshot(projectId, chunk);
            int next = item == null ? totalChars : totalChars + item.text().length();
            if (item == null || result.size() >= MAX_EVIDENCE_ITEMS || next > MAX_TOTAL_EVIDENCE_CHARS) continue;
            result.add(item);
            totalChars = next;
        }
        return List.copyOf(result);
    }

    private PptHarnessDtos.MaterialEvidence evidenceSnapshot(Long projectId, TeachingIntentEvidence evidence) {
        Optional<KnowledgeChunk> rawChunk = evidence.getKnowledgeChunkId() == null || knowledgeChunkRepository == null
                ? Optional.empty()
                : knowledgeChunkRepository.findById(evidence.getKnowledgeChunkId());
        if (rawChunk.isPresent() && !Objects.equals(projectId, rawChunk.get().getProjectId())) return null;
        KnowledgeChunk chunk = rawChunk.orElse(null);
        Optional<UploadedMaterial> material = material(evidence.getMaterialId());
        if (material.isPresent() && !Objects.equals(projectId, material.get().getProjectId())) return null;
        String text = clamp(firstNonBlank(evidence.getContentExcerpt(), chunk == null ? null : chunk.getContent()), MAX_EVIDENCE_TEXT_CHARS);
        if (text.isBlank()) return null;
        return new PptHarnessDtos.MaterialEvidence(
                firstNonNull(evidence.getMaterialId(), chunk == null ? null : chunk.getMaterialId()),
                firstNonBlank(evidence.getSourceFilename(), chunk == null ? null : chunk.getSourceFilename(), materialName(material.orElse(null))),
                evidence.getKnowledgeChunkId(),
                chunk == null ? null : chunk.getChunkNo(),
                text,
                null,
                firstNonBlank(evidence.getHitReason(), "confirmed teaching intent evidence"),
                chunk == null ? null : chunk.getTitle(),
                chunk == null ? null : chunk.getTitle()
        );
    }

    private PptHarnessDtos.MaterialEvidence evidenceSnapshot(Long projectId, KnowledgeChunk chunk) {
        if (chunk == null || !Objects.equals(projectId, chunk.getProjectId())) return null;
        Optional<UploadedMaterial> material = material(chunk.getMaterialId());
        if (material.isPresent() && !Objects.equals(projectId, material.get().getProjectId())) return null;
        String text = clamp(chunk.getContent(), MAX_EVIDENCE_TEXT_CHARS);
        if (text.isBlank()) return null;
        return new PptHarnessDtos.MaterialEvidence(
                chunk.getMaterialId(),
                firstNonBlank(chunk.getSourceFilename(), materialName(material.orElse(null))),
                chunk.getId(),
                chunk.getChunkNo(),
                text,
                null,
                "project-scoped knowledge chunk",
                chunk.getTitle(),
                chunk.getTitle()
        );
    }

    private Optional<UploadedMaterial> material(Long materialId) {
        return materialId == null || uploadedMaterialRepository == null
                ? Optional.empty()
                : uploadedMaterialRepository.findById(materialId);
    }

    private List<PptHarnessJobSnapshot.PptOutlineSection> readPptOutline(String json) {
        if (blank(json)) throw contractConflict("PPT_PLAN_INVALID", "Confirmed PPT outline is empty");
        try {
            JsonNode value = objectMapper.readTree(json);
            if (!value.isArray() || value.isEmpty()) throw contractConflict("PPT_PLAN_INVALID", "Confirmed PPT outline must be a non-empty array");
            List<PptHarnessJobSnapshot.PptOutlineSection> result = new ArrayList<>();
            int fallbackOrder = 1;
            for (JsonNode section : value) {
                String title = section.path("title").asText("").trim();
                String description = section.path("description").asText("").trim();
                if (title.isBlank() || description.isBlank()) throw contractConflict("PPT_PLAN_INVALID", "Confirmed PPT outline contains an incomplete section");
                List<String> points = new ArrayList<>();
                if (section.path("points").isArray()) section.path("points").forEach(point -> { if (!point.asText("").isBlank()) points.add(point.asText().trim()); });
                if (points.isEmpty()) points.add(description);
                result.add(new PptHarnessJobSnapshot.PptOutlineSection(
                        section.path("order").isNumber() ? section.path("order").asInt() : fallbackOrder,
                        title,
                        description,
                        points,
                        textOrNull(section.path("materialReference"))
                ));
                fallbackOrder++;
            }
            return List.copyOf(result);
        } catch (PptSkillGenerationException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new PptSkillGenerationException("PPT_PLAN_INVALID", "Confirmed PPT outline is not valid JSON", HttpStatus.CONFLICT, exception);
        }
    }

    private List<String> readStringList(String json) {
        if (blank(json)) return List.of();
        try {
            JsonNode value = objectMapper.readTree(json);
            if (!value.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            value.forEach(item -> { if (!item.asText("").isBlank()) result.add(item.asText().trim()); });
            return List.copyOf(result);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private PptSkillGenerationException contractConflict(String code, String message) {
        return new PptSkillGenerationException(code, message, HttpStatus.CONFLICT);
    }

    private static int parseMinutes(String value) {
        if (value == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    private static int firstPositive(Integer... values) {
        for (Integer value : values) if (value != null && value > 0) return value;
        return 45;
    }

    private static int countItems(String value) {
        if (blank(value)) return 0;
        return (int) java.util.Arrays.stream(value.split("[;\\n\\uFF1B\\u3002]"))
                .map(String::trim).filter(item -> !item.isBlank()).count();
    }

    private static String textOrNull(JsonNode value) {
        String text = value == null || value.isMissingNode() || value.isNull() ? null : value.asText(null);
        return blank(text) ? null : text.trim();
    }

    private static String clamp(String value, int max) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static Long firstNonNull(Long first, Long second) { return first != null ? first : second; }
    private static String materialName(UploadedMaterial material) {
        return material == null ? null : firstNonBlank(material.getOriginalFileName(), material.getFileName());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) return value.trim();
        }
        return "";
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
