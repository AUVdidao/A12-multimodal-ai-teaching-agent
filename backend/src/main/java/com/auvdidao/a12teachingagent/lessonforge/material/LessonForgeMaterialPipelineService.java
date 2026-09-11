package com.auvdidao.a12teachingagent.lessonforge.material;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.lessonforge.LessonForgeMaterialBinding;
import com.auvdidao.a12teachingagent.domain.lessonforge.repository.LessonForgeMaterialBindingRepository;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.knowledge.KnowledgeIndexService;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeChunkResponse;
import com.auvdidao.a12teachingagent.material.MaterialParseIdentity;
import com.auvdidao.a12teachingagent.material.MaterialParseService;
import com.auvdidao.a12teachingagent.material.chunk.TextCleaner;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.ParseResultResponse;
import com.auvdidao.a12teachingagent.material.parse.MaterialParsingException;
import com.auvdidao.a12teachingagent.material.parse.MaterialPrototypeParser;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * LessonForge's parser/index boundary. This deliberately bypasses the old
 * MaterialParseTransactionService because that service belongs to the legacy
 * public Material API and requires a confirmed RequirementSummary.
 */
@Service
public class LessonForgeMaterialPipelineService {
    private static final String BOUND = "BOUND";
    private static final String FAILURE_REASON =
            "LessonForge material parsing could not be completed. Please retry.";

    private final CurrentUserService currentUserService;
    private final ProjectRepository projectRepository;
    private final UploadedMaterialRepository materialRepository;
    private final ParseResultRepository parseResultRepository;
    private final LessonForgeMaterialBindingRepository bindingRepository;
    private final MaterialPrototypeParser prototypeParser;
    private final KnowledgeIndexService knowledgeIndexService;
    private final TextCleaner textCleaner;

    public LessonForgeMaterialPipelineService(
            CurrentUserService currentUserService,
            ProjectRepository projectRepository,
            UploadedMaterialRepository materialRepository,
            ParseResultRepository parseResultRepository,
            LessonForgeMaterialBindingRepository bindingRepository,
            MaterialPrototypeParser prototypeParser,
            KnowledgeIndexService knowledgeIndexService,
            TextCleaner textCleaner
    ) {
        this.currentUserService = currentUserService;
        this.projectRepository = projectRepository;
        this.materialRepository = materialRepository;
        this.parseResultRepository = parseResultRepository;
        this.bindingRepository = bindingRepository;
        this.prototypeParser = prototypeParser;
        this.knowledgeIndexService = knowledgeIndexService;
        this.textCleaner = textCleaner;
    }

    @Transactional
    public ParseResultResponse parse(Long projectId, Long materialId, LessonForgeMaterialDtos.PipelineRequest identity) {
        UploadedMaterial material = requireBoundMaterial(projectId, materialId, identity);
        ParseResult previous = parseResultRepository
                .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(materialId)
                .orElse(null);
        if (previous != null && previous.getParseStatus() == MaterialParseStatus.SUCCEEDED) {
            MaterialParseIdentity.requireComplete(previous);
            return MaterialParseService.toResponse(previous);
        }

        material.setParseStatus(MaterialParseStatus.PROCESSING);
        materialRepository.saveAndFlush(material);
        ParseResult result = previous == null ? new ParseResult() : previous;
        result.setMaterialId(materialId);
        result.setAnalysisRunId(MaterialParseIdentity.newAnalysisRunId());
        result.setSourceVersionId(materialId);
        result.setParserSnapshotChecksum(null);
        result.setParseStatus(MaterialParseStatus.PROCESSING);
        result.setFailureReason(null);
        result.setChunkCount(null);
        result = parseResultRepository.saveAndFlush(result);
        long startedAt = System.nanoTime();

        try {
            // Null is intentional: the new contract has no RequirementSummary
            // dependency and does not synthesize one to satisfy the old chain.
            MaterialPrototypeParser.ParsedContent parsed = prototypeParser.parse(material, List.of(), null);
            if (parsed == null) {
                throw new MaterialParsingException("LessonForge parser returned no result");
            }
            String snapshotChecksum = MaterialParseIdentity.snapshotChecksum(parsed);
            String extractedText = textCleaner.clean(parsed.extractedText());
            if (snapshotChecksum == null || extractedText.isBlank()) {
                throw new MaterialParsingException("LessonForge parser returned no extractable text");
            }

            result.setSummary(parsed.summary());
            result.setKeywords(parsed.keywords());
            result.setApplicableTeachingStages(parsed.teachingStages());
            result.setExtractedText(extractedText);
            result.setPageCount(parsed.pageCount());
            result.setSections(parsed.sections());
            result.setAnalysisRunId(result.getAnalysisRunId());
            result.setSourceVersionId(materialId);
            result.setParserSnapshotChecksum(snapshotChecksum);
            result.setParseDurationMs(elapsedMillis(startedAt));
            result.setParseStatus(MaterialParseStatus.SUCCEEDED);
            result.setParsedAt(LocalDateTime.now());
            result.setFailureReason(null);
            parseResultRepository.saveAndFlush(result);

            material.setParseStatus(MaterialParseStatus.SUCCEEDED);
            material.setUploadStatus(UploadStatus.PARSED);
            knowledgeIndexService.indexLessonForge(material);
            materialRepository.saveAndFlush(material);
            result.setParseDurationMs(elapsedMillis(startedAt));
            return MaterialParseService.toResponse(parseResultRepository.saveAndFlush(result));
        } catch (MaterialParsingException exception) {
            return fail(result, material, startedAt);
        } catch (RuntimeException exception) {
            fail(result, material, startedAt);
            throw exception;
        }
    }

