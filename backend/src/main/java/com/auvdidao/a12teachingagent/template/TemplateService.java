package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.PayloadTooLargeException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.common.exception.StorageIntegrityException;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.template.Template;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static com.auvdidao.a12teachingagent.template.TemplateDtos.*;

@Service
public class TemplateService {

    private static final String PPTX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final int MAX_ZIP_ENTRIES = 2_000;
    private static final long MAX_SINGLE_ENTRY_BYTES = 50L * 1024 * 1024;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 250L * 1024 * 1024;
    private static final long MAX_COMPRESSION_RATIO = 100L;

    private final ProjectRepository projectRepository;
    private final TemplateRepository templateRepository;
    private final TemplateSourceVersionRepository sourceRepository;
    private final TemplateProfileVersionRepository profileRepository;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;
    private final ProjectAccessService projectAccessService;
    private final CurrentUserService currentUserService;
    private final TemplateProfileContractGate profileContractGate;

    public TemplateService(
            ProjectRepository projectRepository,
            TemplateRepository templateRepository,
            TemplateSourceVersionRepository sourceRepository,
            TemplateProfileVersionRepository profileRepository,
            FileStorageService fileStorageService,
            StorageProperties storageProperties,
            ProjectAccessService projectAccessService,
            CurrentUserService currentUserService,
            TemplateProfileContractGate profileContractGate
    ) {
        this.projectRepository = projectRepository;
        this.templateRepository = templateRepository;
        this.sourceRepository = sourceRepository;
        this.profileRepository = profileRepository;
        this.fileStorageService = fileStorageService;
        this.storageProperties = storageProperties;
        this.projectAccessService = projectAccessService;
        this.currentUserService = currentUserService;
        this.profileContractGate = profileContractGate;
    }

