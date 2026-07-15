package com.auvdidao.a12teachingagent.artifactexport;

import com.auvdidao.a12teachingagent.artifactexport.ArtifactExportDtos.ExportCatalog;
import com.auvdidao.a12teachingagent.artifactexport.ArtifactExportDtos.GeneratedExport;
import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/exports")
public class ArtifactExportController {

    private final ArtifactExportService exportService;

    public ArtifactExportController(ArtifactExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping
    public ApiResponse<ExportCatalog> list(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId
    ) {
        return ApiResponse.success(exportService.listAvailable(projectId));
    }

    @GetMapping("/{format}")
    public ResponseEntity<byte[]> download(
            @PathVariable @Positive(message = "projectId must be greater than 0") Long projectId,
            @PathVariable String format
    ) {
        GeneratedExport generated = exportService.generate(projectId, format);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(generated.filename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(generated.mediaType()))
                .contentLength(generated.content().length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(generated.content());
    }
}