    @Transactional
    public List<KnowledgeChunkResponse> index(Long projectId, Long materialId, LessonForgeMaterialDtos.PipelineRequest identity) {
        UploadedMaterial material = requireBoundMaterial(projectId, materialId, identity);
        return knowledgeIndexService.indexLessonForge(material);
    }

    /**
     * Returns the authorized Java material ids for one Go-owned Mission. The
     * caller may narrow this set, but cannot introduce a material from another
     * Mission or teacher.
     */
    @Transactional(readOnly = true)
    public Set<Long> requireMissionMaterialIds(Long projectId, Long missionId, Set<Long> requestedMaterialIds) {
        AuthenticatedUser actor = requireActor();
        requireProjectOwnedBy(projectId, actor.userId());
        requirePositive(missionId, "missionId");
        List<LessonForgeMaterialBinding> bindings = bindingRepository
                .findByMissionIdAndRagProjectIdOrderByIdAsc(missionId, projectId);
        Set<Long> authorized = new LinkedHashSet<>();
        for (LessonForgeMaterialBinding binding : bindings) {
            if (isAuthorizedBinding(binding, projectId, actor.userId())) {
                authorized.add(binding.getRagMaterialId());
            }
        }
        if (authorized.isEmpty()) {
            throw new ResourceNotFoundException("LessonForge Mission material scope not found");
        }
        if (requestedMaterialIds == null || requestedMaterialIds.isEmpty()) {
            return Set.copyOf(authorized);
        }
        if (!authorized.containsAll(requestedMaterialIds)) {
            throw new ForbiddenException("LESSONFORGE_MATERIAL_SCOPE_MISMATCH");
        }
        return Set.copyOf(requestedMaterialIds);
    }

    public UploadedMaterial requireMissionMaterial(
            Long projectId,
            Long missionId,
            Long materialId,
            LessonForgeMaterialDtos.PipelineRequest identity
    ) {
        Set<Long> authorized = requireMissionMaterialIds(projectId, missionId, Set.of(materialId));
        if (!authorized.contains(materialId)) {
            throw new ForbiddenException("LESSONFORGE_MATERIAL_SCOPE_MISMATCH");
        }
        if (identity == null || !Objects.equals(identity.missionId(), missionId)) {
            throw new ConflictException("LESSONFORGE_MATERIAL_IDENTITY_MISMATCH");
        }
        return requireBoundMaterial(projectId, materialId, identity);
    }