    @Transactional
    public TemplateResponse upload(Long projectId, String name, MultipartFile file) {
        Project project = requireProject(projectId);
        String normalizedName = normalizeName(name);
        ValidatedPptx validated = validate(file);
        Template template = templateRepository.findByProjectIdAndNameIgnoreCase(projectId, normalizedName)
                .orElseGet(() -> {
                    Template created = new Template();
                    created.setProjectId(projectId);
                    created.setName(normalizedName);
                    created.setCreatedByUserId(currentUserId().orElse(null));
                    return templateRepository.saveAndFlush(created);
                });

        Optional<TemplateSourceVersion> duplicate = sourceRepository.findByTemplateIdAndSha256(template.getId(), validated.sha256());
        if (duplicate.isPresent()) {
            return toResponse(template, duplicate.get(), true);
        }

        FileStorageService.StoredFile stored = fileStorageService.store(projectId, "pptx", file);
        if (!stored.sha256().equalsIgnoreCase(validated.sha256())) {
            fileStorageService.deleteQuietly(stored.storageKey());
            throw new StorageIntegrityException("INTEGRITY_MISMATCH: stored PPTX bytes differ from the upload hash");
        }
        TemplateSourceVersion source = new TemplateSourceVersion();
        source.setTemplateId(template.getId());
        source.setProjectId(projectId);
        source.setVersionNumber(sourceRepository.findTopByTemplateIdOrderByVersionNumberDesc(template.getId())
                .map(previous -> previous.getVersionNumber() + 1)
                .orElse(1));
        source.setCreatedByUserId(currentUserId().orElse(null));
        source.setOriginalFilename(validated.originalFilename());
        source.setStoredFilename(stored.storedFilename());
        source.setStorageKey(stored.storageKey());
        source.setContentType(PPTX_CONTENT_TYPE);
        source.setFileSize(file.getSize());
        source.setSha256(stored.sha256());

        try {
            source = sourceRepository.saveAndFlush(source);
            template.setActiveSourceVersionId(source.getId());
            templateRepository.saveAndFlush(template);
            return toResponse(template, source, false);
        } catch (RuntimeException exception) {
            fileStorageService.deleteQuietly(stored.storageKey());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<TemplateSummary> list(Long projectId) {
        requireProject(projectId);
        return templateRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public TemplateResponse detail(Long projectId, Long templateId) {
        Template template = requireTemplate(projectId, templateId);
        return new TemplateResponse(
                toSummary(template),
                false,
                sourceRepository.findByTemplateIdOrderByVersionNumberDesc(templateId).stream()
                        .map(source -> toSourceResponse(projectId, source, List.of(), null, null, false)).toList(),
                profileRepository.findByTemplateIdOrderByVersionNumberDesc(templateId).stream()
                        .map(profile -> { profileContractGate.requireIntact(profile); return new ProfileSummary(profile.getId(), profile.getVersionNumber(), profile.getSourceVersionId(),
                                profile.getStatus(), profile.getOrigin(),
                                profile.getChecksum(), profile.getCapabilityViewChecksum(), profile.getCreatedAt(), profile.getConfirmedAt()); })
                        .toList()
        );
    }

    @Transactional(readOnly = true)
    public SourceDownload download(Long projectId, Long templateId, Long sourceVersionId) {
        TemplateSourceVersion source = requireSource(projectId, templateId, sourceVersionId);
        Resource resource = fileStorageService.loadAndVerify(source.getStorageKey(), source.getSha256());
        return new SourceDownload(resource, source.getOriginalFilename(), source.getContentType(), source.getFileSize());
    }

    Project requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) throw new BadRequestException("projectId must be greater than 0");
        projectAccessService.requireAuthenticatedTeacherAccess(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        return project;
    }

    Template requireTemplate(Long projectId, Long templateId) {
        requireProject(projectId);
        if (templateId == null || templateId <= 0) throw new BadRequestException("templateId must be greater than 0");
        return templateRepository.findByIdAndProjectId(templateId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Template not found in project: " + templateId));
    }

    TemplateSourceVersion requireSource(Long projectId, Long templateId, Long sourceVersionId) {
        requireTemplate(projectId, templateId);
        if (sourceVersionId == null || sourceVersionId <= 0) throw new BadRequestException("sourceVersionId must be greater than 0");
        return sourceRepository.findByIdAndTemplateIdAndProjectId(sourceVersionId, templateId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Template source version not found: " + sourceVersionId));
    }

    Optional<Long> currentUserId() {
        return currentUserService.currentUser().map(AuthenticatedUser::userId);
    }

    TemplateResponse toResponse(Template template, TemplateSourceVersion source, boolean deduplicated) {
        return new TemplateResponse(toSummary(template), deduplicated,
                List.of(toSourceResponse(template.getProjectId(), source, List.of(), null, null, false)),
                profileRepository.findByTemplateIdOrderByVersionNumberDesc(template.getId()).stream()
                        .map(profile -> { profileContractGate.requireIntact(profile); return new ProfileSummary(profile.getId(), profile.getVersionNumber(), profile.getSourceVersionId(),
                                profile.getStatus(), profile.getOrigin(), profile.getChecksum(), profile.getCapabilityViewChecksum(), profile.getCreatedAt(), profile.getConfirmedAt()); })
                        .toList());
    }

    SourceVersionResponse toSourceResponse(Long projectId, TemplateSourceVersion source,
                                            List<ProcessingRunResponse> runs,
                                            StructuralSnapshotResponse snapshot,
                                            RenderedSlideSetResponse rendered,
                                            boolean detailsLoaded) {
        return new SourceVersionResponse(source.getId(), source.getTemplateId(), source.getVersionNumber(), source.getOriginalFilename(),
                source.getContentType(), source.getFileSize(), source.getSha256(), safeStatus(source.getParseStatus()),
                safeStatus(source.getRenderStatus()), safeStatus(source.getAnalysisStatus()), source.getCreatedAt(),
                "/api/projects/" + projectId + "/templates/" + source.getTemplateId() + "/source-versions/" + source.getId() + "/download",
                runs, snapshot, rendered, detailsLoaded);
    }

    private TemplateSummary toSummary(Template template) {
        return new TemplateSummary(template.getId(), template.getProjectId(), template.getName(), template.getActiveSourceVersionId(),
                template.getCreatedAt(), template.getUpdatedAt());
    }

    private ValidatedPptx validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) throw new BadRequestException("The PPTX file must not be empty");
        if (file.getSize() > storageProperties.getMaxFileSize()) {
            throw new PayloadTooLargeException("The uploaded template exceeds the configured size limit");
        }
        String original = sanitizeFilename(file.getOriginalFilename());
        if (!original.toLowerCase(Locale.ROOT).endsWith(".pptx")) {
            throw new BadRequestException("Only .pptx templates are supported");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!PPTX_CONTENT_TYPE.equals(contentType)) {
            throw new BadRequestException("The PPTX MIME type does not match the extension");
        }
        try {
            boolean contentTypes = false;
            boolean presentation = false;
            long totalUncompressedBytes = 0;
            Set<String> entryNames = new HashSet<>();
            try (InputStream input = file.getInputStream(); ZipInputStream zip = new ZipInputStream(input)) {
                java.util.zip.ZipEntry entry;
                int entries = 0;
                byte[] buffer = new byte[8192];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > MAX_ZIP_ENTRIES) throw new BadRequestException("The PPTX package contains too many entries");
                    String entryName = validateZipEntryName(entry.getName());
                    if (!entryNames.add(entryName)) throw new BadRequestException("The PPTX package contains duplicate entries");
                    long declaredSize = entry.getSize();
                    long compressedSize = entry.getCompressedSize();
                    if (declaredSize > MAX_SINGLE_ENTRY_BYTES) throw new PayloadTooLargeException("A PPTX package entry exceeds the per-entry limit");
                    if (declaredSize >= 0 && compressedSize > 0 && declaredSize / compressedSize > MAX_COMPRESSION_RATIO) {
                        throw new BadRequestException("The PPTX package compression ratio is unsafe");
                    }
                    long entryBytes = 0;
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        entryBytes += read;
                        totalUncompressedBytes += read;
                        if (entryBytes > MAX_SINGLE_ENTRY_BYTES) throw new PayloadTooLargeException("A PPTX package entry exceeds the per-entry limit");
                        if (totalUncompressedBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) throw new PayloadTooLargeException("The PPTX package exceeds the total decompressed size limit");
                    }
                    if ("[Content_Types].xml".equals(entryName)) contentTypes = true;
                    if ("ppt/presentation.xml".equals(entryName)) presentation = true;
                    zip.closeEntry();
                }
            }
            if (!contentTypes || !presentation) throw new BadRequestException("The uploaded file is not a valid PPTX package");
            try (InputStream input = file.getInputStream()) {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                input.transferTo(new java.io.OutputStream() {
                    @Override public void write(int b) { digest.update((byte) b); }
                    @Override public void write(byte[] b, int off, int len) { digest.update(b, off, len); }
                });
                return new ValidatedPptx(original, java.util.HexFormat.of().formatHex(digest.digest()));
            }
        } catch (IOException exception) {
            throw new BadRequestException("The uploaded PPTX package could not be read");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String validateZipEntryName(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.startsWith("/")
                || (name.length() > 1 && name.charAt(1) == ':')) {
            throw new BadRequestException("The PPTX package contains an unsafe entry path");
        }
        String normalized = name.replace('\\', '/');
        for (String part : normalized.split("/")) {
            if ("..".equals(part) || part.isBlank()) throw new BadRequestException("The PPTX package contains an unsafe entry path");
        }
        return normalized;
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null) throw new BadRequestException("The PPTX file must have a filename");
        String normalized = filename.replace('\\', '/').replace("\r", "").replace("\n", "").replace("\0", "");
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (basename.isBlank() || ".".equals(basename) || "..".equals(basename)) throw new BadRequestException("The PPTX file must have a valid filename");
        return basename;
    }

    private static String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || normalized.length() > 200) throw new BadRequestException("Template name must be 1-200 characters");
        return normalized;
    }

    private static TemplateProcessingStatus safeStatus(TemplateProcessingStatus status) {
        return status == null ? TemplateProcessingStatus.NOT_STARTED : status;
    }

    record SourceDownload(Resource resource, String originalFilename, String contentType, Long fileSize) { }
    private record ValidatedPptx(String originalFilename, String sha256) { }
}
