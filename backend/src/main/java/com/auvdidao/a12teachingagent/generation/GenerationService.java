package com.auvdidao.a12teachingagent.generation;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
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
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.ArtifactGenerationRequest;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.ArtifactResponse;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.GenerationCapabilities;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.GenerationPlanResponse;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.GenerationPlanUpdateRequest;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.GenerationWorkspaceResponse;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.PlanSection;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.TeachingIntentSummary;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class GenerationService {

    private static final int SCHEMA_VERSION = 1;
    private static final List<ArtifactType> ARTIFACT_TYPES = List.of(
            ArtifactType.PPT,
            ArtifactType.DOCX,
            ArtifactType.INTERACTION
    );
    private static final TypeReference<List<PlanSection>> PLAN_SECTION_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ProjectRepository projectRepository;
    private final TeachingIntentRepository intentRepository;
    private final GenerationPlanRepository planRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final ArtifactVersionRepository versionRepository;
    private final AIWorkflowGateway aiWorkflowGateway;
    private final MockArtifactContentFactory contentFactory;
    private final ObjectMapper objectMapper;
    private final ProjectAccessService projectAccessService;

    public GenerationService(
            ProjectRepository projectRepository,
            TeachingIntentRepository intentRepository,
            GenerationPlanRepository planRepository,
            GeneratedArtifactRepository artifactRepository,
            ArtifactVersionRepository versionRepository,
            AIWorkflowGateway aiWorkflowGateway,
            MockArtifactContentFactory contentFactory,
            ObjectMapper objectMapper,
            ProjectAccessService projectAccessService
    ) {
        this.projectRepository = projectRepository;
        this.intentRepository = intentRepository;
        this.planRepository = planRepository;
        this.artifactRepository = artifactRepository;
        this.versionRepository = versionRepository;
        this.aiWorkflowGateway = aiWorkflowGateway;
        this.contentFactory = contentFactory;
        this.objectMapper = objectMapper;
        this.projectAccessService = projectAccessService;
    }

    @Transactional
    public GenerationPlanResponse createPlan(Long projectId) {
        Project project = requireProject(projectId);
        TeachingIntent intent = requireLatestConfirmedIntent(projectId);

        AiWorkflowDtos.GenerationPlanResponse generated = aiWorkflowGateway.createGenerationPlan(
                new AiWorkflowDtos.GenerationPlanRequest(
                        projectId,
                        firstNonBlank(project.getCourseName(), project.getProjectName(), "Unnamed course"),
                        firstNonBlank(project.getChapterTopic(), project.getProjectName(), "Course topic"),
                        firstNonBlank(intent.getTargetAudience(), project.getTargetAudience()),
                        intent.getOutputTypes(),
                        project.getGenerationMode() == null ? GenerationMode.STANDARD : project.getGenerationMode()
                )
        );

        List<PlanSection> pptOutline = toPlanSections(generated.pptOutline());
        List<PlanSection> docOutline = toPlanSections(generated.docOutline());
        List<String> interactionPlan = normalizeStrings(generated.interactionPlan());
        validatePlanStructure(pptOutline, docOutline, interactionPlan);

        GenerationPlan plan = new GenerationPlan();
        plan.setProjectId(projectId);
        plan.setTeachingIntentId(intent.getId());
        plan.setProvider(activeProvider());
        plan.setPptOutline(writeJson(pptOutline));
        plan.setDocOutline(writeJson(docOutline));
        plan.setInteractionPlan(writeJson(interactionPlan));
        plan.setConfirmed(false);
        return toPlanResponse(planRepository.save(plan));
    }

    @Transactional(readOnly = true)
    public GenerationPlanResponse latestPlan(Long projectId) {
        requireProject(projectId);
        GenerationPlan plan = planRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation plan not found for project: " + projectId));
        return toPlanResponse(plan);
    }

    @Transactional
    public GenerationPlanResponse updatePlan(Long projectId, Long planId, GenerationPlanUpdateRequest request) {
        requireProject(projectId);
        GenerationPlan plan = requirePlan(projectId, planId);
        if (Boolean.TRUE.equals(plan.getConfirmed())) {
            throw new ConflictException("A confirmed generation plan cannot be modified");
        }
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }

        List<PlanSection> pptOutline = normalizeSections(request.pptOutline());
        List<PlanSection> docOutline = normalizeSections(request.docOutline());
        List<String> interactionPlan = normalizeStrings(request.interactionPlan());
        validatePlanStructure(pptOutline, docOutline, interactionPlan);

        plan.setPptOutline(writeJson(pptOutline));
        plan.setDocOutline(writeJson(docOutline));
        plan.setInteractionPlan(writeJson(interactionPlan));
        return toPlanResponse(planRepository.save(plan));
    }

    @Transactional
    public GenerationPlanResponse confirmPlan(Long projectId, Long planId) {
        requireProject(projectId);
        GenerationPlan plan = requirePlan(projectId, planId);
        if (Boolean.TRUE.equals(plan.getConfirmed())) {
            return toPlanResponse(plan);
        }
        validatePlanStructure(
                readPlanSections(plan.getPptOutline()),
                readPlanSections(plan.getDocOutline()),
                readStrings(plan.getInteractionPlan())
        );
        plan.setConfirmed(true);
        return toPlanResponse(planRepository.save(plan));
    }

    @Transactional
    public List<ArtifactResponse> generateArtifacts(Long projectId, ArtifactGenerationRequest request) {
        Project project = requireProject(projectId);
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        GenerationPlan plan = requirePlan(projectId, request.planId());
        if (!Boolean.TRUE.equals(plan.getConfirmed())) {
            throw new ConflictException("Generation plan must be confirmed before artifact generation");
        }

        List<GeneratedArtifact> projectArtifacts = artifactRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        boolean belongsToAnotherPlan = projectArtifacts.stream()
                .anyMatch(artifact -> !Objects.equals(plan.getId(), artifact.getGenerationPlanId()));
        if (belongsToAnotherPlan) {
            throw new ConflictException("M3 artifacts already exist for another generation plan");
        }

        ArtifactVersion version = requireOrCreateFirstVersion(projectId, plan.getId(), projectArtifacts);
        TeachingIntent intent = requirePlanIntent(plan);
        EnumSet<ArtifactType> existingTypes = projectArtifacts.stream()
                .map(GeneratedArtifact::getArtifactType)
                .filter(Objects::nonNull)
                .collect(() -> EnumSet.noneOf(ArtifactType.class), EnumSet::add, EnumSet::addAll);

        List<GeneratedArtifact> newArtifacts = new ArrayList<>();
        for (ArtifactType type : ARTIFACT_TYPES) {
            if (!existingTypes.contains(type)) {
                newArtifacts.add(createArtifact(project, intent, plan, version, type));
            }
        }
        if (!newArtifacts.isEmpty()) {
            artifactRepository.saveAll(newArtifacts);
        }

        if (project.getStatus() != ProjectStatus.FINALIZED) {
            project.setStatus(ProjectStatus.GENERATED);
        }
        projectRepository.save(project);
        return toArtifactResponses(artifactRepository.findByProjectIdAndGenerationPlanIdOrderByCreatedAtAsc(projectId, plan.getId()));
    }

    @Transactional(readOnly = true)
    public List<ArtifactResponse> listArtifacts(Long projectId) {
        requireProject(projectId);
        return toArtifactResponses(artifactRepository.findByProjectIdOrderByCreatedAtAsc(projectId));
    }

    @Transactional(readOnly = true)
    public ArtifactResponse getArtifact(Long projectId, Long artifactId) {
        requireProject(projectId);
        GeneratedArtifact artifact = artifactRepository.findByIdAndProjectId(artifactId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Artifact not found for project: " + projectId));
        Integer versionNumber = artifact.getVersionId() == null
                ? null
                : versionRepository.findById(artifact.getVersionId())
                        .filter(version -> projectId.equals(version.getProjectId()))
                        .map(ArtifactVersion::getVersionNumber)
                        .orElse(null);
        return toArtifactResponse(artifact, versionNumber);
    }

    @Transactional(readOnly = true)
    public GenerationWorkspaceResponse workspace(Long projectId) {
        Project project = requireProject(projectId);
        TeachingIntent intent = intentRepository
                .findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(projectId, TeachingIntentStatus.CONFIRMED)
                .orElse(null);
        GenerationPlan plan = planRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId).orElse(null);
        List<GeneratedArtifact> artifacts = artifactRepository.findByProjectIdOrderByCreatedAtAsc(projectId);

        GenerationPlanResponse planResponse = plan == null ? null : toPlanResponse(plan);
        List<ArtifactResponse> artifactResponses = toArtifactResponses(artifacts);
        boolean planConfirmed = plan != null && Boolean.TRUE.equals(plan.getConfirmed());
        boolean canGenerate = planConfirmed && artifacts.isEmpty();
        GenerationCapabilities capabilities = new GenerationCapabilities(
                intent != null,
                plan != null && !planConfirmed,
                plan != null && !planConfirmed,
                canGenerate,
                canGenerate,
                !artifacts.isEmpty()
        );
        TeachingIntentSummary intentSummary = intent == null ? null : new TeachingIntentSummary(
                intent.getId(),
                intent.getStatus(),
                intent.getGenerationGoal(),
                intent.getGenerationGoals(),
                intent.getContentBasis(),
                intent.getPrimaryBasis(),
                intent.getTeachingApproach(),
                intent.getTeachingFormat(),
                intent.getInteractionMode(),
                intent.getOutputTypes(),
                intent.getTargetAudience(),
                intent.getStylePreference()
        );

        return new GenerationWorkspaceResponse(
                project.getId(),
                project.getProjectName(),
                project.getStatus(),
                plan == null ? activeProvider() : firstNonBlank(plan.getProvider(), activeProvider()),
                intentSummary,
                planResponse,
                artifactResponses,
                capabilities
        );
    }

    private ArtifactVersion requireOrCreateFirstVersion(
            Long projectId,
            Long planId,
            List<GeneratedArtifact> existingArtifacts
    ) {
        ArtifactVersion version = versionRepository
                .findFirstByProjectIdAndGenerationPlanIdOrderByVersionNumberAsc(projectId, planId)
                .orElse(null);
        if (version != null) {
            return version;
        }
        if (!existingArtifacts.isEmpty() && existingArtifacts.get(0).getVersionId() != null) {
            return versionRepository.findById(existingArtifacts.get(0).getVersionId())
                    .filter(item -> projectId.equals(item.getProjectId()))
                    .orElseThrow(() -> new ConflictException("Artifact version does not belong to project: " + projectId));
        }

        ArtifactVersion created = new ArtifactVersion();
        created.setProjectId(projectId);
        created.setGenerationPlanId(planId);
        created.setVersionNumber(1);
        created.setDescription("M3 Mock first generated version");
        created.setFinalVersion(false);
        return versionRepository.save(created);
    }

    private GeneratedArtifact createArtifact(
            Project project,
            TeachingIntent intent,
            GenerationPlan plan,
            ArtifactVersion version,
            ArtifactType type
    ) {
        GenerationPlanResponse planResponse = toPlanResponse(plan);
        Object content = switch (type) {
            case PPT -> contentFactory.buildPpt(project, intent, planResponse);
            case DOCX -> contentFactory.buildLessonPlan(project, intent, planResponse);
            case INTERACTION -> contentFactory.buildInteraction(project);
        };

        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setProjectId(project.getId());
        artifact.setGenerationPlanId(plan.getId());
        artifact.setVersionId(version.getId());
        artifact.setArtifactType(type);
        artifact.setTitle(artifactTitle(project, type));
        artifact.setSchemaVersion(SCHEMA_VERSION);
        artifact.setContentJson(writeJson(content));
        artifact.setFilePath(null);
        return artifact;
    }

    private TeachingIntent requireLatestConfirmedIntent(Long projectId) {
        return intentRepository
                .findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(projectId, TeachingIntentStatus.CONFIRMED)
                .orElseThrow(() -> new ConflictException(
                        "A confirmed teaching intent is required before generation plan creation"
                ));
    }

    private TeachingIntent requirePlanIntent(GenerationPlan plan) {
        if (plan.getTeachingIntentId() == null) {
            return requireLatestConfirmedIntent(plan.getProjectId());
        }
        TeachingIntent intent = intentRepository.findById(plan.getTeachingIntentId())
                .filter(item -> plan.getProjectId().equals(item.getProjectId()))
                .orElseThrow(() -> new ConflictException("The teaching intent used by this generation plan is unavailable"));
        if (intent.getStatus() != TeachingIntentStatus.CONFIRMED) {
            throw new ConflictException("The teaching intent used by this generation plan is not confirmed");
        }
        return intent;
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

    private GenerationPlan requirePlan(Long projectId, Long planId) {
        if (planId == null || planId <= 0) {
            throw new BadRequestException("planId must be greater than 0");
        }
        return planRepository.findByIdAndProjectId(planId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation plan not found for project: " + projectId));
    }

    private GenerationPlanResponse toPlanResponse(GenerationPlan plan) {
        return new GenerationPlanResponse(
                plan.getId(),
                plan.getProjectId(),
                plan.getProvider(),
                readPlanSections(plan.getPptOutline()),
                readPlanSections(plan.getDocOutline()),
                readStrings(plan.getInteractionPlan()),
                Boolean.TRUE.equals(plan.getConfirmed()),
                plan.getCreatedAt(),
                plan.getUpdatedAt()
        );
    }

    private List<ArtifactResponse> toArtifactResponses(List<GeneratedArtifact> artifacts) {
        if (artifacts.isEmpty()) {
            return List.of();
        }
        Long projectId = artifacts.get(0).getProjectId();
        Map<Long, Integer> versionNumbers = new LinkedHashMap<>();
        versionRepository.findByProjectIdOrderByCreatedAtAsc(projectId).forEach(version ->
                versionNumbers.put(version.getId(), version.getVersionNumber())
        );
        return artifacts.stream()
                .sorted(Comparator
                        .comparingInt((GeneratedArtifact artifact) -> artifactOrder(artifact.getArtifactType()))
                        .thenComparing(GeneratedArtifact::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(GeneratedArtifact::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(artifact -> toArtifactResponse(artifact, versionNumbers.get(artifact.getVersionId())))
                .toList();
    }

    private ArtifactResponse toArtifactResponse(GeneratedArtifact artifact, Integer versionNumber) {
        return new ArtifactResponse(
                artifact.getId(),
                artifact.getProjectId(),
                artifact.getGenerationPlanId(),
                artifact.getVersionId(),
                versionNumber,
                artifact.getArtifactType(),
                artifact.getTitle(),
                artifact.getSchemaVersion(),
                readContent(artifact.getContentJson()),
                artifact.getCreatedAt()
        );
    }

    private List<PlanSection> toPlanSections(List<AiWorkflowDtos.PlanSection> sections) {
        if (sections == null) {
            return List.of();
        }
        List<PlanSection> result = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            AiWorkflowDtos.PlanSection section = sections.get(index);
            if (section == null) {
                continue;
            }
            List<String> descriptionParts = new ArrayList<>(normalizeStrings(section.points()));
            if (hasText(section.materialReference())) {
                descriptionParts.add("依据：" + section.materialReference().trim());
            }
            String description = descriptionParts.isEmpty()
                    ? firstNonBlank(section.title(), "Plan section")
                    : String.join("；", descriptionParts);
            result.add(new PlanSection(
                    index + 1,
                    firstNonBlank(section.title(), "Section " + (index + 1)),
                    description
            ));
        }
        return List.copyOf(result);
    }

    private static List<PlanSection> normalizeSections(List<PlanSection> sections) {
        if (sections == null) {
            return List.of();
        }
        return sections.stream()
                .map(section -> section == null ? null : new PlanSection(
                            section.order(),
                            trimToEmpty(section.title()),
                            trimToEmpty(section.description())
                    ))
                .toList();
    }

    private static List<String> normalizeStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static void validatePlanStructure(
            List<PlanSection> pptOutline,
            List<PlanSection> docOutline,
            List<String> interactionPlan
    ) {
        if (pptOutline == null || pptOutline.isEmpty()
                || docOutline == null || docOutline.isEmpty()
                || interactionPlan == null || interactionPlan.isEmpty()) {
            throw new BadRequestException("PPT outline, document outline, and interaction plan must not be empty");
        }
        boolean invalidSection = java.util.stream.Stream.concat(pptOutline.stream(), docOutline.stream())
                .anyMatch(section -> section == null
                        || section.order() == null
                        || section.order() <= 0
                        || !hasText(section.title())
                        || !hasText(section.description()));
        if (invalidSection || interactionPlan.stream().anyMatch(value -> !hasText(value))) {
            throw new BadRequestException("Generation plan contains an invalid section or interaction item");
        }
    }

    private List<PlanSection> readPlanSections(String value) {
        return readJson(value, PLAN_SECTION_LIST, "generation plan outline");
    }

    private List<String> readStrings(String value) {
        return readJson(value, STRING_LIST, "interaction plan");
    }

    private <T> T readJson(String value, TypeReference<T> type, String label) {
        if (!hasText(value)) {
            throw new IllegalStateException("Persisted " + label + " is empty");
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted " + label + " is invalid JSON", exception);
        }
    }

    private JsonNode readContent(String value) {
        if (!hasText(value)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted artifact content is invalid JSON", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize M3 generation data", exception);
        }
    }

    private String activeProvider() {
        return aiWorkflowGateway.status().activeProvider();
    }

    private static String artifactTitle(Project project, ArtifactType type) {
        String title = firstNonBlank(project.getProjectName(), project.getChapterTopic(), project.getCourseName(), "Teaching project");
        return switch (type) {
            case PPT -> title + "教学课件";
            case DOCX -> title + "教案";
            case INTERACTION -> title + "课堂互动";
        };
    }

    private static int artifactOrder(ArtifactType type) {
        return type == null ? Integer.MAX_VALUE : ARTIFACT_TYPES.indexOf(type);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
