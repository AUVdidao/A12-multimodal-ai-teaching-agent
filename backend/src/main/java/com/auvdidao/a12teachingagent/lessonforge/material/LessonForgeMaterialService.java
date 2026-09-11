package com.auvdidao.a12teachingagent.lessonforge.material;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.common.exception.UnauthorizedException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.lessonforge.LessonForgeMaterialBinding;
import com.auvdidao.a12teachingagent.domain.lessonforge.repository.LessonForgeMaterialBindingRepository;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.IngestRequest;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.IngestResponse;
import com.auvdidao.a12teachingagent.material.MaterialService;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

import static com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus.NOT_STARTED;
import static com.auvdidao.a12teachingagent.domain.common.UploadStatus.UPLOADED;

/**
 * LessonForge-only material intake. It deliberately does not call the public
 * MaterialService.upload method, whose old-chain RequirementSummary gate must
 * remain intact.
 */
@Service
public class LessonForgeMaterialService {
    private static final String BOUND = "BOUND";

    private final CurrentUserService currentUserService;
    private final ProjectRepository projectRepository;
    private final UploadedMaterialRepository materialRepository;
    private final LessonForgeMaterialBindingRepository bindingRepository;
    private final FileStorageService fileStorageService;
    private final MaterialService materialService;

    public LessonForgeMaterialService(
            CurrentUserService currentUserService,
            ProjectRepository projectRepository,
            UploadedMaterialRepository materialRepository,
            LessonForgeMaterialBindingRepository bindingRepository,
            FileStorageService fileStorageService,
            MaterialService materialService
    ) {
        this.currentUserService = currentUserService;
        this.projectRepository = projectRepository;
        this.materialRepository = materialRepository;
        this.bindingRepository = bindingRepository;
        this.fileStorageService = fileStorageService;
        this.materialService = materialService;
    }

