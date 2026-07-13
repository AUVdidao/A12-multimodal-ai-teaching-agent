package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.PayloadTooLargeException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.MaterialResponse;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.MaterialUsageResponse;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.MaterialUsageUpdateRequest;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MaterialService {

    private static final Map<String, FileRule> FILE_RULES = Map.ofEntries(
            Map.entry("pdf", new FileRule(MaterialFileType.PDF, Set.of("application/pdf"))),
            Map.entry("docx", new FileRule(MaterialFileType.DOCX, Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))),
            Map.entry("pptx", new FileRule(MaterialFileType.PPTX, Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"))),
            Map.entry("ppt", new FileRule(MaterialFileType.PPT, Set.of("application/vnd.ms-powerpoint"))),
            Map.entry("xlsx", new FileRule(MaterialFileType.XLSX, Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))),
            Map.entry("txt", new FileRule(MaterialFileType.TXT, Set.of("text/plain", "application/octet-stream"))),
            Map.entry("md", new FileRule(MaterialFileType.MD, Set.of("text/markdown", "text/plain", "text/x-markdown", "application/octet-stream"))),
            Map.entry("mp4", new FileRule(MaterialFileType.MP4, Set.of("video/mp4", "application/mp4"))),
            Map.entry("png", new FileRule(MaterialFileType.PNG, Set.of("image/png"))),
            Map.entry("jpg", new FileRule(MaterialFileType.JPG, Set.of("image/jpeg", "image/jpg"))),
            Map.entry("jpeg", new FileRule(MaterialFileType.JPEG, Set.of("image/jpeg", "image/jpg")))
    );

    private final ProjectRepository projectRepository;
    private final RequirementSummaryRepository requirementSummaryRepository;
    private final UploadedMaterialRepository materialRepository;
    private final MaterialPurposeRepository purposeRepository;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    public MaterialService(
            ProjectRepository projectRepository,
            RequirementSummaryRepository requirementSummaryRepository,
            UploadedMaterialRepository materialRepository,
            MaterialPurposeRepository purposeRepository,
            FileStorageService fileStorageService,
            StorageProperties storageProperties
    ) {
        this.projectRepository = projectRepository;
        this.requirementSummaryRepository = requirementSummaryRepository;
        this.materialRepository = materialRepository;
        this.purposeRepository = purposeRepository;
        this.fileStorageService = fileStorageService;
        this.storageProperties = storageProperties;
    }

    @Transactional
    public MaterialResponse upload(Long projectId, MultipartFile file, String description) {
        requireConfirmedSummary(projectId);
        ValidatedFile validated = validateFile(file);
        FileStorageService.StoredFile stored = fileStorageService.store(projectId, validated.extension(), file);

        UploadedMaterial material = new UploadedMaterial();
        material.setProjectId(projectId);
        material.setFileName(stored.storedFilename());
        material.setOriginalFileName(validated.originalFilename());
        material.setFileExtension(validated.extension());
        material.setFileType(validated.rule().fileType());
        material.setContentType(validated.contentType());
        material.setFilePath(stored.storageKey());
        material.setFileSize(file.getSize());
        material.setMaterialDescription(trimToNull(description));
        material.setUploadStatus(UploadStatus.UPLOADED);
        material.setParseStatus(MaterialParseStatus.NOT_STARTED);

        try {
            return toResponse(materialRepository.saveAndFlush(material));
        } catch (RuntimeException exception) {
            fileStorageService.deleteQuietly(stored.storageKey());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<MaterialResponse> list(Long projectId) {
        requireProject(projectId);
        return materialRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MaterialResponse detail(Long projectId, Long materialId) {
        requireProject(projectId);
        return toResponse(requireMaterial(projectId, materialId));
    }

    @Transactional(readOnly = true)
    public MaterialDownload download(Long projectId, Long materialId) {
        requireProject(projectId);
        UploadedMaterial material = requireMaterial(projectId, materialId);
        return new MaterialDownload(
                fileStorageService.load(material.getFilePath()),
                material.getOriginalFileName(),
                material.getContentType(),
                material.getFileSize()
        );
    }

    @Transactional
    public MaterialUsageResponse updateUsages(
            Long projectId,
            Long materialId,
            MaterialUsageUpdateRequest request
    ) {
        requireProject(projectId);
        requireMaterial(projectId, materialId);
        if (request == null || request.usageTypes() == null || request.usageTypes().isEmpty()) {
            throw new BadRequestException("At least one material usage is required");
        }

        LinkedHashSet<PurposeType> normalized = new LinkedHashSet<>();
        for (PurposeType type : request.usageTypes()) {
            if (type == null || !MaterialLabels.SUPPORTED_USAGES.contains(type)) {
                throw new BadRequestException("Unsupported material usage: " + type);
            }
            normalized.add(type);
        }

        purposeRepository.deleteByMaterialId(materialId);
        String note = trimToNull(request.note());
        List<MaterialPurpose> rows = new ArrayList<>();
        for (PurposeType type : normalized) {
            MaterialPurpose purpose = new MaterialPurpose();
            purpose.setProjectId(projectId);
            purpose.setMaterialId(materialId);
            purpose.setPurposeType(type);
            purpose.setPurposeDescription(note);
            rows.add(purpose);
        }
        purposeRepository.saveAll(rows);
        return new MaterialUsageResponse(materialId, projectId, List.copyOf(normalized), note, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public MaterialUsageResponse getUsages(Long projectId, Long materialId) {
        requireProject(projectId);
        requireMaterial(projectId, materialId);
        List<MaterialPurpose> rows = purposeRepository.findByMaterialIdOrderByIdAsc(materialId);
        return new MaterialUsageResponse(
                materialId,
                projectId,
                rows.stream().map(MaterialPurpose::getPurposeType).distinct().toList(),
                rows.isEmpty() ? null : rows.get(0).getPurposeDescription(),
                rows.isEmpty() ? null : rows.get(rows.size() - 1).getCreatedAt()
        );
    }

    Project requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BadRequestException("projectId must be greater than 0");
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    RequirementSummary requireConfirmedSummary(Long projectId) {
        requireProject(projectId);
        RequirementSummary summary = requirementSummaryRepository
                .findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .orElseThrow(() -> new ConflictException("A confirmed requirement summary is required before material upload"));
        if (summary.getStatus() != RequirementSummaryStatus.CONFIRMED) {
            throw new ConflictException("A confirmed requirement summary is required before material upload");
        }
        return summary;
    }

    UploadedMaterial requireMaterial(Long projectId, Long materialId) {
        if (materialId == null || materialId <= 0) {
            throw new BadRequestException("materialId must be greater than 0");
        }
        return materialRepository.findByIdAndProjectId(materialId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Material not found in project: " + materialId));
    }

    MaterialResponse toResponse(UploadedMaterial material) {
        List<MaterialPurpose> purposes = purposeRepository.findByMaterialIdOrderByIdAsc(material.getId());
        return new MaterialResponse(
                material.getId(),
                material.getProjectId(),
                material.getOriginalFileName(),
                material.getFileExtension(),
                material.getFileType(),
                material.getContentType(),
                material.getFileSize(),
                material.getMaterialDescription(),
                material.getUploadStatus(),
                material.getParseStatus() == null ? MaterialParseStatus.NOT_STARTED : material.getParseStatus(),
                purposes.stream().map(MaterialPurpose::getPurposeType).distinct().toList(),
                purposes.isEmpty() ? null : purposes.get(0).getPurposeDescription(),
                material.getCreatedAt(),
                material.getUpdatedAt(),
                "/api/projects/" + material.getProjectId() + "/materials/" + material.getId() + "/download"
        );
    }

    private ValidatedFile validateFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BadRequestException("The uploaded file must not be empty");
        }
        if (file.getSize() > storageProperties.getMaxFileSize()) {
            long maxSizeMb = storageProperties.getMaxFileSize() / (1024 * 1024);
            throw new PayloadTooLargeException("The uploaded file exceeds the " + maxSizeMb + " MB limit");
        }

        String original = sanitizeOriginalFilename(file.getOriginalFilename());
        int dot = original.lastIndexOf('.');
        if (dot <= 0 || dot == original.length() - 1) {
            throw new BadRequestException("The uploaded file must have a supported extension");
        }
        String extension = original.substring(dot + 1).toLowerCase(Locale.ROOT);
        FileRule rule = FILE_RULES.get(extension);
        if (rule == null) {
            throw new BadRequestException("Unsupported file extension: " + extension);
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!rule.contentTypes().contains(contentType)) {
            throw new BadRequestException("The file MIME type does not match its extension");
        }
        return new ValidatedFile(original, extension, contentType, rule);
    }

    private static String sanitizeOriginalFilename(String filename) {
        if (filename == null) {
            throw new BadRequestException("The uploaded file must have a filename");
        }
        String normalized = filename.replace('\\', '/').replace("\r", "").replace("\n", "").replace("\0", "");
        int slash = normalized.lastIndexOf('/');
        String basename = (slash >= 0 ? normalized.substring(slash + 1) : normalized).trim();
        if (basename.isBlank() || ".".equals(basename) || "..".equals(basename)) {
            throw new BadRequestException("The uploaded file must have a valid filename");
        }
        return basename;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record MaterialDownload(Resource resource, String originalFilename, String contentType, Long fileSize) {
    }

    private record FileRule(MaterialFileType fileType, Set<String> contentTypes) {
    }

    private record ValidatedFile(String originalFilename, String extension, String contentType, FileRule rule) {
    }
}
