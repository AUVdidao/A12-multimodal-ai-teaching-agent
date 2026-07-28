package com.auvdidao.a12teachingagent.pptskill.harness;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/projects/{projectId}/ppt-template-selection")
public class PptTemplateSelectionController {
    private final PptTemplateSelectionService service;
    private final PptHarnessClient harnessClient;
    public PptTemplateSelectionController(PptTemplateSelectionService service, PptHarnessClient harnessClient) {
        this.service = service;
        this.harnessClient = harnessClient;
    }

    @GetMapping("/available")
    public ApiResponse<JsonNode> available(@PathVariable @Positive Long projectId) {
        service.get(projectId);
        return ApiResponse.success(harnessClient.listTemplates());
    }

    @GetMapping("/available/{templateId}/{templateVersion}")
    public ApiResponse<JsonNode> detail(@PathVariable @Positive Long projectId,
                                         @PathVariable String templateId,
                                         @PathVariable String templateVersion) {
        service.get(projectId);
        return ApiResponse.success(harnessClient.getTemplate(templateId, templateVersion));
    }

    @GetMapping
    public ApiResponse<PptTemplateSelectionService.Selection> get(@PathVariable @Positive Long projectId) {
        return ApiResponse.success(service.get(projectId));
    }

    @PutMapping
    public ApiResponse<PptTemplateSelectionService.Selection> select(@PathVariable @Positive Long projectId, @RequestBody TemplateSelectionRequest request) {
        return ApiResponse.success(service.select(projectId, request.templateId(), request.templateVersion()));
    }

    public record TemplateSelectionRequest(String templateId, String templateVersion) { }
}
