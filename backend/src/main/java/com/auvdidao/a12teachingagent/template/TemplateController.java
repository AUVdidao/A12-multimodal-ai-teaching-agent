package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingOperation;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.auvdidao.a12teachingagent.template.TemplateDtos.*;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/templates")
public class TemplateController {

    private final TemplateService templateService;
    private final TemplateProcessingService processingService;

    public TemplateController(TemplateService templateService, TemplateProcessingService processingService) {
        this.templateService = templateService;
        this.processingService = processingService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TemplateResponse> upload(
            @PathVariable @Positive Long projectId,
            @RequestParam @Size(min = 1, max = 200) String name,
            @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.success(templateService.upload(projectId, name, file));
    }

    @GetMapping
    public ApiResponse<List<TemplateSummary>> list(@PathVariable @Positive Long projectId) {
        return ApiResponse.success(templateService.list(projectId));
    }

    @GetMapping("/{templateId}")
    public ApiResponse<TemplateResponse> detail(@PathVariable @Positive Long projectId, @PathVariable @Positive Long templateId) {
        return ApiResponse.success(templateService.detail(projectId, templateId));
    }

    @GetMapping("/{templateId}/source-versions/{sourceVersionId}")
    public ApiResponse<SourceVersionResponse> sourceDetail(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @PathVariable @Positive Long sourceVersionId
    ) {
        return ApiResponse.success(processingService.detail(projectId, templateId, sourceVersionId));
    }

    @GetMapping("/{templateId}/source-versions/{sourceVersionId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @PathVariable @Positive Long sourceVersionId
    ) {
        TemplateService.SourceDownload download = templateService.download(projectId, templateId, sourceVersionId);
        ContentDisposition disposition = ContentDisposition.attachment().filename(download.originalFilename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.resource());
    }

    @PostMapping("/{templateId}/source-versions/{sourceVersionId}/{operation}")
    public ApiResponse<ProcessingStatusResponse> process(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long templateId,
            @PathVariable @Positive Long sourceVersionId,
            @PathVariable String operation
    ) {
        return ApiResponse.success(processingService.run(projectId, templateId, sourceVersionId, parseOperation(operation)));
    }

    private TemplateProcessingOperation parseOperation(String operation) {
        return switch (operation.toLowerCase(java.util.Locale.ROOT)) {
            case "parse", "parser" -> TemplateProcessingOperation.PARSER;
            case "render", "renderer" -> TemplateProcessingOperation.RENDERER;
            case "analyze", "analyzer" -> TemplateProcessingOperation.ANALYZER;
            default -> throw new com.auvdidao.a12teachingagent.common.exception.BadRequestException("Unsupported template processing operation");
        };
    }
}


