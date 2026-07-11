package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.MaterialResponse;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.MaterialUsageResponse;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.MaterialUsageUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/materials")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MaterialResponse> upload(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) @Size(max = 300) String description
    ) {
        return ApiResponse.success(materialService.upload(projectId, file, description));
    }

    @GetMapping
    public ApiResponse<List<MaterialResponse>> list(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(materialService.list(projectId));
    }

    @GetMapping("/{materialId}")
    public ApiResponse<MaterialResponse> detail(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "materialId must be greater than 0") Long materialId
    ) {
        return ApiResponse.success(materialService.detail(projectId, materialId));
    }

    @GetMapping("/{materialId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "materialId must be greater than 0") Long materialId
    ) {
        MaterialService.MaterialDownload download = materialService.download(projectId, materialId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.originalFilename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.resource());
    }

    @PutMapping("/{materialId}/usages")
    public ApiResponse<MaterialUsageResponse> updateUsages(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "materialId must be greater than 0") Long materialId,
            @Valid @RequestBody MaterialUsageUpdateRequest request
    ) {
        return ApiResponse.success(materialService.updateUsages(projectId, materialId, request));
    }

    @GetMapping("/{materialId}/usages")
    public ApiResponse<MaterialUsageResponse> getUsages(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable @Positive(message = "materialId must be greater than 0") Long materialId
    ) {
        return ApiResponse.success(materialService.getUsages(projectId, materialId));
    }
}