    public UploadedMaterial requireBoundMaterial(
            Long projectId,
            Long materialId,
            LessonForgeMaterialDtos.PipelineRequest identity
    ) {
        AuthenticatedUser actor = requireActor();
        requireProjectOwnedBy(projectId, actor.userId());
        requirePositive(materialId, "materialId");
        if (identity == null) {
            throw new BadRequestException("LESSONFORGE_MATERIAL_IDENTITY_REQUIRED");
        }
        requirePositive(identity.missionId(), "missionId");
        requirePositive(identity.missionFileId(), "missionFileId");
        requirePositive(identity.ownerUserId(), "ownerUserId");
        requirePositive(identity.actorUserId(), "actorUserId");
        requirePositive(identity.sourceSize(), "sourceSize");
        String expectedSha = normalizeSha(identity.sourceSha256());
        if (!Objects.equals(actor.userId(), identity.ownerUserId())
                || !Objects.equals(actor.userId(), identity.actorUserId())) {
            throw new ForbiddenException("LESSONFORGE_OWNER_ACTOR_MISMATCH");
        }
        LessonForgeMaterialBinding binding = bindingRepository
                .findByRagProjectIdAndRagMaterialId(projectId, materialId)
                .orElseThrow(() -> new ResourceNotFoundException("LessonForge material binding not found"));
        if (!isAuthorizedBinding(binding, projectId, actor.userId())) {
            throw new ForbiddenException("LESSONFORGE_MATERIAL_SCOPE_MISMATCH");
        }
        if (!Objects.equals(binding.getMissionId(), identity.missionId())
                || !Objects.equals(binding.getMissionFileId(), identity.missionFileId())
                || !Objects.equals(binding.getOwnerUserId(), identity.ownerUserId())
                || !Objects.equals(binding.getActorUserId(), identity.actorUserId())
                || !Objects.equals(binding.getRagProjectId(), projectId)
                || !Objects.equals(binding.getRagMaterialId(), materialId)
                || !expectedSha.equalsIgnoreCase(binding.getSourceSha256())
                || !Objects.equals(binding.getSourceSize(), identity.sourceSize())) {
            throw new ConflictException("LESSONFORGE_MATERIAL_IDENTITY_MISMATCH");
        }
        UploadedMaterial material = materialRepository.findByIdAndProjectId(materialId, projectId)
                .orElseThrow(() -> new ConflictException("LESSONFORGE_BINDING_MATERIAL_MISSING"));
        if (!Objects.equals(material.getFileSize(), identity.sourceSize())) {
            throw new ConflictException("LESSONFORGE_SOURCE_SIZE_MISMATCH");
        }
        return material;
    }

    private ParseResultResponse fail(ParseResult result, UploadedMaterial material, long startedAt) {
        result.setParseStatus(MaterialParseStatus.FAILED);
        result.setFailureReason(FAILURE_REASON);
        result.setParsedAt(LocalDateTime.now());
        result.setParseDurationMs(elapsedMillis(startedAt));
        parseResultRepository.saveAndFlush(result);
        material.setParseStatus(MaterialParseStatus.FAILED);
        material.setUploadStatus(UploadStatus.FAILED);
        materialRepository.saveAndFlush(material);
        return MaterialParseService.toResponse(result);
    }

    private AuthenticatedUser requireActor() {
        AuthenticatedUser actor = currentUserService.requireRole(com.auvdidao.a12teachingagent.domain.common.UserRole.TEACHER);
        if (actor.userId() == null || actor.userId() <= 0) {
            throw new com.auvdidao.a12teachingagent.common.exception.UnauthorizedException(
                    "LESSONFORGE_ACTOR_REQUIRED");
        }
        return actor;
    }

    private Project requireProjectOwnedBy(Long projectId, Long ownerUserId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        if (project.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
        if (!Objects.equals(project.getOwnerUserId(), ownerUserId)) {
            throw new ForbiddenException("LESSONFORGE_PROJECT_OWNER_MISMATCH");
        }
        return project;
    }

    private static boolean isAuthorizedBinding(LessonForgeMaterialBinding binding, Long projectId, Long actorUserId) {
        return BOUND.equals(binding.getBindingStatus())
                && Objects.equals(binding.getRagProjectId(), projectId)
                && Objects.equals(binding.getOwnerUserId(), actorUserId)
                && Objects.equals(binding.getActorUserId(), actorUserId)
                && binding.getRagMaterialId() != null && binding.getRagMaterialId() > 0;
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new BadRequestException(field + " must be greater than 0");
        }
    }

    private static String normalizeSha(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new BadRequestException("sourceSha256 must be a SHA-256 hex value");
        }
        return normalized;
    }

    private static long elapsedMillis(long startedAt) {
        return startedAt <= 0L ? 0L : Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