    @Transactional
    public IngestResponse ingest(Long projectId, IngestRequest request, MultipartFile file) {
        requirePositive(projectId, "projectId");
        if (request == null) throw new BadRequestException("LESSONFORGE_MATERIAL_REQUEST_REQUIRED");
        requirePositive(request.missionId(), "missionId");
        requirePositive(request.missionFileId(), "missionFileId");
        requirePositive(request.ownerUserId(), "ownerUserId");
        requirePositive(request.actorUserId(), "actorUserId");
        requirePositive(request.sourceSize(), "sourceSize");

        String expectedSha = normalizeSha(request.sourceSha256());
        AuthenticatedUser actor = currentUserService.requireRole(UserRole.TEACHER);
        if (actor.userId() == null) throw new UnauthorizedException("LESSONFORGE_ACTOR_REQUIRED");
        if (!Objects.equals(actor.userId(), request.actorUserId())
                || !Objects.equals(actor.userId(), request.ownerUserId())) {
            throw new ForbiddenException("LESSONFORGE_OWNER_ACTOR_MISMATCH");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        if (project.getDeletedAt() != null) throw new ResourceNotFoundException("Project not found: " + projectId);
        if (!Objects.equals(project.getOwnerUserId(), request.ownerUserId())) {
            throw new ForbiddenException("LESSONFORGE_PROJECT_OWNER_MISMATCH");
        }

        MaterialService.ValidatedFile validated = materialService.validateFile(file);
        if (file.getSize() != request.sourceSize()) {
            throw new ConflictException("LESSONFORGE_SOURCE_SIZE_MISMATCH");
        }
        if (!expectedSha.equals(hash(file))) {
            throw new ConflictException("LESSONFORGE_SOURCE_SHA256_MISMATCH");
        }

        LessonForgeMaterialBinding existing = bindingRepository
                .findByMissionFileIdAndSourceSha256(request.missionFileId(), expectedSha)
                .orElse(null);
        if (existing != null) {
            verifyIdentity(existing, projectId, request, expectedSha);
            UploadedMaterial material = materialRepository
                    .findByIdAndProjectId(existing.getRagMaterialId(), projectId)
                    .orElseThrow(() -> new ConflictException("LESSONFORGE_BINDING_MATERIAL_MISSING"));
            return response(existing, material);
        }
        for (LessonForgeMaterialBinding binding : bindingRepository.findByMissionFileIdOrderByIdAsc(request.missionFileId())) {
            if (!expectedSha.equalsIgnoreCase(binding.getSourceSha256())) {
                throw new ConflictException("LESSONFORGE_SOURCE_SHA_CONFLICT");
            }
        }

        FileStorageService.StoredFile stored = null;
        try {
            stored = fileStorageService.store(projectId, validated.extension(), file);
            if (!expectedSha.equalsIgnoreCase(stored.sha256())) {
                throw new ConflictException("LESSONFORGE_STORED_SOURCE_SHA256_MISMATCH");
            }

            UploadedMaterial material = new UploadedMaterial();
            material.setProjectId(projectId);
            material.setFileName(stored.storedFilename());
            material.setOriginalFileName(validated.originalFilename());
            material.setFileExtension(validated.extension());
            material.setFileType(validated.rule().fileType());
            material.setContentType(validated.contentType());
            material.setFilePath(stored.storageKey());
            material.setFileSize(file.getSize());
            material.setMaterialDescription(MaterialService.trimToNull(request.description()));
            material.setUploadStatus(UPLOADED);
            material.setParseStatus(NOT_STARTED);
            UploadedMaterial savedMaterial = materialRepository.saveAndFlush(material);

            LessonForgeMaterialBinding binding = new LessonForgeMaterialBinding();
            binding.setMissionId(request.missionId());
            binding.setMissionFileId(request.missionFileId());
            binding.setOwnerUserId(request.ownerUserId());
            binding.setActorUserId(request.actorUserId());
            binding.setRagProjectId(projectId);
            binding.setRagMaterialId(savedMaterial.getId());
            binding.setSourceSha256(expectedSha);
            binding.setSourceSize(request.sourceSize());
            binding.setOriginalFilename(validated.originalFilename());
            binding.setBindingStatus(BOUND);
            LessonForgeMaterialBinding savedBinding = bindingRepository.saveAndFlush(binding);
            return response(savedBinding, savedMaterial);
        } catch (RuntimeException exception) {
            if (stored != null) fileStorageService.deleteQuietly(stored.storageKey());
            throw exception;
        }
    }

    private void verifyIdentity(LessonForgeMaterialBinding binding, Long projectId, IngestRequest request, String expectedSha) {
        if (!Objects.equals(binding.getMissionId(), request.missionId())
                || !Objects.equals(binding.getMissionFileId(), request.missionFileId())
                || !Objects.equals(binding.getOwnerUserId(), request.ownerUserId())
                || !Objects.equals(binding.getActorUserId(), request.actorUserId())
                || !Objects.equals(binding.getRagProjectId(), projectId)
                || !expectedSha.equalsIgnoreCase(binding.getSourceSha256())
                || !Objects.equals(binding.getSourceSize(), request.sourceSize())) {
            throw new ConflictException("LESSONFORGE_BINDING_IDENTITY_CONFLICT");
        }
    }

    private IngestResponse response(LessonForgeMaterialBinding binding, UploadedMaterial material) {
        return new IngestResponse(binding.getId(), binding.getMissionId(), binding.getMissionFileId(),
                binding.getRagProjectId(), material.getId(), binding.getSourceSha256(), binding.getSourceSize(),
                binding.getOriginalFilename(), binding.getBindingStatus(), binding.getCreatedAt());
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) throw new BadRequestException(field + " must be greater than 0");
    }

    private static String normalizeSha(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new BadRequestException("sourceSha256 must be a SHA-256 hex value");
        return normalized;
    }

    private static String hash(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new BadRequestException("LESSONFORGE_SOURCE_FILE_UNREADABLE");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
