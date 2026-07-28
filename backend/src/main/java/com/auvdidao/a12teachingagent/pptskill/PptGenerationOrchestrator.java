package com.auvdidao.a12teachingagent.pptskill;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.GenerationPlan;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PptGenerationOrchestrator {

    private static final String PRESENTATION_SKILL = "PRESENTATION_SKILL";
    private static final String LEGACY = "LEGACY";
    private static final String AUTOMATED_GEOMETRY_ONLY = "AUTOMATED_GEOMETRY_ONLY";

    private final ProjectRepository projectRepository;
    private final GenerationPlanRepository planRepository;
    private final ArtifactVersionRepository versionRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final ProjectAccessService projectAccessService;
    private final PptOutlineProviderRouter outlineProviderRouter;
    private final PptSkillRunnerClient runnerClient;
    private final PptSkillFileStore fileStore;
    private final PptGeneratorProperties properties;
    private final ObjectMapper objectMapper;

    public PptGenerationOrchestrator(
            ProjectRepository projectRepository,
            GenerationPlanRepository planRepository,
            ArtifactVersionRepository versionRepository,
            GeneratedArtifactRepository artifactRepository,
            ProjectAccessService projectAccessService,
            PptOutlineProviderRouter outlineProviderRouter,
            PptSkillRunnerClient runnerClient,
            PptSkillFileStore fileStore,
            PptGeneratorProperties properties,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.planRepository = planRepository;
        this.versionRepository = versionRepository;
        this.artifactRepository = artifactRepository;
        this.projectAccessService = projectAccessService;
        this.outlineProviderRouter = outlineProviderRouter;
        this.runnerClient = runnerClient;
        this.fileStore = fileStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PptGenerationDtos.GenerationResponse generate(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .filter(item -> item.getDeletedAt() == null)
                .orElseThrow(() -> new PptSkillGenerationException("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND));
        projectAccessService.requireAccess(project);

        String provider = upper(properties.getProvider());
        if (LEGACY.equals(provider)) {
            throw new PptSkillGenerationException(
                    "LEGACY_RENDER_FAILED",
                    "PPT generation is configured for the legacy renderer; use the existing artifact generation endpoint",
                    HttpStatus.CONFLICT
            );
        }
        if (!PRESENTATION_SKILL.equals(provider)) {
            throw new PptSkillGenerationException("RUNNER_UNAVAILABLE", "PPT generator provider is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        JsonNode outline = outlineProviderRouter.getOutline(project);
        PptSkillRunnerDtos.RunnerResult runner = runnerClient.generate(outline, properties.getStylePreset());
        validateRunnerResult(runner);

        String actualHash = sha256(runner.pptx());
        if (!Objects.equals(actualHash, runner.sha256())) {
            throw new PptSkillGenerationException("PPT_HASH_MISMATCH", "Generated PPTX integrity check failed", HttpStatus.BAD_GATEWAY);
        }

        Path storedFile = fileStore.save(projectId, runner.pptx());
        try {
            GenerationPlan plan = planRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId).orElse(null);
            int versionNumber = versionRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                    .map(ArtifactVersion::getVersionNumber)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(0) + 1;

            ArtifactVersion version = new ArtifactVersion();
            version.setProjectId(projectId);
            version.setGenerationPlanId(plan == null ? null : plan.getId());
            version.setVersionNumber(versionNumber);
            version.setDescription("Presentation-skill PPTX generated with automated geometry QA");
            version.setFinalVersion(false);
            version = versionRepository.save(version);

            GeneratedArtifact artifact = new GeneratedArtifact();
            artifact.setProjectId(projectId);
            artifact.setGenerationPlanId(plan == null ? null : plan.getId());
            artifact.setVersionId(version.getId());
            artifact.setArtifactType(ArtifactType.PPT);
            artifact.setTitle(project.getProjectName() + " PPTX");
            artifact.setSchemaVersion(1);
            artifact.setContentJson(metadataJson(runner, actualHash));
            artifact.setFilePath(storedFile.toString());
            artifact = artifactRepository.save(artifact);

            if (project.getStatus() != ProjectStatus.FINALIZED) {
                project.setStatus(ProjectStatus.GENERATED);
                projectRepository.save(project);
            }
            return new PptGenerationDtos.GenerationResponse(
                    projectId,
                    artifact.getId(),
                    version.getId(),
                    versionNumber,
                    PRESENTATION_SKILL,
                    runner.jobId(),
                    "presentation.pptx",
                    runner.pptx().length,
                    actualHash,
                    runner.qa().qaLevel(),
                    true,
                    runner.buildDurationMs(),
                    runner.qaDurationMs(),
                    runner.totalDurationMs(),
                    "/api/v1/projects/" + projectId + "/exports/pptx"
            );
        } catch (RuntimeException exception) {
            fileStore.deleteQuietly(storedFile);
            throw exception;
        }
    }

    private void validateRunnerResult(PptSkillRunnerDtos.RunnerResult runner) {
        if (runner == null || !"SUCCEEDED".equals(runner.status())) {
            throw new PptSkillGenerationException("PPT_BUILD_FAILED", "PPT generation did not succeed", HttpStatus.BAD_GATEWAY);
        }
        if (runner.pptx() == null || runner.pptx().length == 0 || runner.sizeBytes() <= 0) {
            throw new PptSkillGenerationException("PPT_EMPTY_FILE", "Generated PPTX is empty", HttpStatus.BAD_GATEWAY);
        }
        if (runner.sizeBytes() != runner.pptx().length) {
            throw new PptSkillGenerationException("PPT_BUILD_FAILED", "Generated PPTX size metadata is inconsistent", HttpStatus.BAD_GATEWAY);
        }
        if (runner.qa() == null || !runner.qa().passed()) {
            throw new PptSkillGenerationException("PPT_QA_FAILED", "PPT quality gate failed", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!AUTOMATED_GEOMETRY_ONLY.equals(runner.qa().qaLevel())) {
            throw new PptSkillGenerationException("UNSUPPORTED_QA_LEVEL", "PPT QA level is not supported for delivery", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private String metadataJson(PptSkillRunnerDtos.RunnerResult runner, String actualHash) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generator", PRESENTATION_SKILL);
        metadata.put("outlineProvider", upper(properties.getOutlineProvider()));
        if ("KIMI".equals(upper(properties.getOutlineProvider()))) {
            metadata.put("outlineModel", properties.getKimiModel());
        }
        metadata.put("runnerJobId", runner.jobId());
        metadata.put("fileName", "presentation.pptx");
        metadata.put("sizeBytes", runner.pptx().length);
        metadata.put("sha256", actualHash);
        metadata.put("qaLevel", runner.qa().qaLevel());
        metadata.put("qaPassed", runner.qa().passed());
        metadata.put("qaReport", runner.qaReportJson());
        metadata.put("outline", runner.outlineJson());
        metadata.put("buildDurationMs", runner.buildDurationMs());
        metadata.put("qaDurationMs", runner.qaDurationMs());
        metadata.put("totalDurationMs", runner.totalDurationMs());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new PptSkillGenerationException("PPT_BUILD_FAILED", "PPT metadata could not be saved", HttpStatus.INTERNAL_SERVER_ERROR, exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
