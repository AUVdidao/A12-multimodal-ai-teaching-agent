package com.auvdidao.a12teachingagent.lessonforge.material;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.knowledge.KnowledgeSearchService;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeMaterialReadResponse;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeSearchResponse;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeKnowledgeDtos.SearchRequest;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.PipelineRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;

@RestController
@Validated
@RequestMapping("/api/v1/internal/lessonforge/projects/{projectId}/knowledge")
public class LessonForgeKnowledgeController {
    private final LessonForgeMaterialPipelineService pipelineService;
    private final KnowledgeSearchService knowledgeSearchService;

    public LessonForgeKnowledgeController(
            LessonForgeMaterialPipelineService pipelineService,
            KnowledgeSearchService knowledgeSearchService
    ) {
        this.pipelineService = pipelineService;
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @PostMapping("/search")
    public ApiResponse<KnowledgeSearchResponse> search(
            @PathVariable @Positive Long projectId,
            @Valid @RequestBody SearchRequest request
    ) {
        return ApiResponse.success(knowledgeSearchService.searchWithinMaterialIds(
                projectId,
                request.query(),
                request.limit(),
                pipelineService.requireMissionMaterialIds(
                        projectId,
                        request.missionId(),
                        request.materialIds() == null ? null : new LinkedHashSet<>(request.materialIds())
                )
        ));
    }

    @GetMapping("/missions/{missionId}/materials/{materialId}/read")
    public ApiResponse<KnowledgeMaterialReadResponse> read(
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long missionId,
            @PathVariable @Positive Long materialId,
            @RequestParam(required = false) String locator,
            @RequestParam("missionId") @Positive Long identityMissionId,
            @RequestParam("missionFileId") @Positive Long missionFileId,
            @RequestParam("ownerUserId") @Positive Long ownerUserId,
            @RequestParam("actorUserId") @Positive Long actorUserId,
            @RequestParam("sourceSha256") @NotBlank @Size(min = 64, max = 64) String sourceSha256,
            @RequestParam("sourceSize") @Positive Long sourceSize
    ) {
        pipelineService.requireMissionMaterial(
                projectId,
                missionId,
                materialId,
                new PipelineRequest(identityMissionId, missionFileId, ownerUserId, actorUserId, sourceSha256, sourceSize)
        );
        return ApiResponse.success(knowledgeSearchService.readMaterial(projectId, materialId, locator));
    }
}
