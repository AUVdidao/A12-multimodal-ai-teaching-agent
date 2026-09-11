package com.auvdidao.a12teachingagent.lessonforge.material;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.IngestRequest;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.IngestResponse;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequestMapping("/api/v1/internal/lessonforge/projects/{projectId}/materials")
public class LessonForgeMaterialController {
    private final LessonForgeMaterialService service;

    public LessonForgeMaterialController(LessonForgeMaterialService service) {
        this.service = service;
    }

    /**
     * Internal Go→Java intake. The bearer proves service identity; all owner,
     * actor, Mission/File and byte-hash fields are independently checked.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<IngestResponse> ingest(
            @PathVariable @Positive Long projectId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("missionId") @Positive Long missionId,
            @RequestParam("missionFileId") @Positive Long missionFileId,
            @RequestParam("ownerUserId") @Positive Long ownerUserId,
            @RequestParam("actorUserId") @Positive Long actorUserId,
            @RequestParam("sourceSha256") @Size(min = 64, max = 64) String sourceSha256,
            @RequestParam("sourceSize") @Positive Long sourceSize,
            @RequestParam(value = "description", required = false) @Size(max = 300) String description
    ) {
        return ApiResponse.success(service.ingest(projectId,
                new IngestRequest(missionId, missionFileId, ownerUserId, actorUserId, sourceSha256, sourceSize, description), file));
    }
}
