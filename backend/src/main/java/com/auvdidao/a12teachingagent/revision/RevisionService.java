package com.auvdidao.a12teachingagent.revision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.EditRecord;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.EditRecordRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.ArtifactResponse;
import com.auvdidao.a12teachingagent.revision.dto.RevisionDtos.EditRecordResponse;
import com.auvdidao.a12teachingagent.revision.dto.RevisionDtos.RevisionRequest;
import com.auvdidao.a12teachingagent.revision.dto.RevisionDtos.RevisionResponse;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.versioning.dto.ArtifactVersionDtos.ArtifactVersionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class RevisionService {

    private final ArtifactVersionRepository artifactVersionRepository;
    private final GeneratedArtifactRepository generatedArtifactRepository;
    private final EditRecordRepository editRecordRepository;
    private final ProjectRepository projectRepository;
    private final CurrentUserService currentUserService;
    private final AIWorkflowGateway aiWorkflowGateway;
    private final ObjectMapper objectMapper;

    public RevisionService(
            ArtifactVersionRepository artifactVersionRepository,
            GeneratedArtifactRepository generatedArtifactRepository,
            EditRecordRepository editRecordRepository,
            ProjectRepository projectRepository,
            CurrentUserService currentUserService,
            AIWorkflowGateway aiWorkflowGateway,
            ObjectMapper objectMapper
    ) {
        this.artifactVersionRepository = artifactVersionRepository;
        this.generatedArtifactRepository = generatedArtifactRepository;
        this.editRecordRepository = editRecordRepository;
        this.projectRepository = projectRepository;
        this.currentUserService = currentUserService;
        this.aiWorkflowGateway = aiWorkflowGateway;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RevisionResponse revise(Long projectId, Long artifactId, RevisionRequest request) {
        Project project = requireOwnerProject(projectId);
        GeneratedArtifact sourceArtifact = generatedArtifactRepository.findByIdAndProjectId(artifactId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Artifact not found for project: " + projectId));
        String instruction = request.instruction().trim();

        ArtifactVersion sourceVersion = sourceArtifact.getVersionId() == null
                ? null
                : artifactVersionRepository.findById(sourceArtifact.getVersionId())
                .filter(version -> Objects.equals(projectId, version.getProjectId()))
                .orElse(null);
        if (sourceVersion == null) {
            throw new ResourceNotFoundException("Artifact version not found for project: " + projectId);
        }
        if (Boolean.TRUE.equals(sourceVersion.getFinalVersion())) {
            throw new ConflictException("Final artifact version cannot be revised");
        }

        List<GeneratedArtifact> sourceArtifacts = generatedArtifactRepository
                .findByProjectIdAndVersionIdOrderByCreatedAtAsc(projectId, sourceVersion.getId());
        if (sourceArtifacts.isEmpty() || sourceArtifacts.stream().noneMatch(item -> Objects.equals(item.getId(), artifactId))) {
            throw new ResourceNotFoundException("Artifact does not belong to the source version");
        }

        AiWorkflowDtos.RevisionResponse aiRevision = aiWorkflowGateway.reviseArtifact(
                new AiWorkflowDtos.RevisionRequest(
                        projectId,
                        artifactId,
                        instruction,
                        firstNonBlank(sourceArtifact.getContentJson(), "{}")
                )
        );
        AiWorkflowDtos.AiGatewayStatus providerStatus = aiWorkflowGateway.status();
        List<String> changedSections = aiRevision.changedSections() == null
                ? List.of()
                : List.copyOf(aiRevision.changedSections());
        String changeSummary = firstNonBlank(
                aiRevision.changeSummary(),
                "Artifact structure updated according to the revision instruction."
        );

        int nextVersionNumber = artifactVersionRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(ArtifactVersion::getVersionNumber)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        ArtifactVersion targetVersion = new ArtifactVersion();
        targetVersion.setProjectId(projectId);
        targetVersion.setGenerationPlanId(sourceVersion.getGenerationPlanId());
        targetVersion.setVersionNumber(nextVersionNumber);
        targetVersion.setDescription(firstNonBlank(
                sourceVersion.getDescription(),
                "Revision of version " + sourceVersion.getVersionNumber()
        ) + " (revision)");
        targetVersion.setFinalVersion(false);
        targetVersion = artifactVersionRepository.save(targetVersion);

        List<GeneratedArtifact> clonedArtifacts = new ArrayList<>();
        for (GeneratedArtifact source : sourceArtifacts) {
            GeneratedArtifact clone = cloneArtifact(source, targetVersion);
            if (Objects.equals(source.getId(), artifactId)) {
                clone.setContentJson(revisedContent(source, instruction, changeSummary, nextVersionNumber));
            }
            clonedArtifacts.add(clone);
        }
        List<GeneratedArtifact> savedArtifacts = generatedArtifactRepository.saveAll(clonedArtifacts);

        EditRecord editRecord = new EditRecord();
        editRecord.setProjectId(projectId);
        editRecord.setVersionId(targetVersion.getId());
        editRecord.setEditInstruction(instruction);
        editRecord.setEditResult(changeSummary);
        editRecord = editRecordRepository.save(editRecord);

        return new RevisionResponse(
                toVersionResponse(targetVersion, savedArtifacts.size()),
                savedArtifacts.stream().map(this::toArtifactResponse).toList(),
                changeSummary,
                changedSections,
                providerStatus.requestedProvider(),
                providerStatus.activeProvider(),
                providerStatus.mockEnabled(),
                providerStatus.message(),
                toEditRecordResponse(editRecord)
        );
    }

    @Transactional(readOnly = true)
    public List<EditRecordResponse> listEditRecords(Long projectId) {
        requireOwnerProject(projectId);
        return editRecordRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toEditRecordResponse)
                .toList();
    }

    private Project requireOwnerProject(Long projectId) {
        AuthenticatedUser teacher = currentUserService.requireRole(UserRole.TEACHER);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        if (project.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
        if (!Objects.equals(project.getOwnerUserId(), teacher.userId())) {
            throw new ForbiddenException("This project belongs to another teacher");
        }
        return project;
    }

    private GeneratedArtifact cloneArtifact(GeneratedArtifact source, ArtifactVersion targetVersion) {
        GeneratedArtifact clone = new GeneratedArtifact();
        clone.setProjectId(source.getProjectId());
        clone.setGenerationPlanId(source.getGenerationPlanId());
        clone.setVersionId(targetVersion.getId());
        clone.setArtifactType(source.getArtifactType());
        clone.setTitle(source.getTitle());
        clone.setSchemaVersion(source.getSchemaVersion());
        clone.setContentJson(source.getContentJson());
        clone.setFilePath(source.getFilePath());
        return clone;
    }

    private String revisedContent(
            GeneratedArtifact source,
            String instruction,
            String changeSummary,
            int versionNumber
    ) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(source.getContentJson());
        } catch (JsonProcessingException exception) {
            throw new ConflictException("Source artifact content is not valid JSON");
        }
        if (!(parsed instanceof ObjectNode content)) {
            throw new ConflictException("Source artifact content must be a JSON object");
        }

        try {
            switch (source.getArtifactType()) {
                case PPT -> appendPptRevision(content, instruction, changeSummary, versionNumber);
                case DOCX -> appendDocxRevision(content, instruction, changeSummary);
                case INTERACTION -> appendInteractionRevision(content, instruction, changeSummary, versionNumber);
                default -> throw new ConflictException("Unsupported artifact type for revision");
            }
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ConflictException("Source artifact content does not match its JSON schema");
        }
    }

    private void appendPptRevision(ObjectNode content, String instruction, String changeSummary, int versionNumber) {
        ArrayNode slides = requiredArray(content, "slides");
        ObjectNode slide = slides.addObject();
        slide.put("index", slides.size());
        slide.put("kind", "REVISION");
        slide.put("title", "Revision notes");
        slide.put("layout", "CONTENT_WITH_SIDEBAR");
        ArrayNode points = slide.putArray("points");
        points.add("Revision v" + versionNumber + ": " + instruction);
        points.add("Change summary: " + changeSummary);
        slide.put("speakerNotes", "This slide records the structured revision instruction.");
    }

    private void appendDocxRevision(ObjectNode content, String instruction, String changeSummary) {
        ArrayNode sections = requiredArray(content, "sections");
        ObjectNode section = sections.addObject();
        section.put("order", sections.size());
        section.put("title", "Revision notes");
        ArrayNode paragraphs = section.putArray("paragraphs");
        paragraphs.add("Instruction: " + instruction);
        paragraphs.add("Change summary: " + changeSummary);
    }

    private void appendInteractionRevision(
            ObjectNode content,
            String instruction,
            String changeSummary,
            int versionNumber
    ) {
        ArrayNode questions = requiredArray(content, "questions");
        ObjectNode question = questions.addObject();
        question.put("id", "revision-v" + versionNumber);
        question.put("question", "Which statement records the current revision?");
        ArrayNode options = question.putArray("options");
        options.add("The revision instruction was reviewed.");
        options.add("The revision was ignored.");
        question.put("correctOption", 0);
        question.put("correctAnswer", "A");
        question.put("explanation", instruction + " Change summary: " + changeSummary);
    }

    private ArrayNode requiredArray(ObjectNode content, String field) {
        JsonNode existing = content.get(field);
        if (existing == null) {
            return content.putArray(field);
        }
        if (!existing.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return (ArrayNode) existing;
    }

    private ArtifactResponse toArtifactResponse(GeneratedArtifact artifact) {
        Integer versionNumber = artifact.getVersionId() == null
                ? null
                : artifactVersionRepository.findById(artifact.getVersionId())
                .map(ArtifactVersion::getVersionNumber)
                .orElse(null);
        return new ArtifactResponse(
                artifact.getId(),
                artifact.getProjectId(),
                artifact.getGenerationPlanId(),
                artifact.getVersionId(),
                versionNumber,
                artifact.getArtifactType(),
                artifact.getTitle(),
                artifact.getSchemaVersion(),
                readJson(artifact.getContentJson()),
                artifact.getCreatedAt()
        );
    }

    private ArtifactVersionResponse toVersionResponse(ArtifactVersion version, int artifactCount) {
        return new ArtifactVersionResponse(
                version.getId(),
                version.getProjectId(),
                version.getGenerationPlanId(),
                version.getVersionNumber(),
                version.getDescription(),
                Boolean.TRUE.equals(version.getFinalVersion()),
                artifactCount,
                version.getCreatedAt()
        );
    }

    private EditRecordResponse toEditRecordResponse(EditRecord record) {
        return new EditRecordResponse(
                record.getId(),
                record.getProjectId(),
                record.getVersionId(),
                record.getEditInstruction(),
                record.getEditResult(),
                record.getCreatedAt()
        );
    }

    private JsonNode readJson(String contentJson) {
        try {
            return objectMapper.readTree(contentJson);
        } catch (JsonProcessingException exception) {
            throw new ConflictException("Artifact content is not valid JSON");
        }
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
