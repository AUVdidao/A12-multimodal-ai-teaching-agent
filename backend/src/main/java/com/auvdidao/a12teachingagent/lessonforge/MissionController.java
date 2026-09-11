package com.auvdidao.a12teachingagent.lessonforge;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.domain.mission.MissionFileKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static com.auvdidao.a12teachingagent.lessonforge.MissionDtos.*;

@RestController
@RequestMapping("/api/v1/lessonforge")
public class MissionController {
    private final MissionService service;
    public MissionController(MissionService service) { this.service = service; }

    @GetMapping("/teachers") public ApiResponse<List<TeacherView>> teachers() { return ApiResponse.success(service.teachers()); }
    @GetMapping("/missions") public ApiResponse<List<MissionView>> list() { return ApiResponse.success(service.list()); }
    @PostMapping("/missions") public ApiResponse<MissionView> create(@Valid @RequestBody CreateRequest request) { return ApiResponse.success(service.create(request)); }
    @GetMapping("/missions/{id}") public ApiResponse<MissionView> get(@PathVariable("id") @Positive Long id) { return ApiResponse.success(service.get(id)); }
    @PostMapping("/missions/{id}/accept") public ApiResponse<MissionView> accept(@PathVariable("id") @Positive Long id) { return ApiResponse.success(service.accept(id)); }
    @PostMapping("/missions/{id}/reject") public ApiResponse<MissionView> reject(@PathVariable("id") @Positive Long id, @Valid @RequestBody DecisionRequest request) { return ApiResponse.success(service.reject(id, request)); }
    @PutMapping("/missions/{id}/model-connection") public ApiResponse<MissionView> selectConnection(@PathVariable("id") @Positive Long id, @Valid @RequestBody ConnectionSelectionRequest request) { return ApiResponse.success(service.selectConnection(id, request)); }
    @GetMapping("/missions/{id}/conversation") public ApiResponse<List<MessageView>> conversation(@PathVariable("id") @Positive Long id) { return ApiResponse.success(service.conversation(id)); }
    @PostMapping("/missions/{id}/conversation/messages") public ApiResponse<MessageView> message(@PathVariable("id") @Positive Long id, @Valid @RequestBody MessageRequest request) { return ApiResponse.success(service.sendMessage(id, request)); }
    @GetMapping("/missions/{id}/context-files") public ApiResponse<List<ContextFileView>> files(@PathVariable("id") @Positive Long id) { return ApiResponse.success(service.listFiles(id)); }
    @PostMapping(value = "/missions/{id}/context-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ContextFileView> upload(@PathVariable("id") @Positive Long id, @RequestPart("file") MultipartFile file, @RequestParam(name = "kind", required = false) MissionFileKind kind) { return ApiResponse.success(service.uploadFile(id, file, kind)); }
    @DeleteMapping("/missions/{id}/context-files/{fileId}") public ApiResponse<Void> deleteFile(@PathVariable("id") @Positive Long id, @PathVariable("fileId") @Positive Long fileId) { service.deleteFile(id, fileId); return ApiResponse.success(); }
    @GetMapping("/missions/{id}/context-files/{fileId}/download") public ResponseEntity<Resource> downloadContext(@PathVariable("id") @Positive Long id, @PathVariable("fileId") @Positive Long fileId) { return fileResponse(service.downloadContext(id, fileId)); }
    @PostMapping(value = "/missions/{id}/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SubmissionView> submit(@PathVariable("id") @Positive Long id, @RequestPart("file") MultipartFile file, @RequestParam(name = "reviewerId", required = false) @Positive Long reviewerId) { return ApiResponse.success(service.submit(id, file, reviewerId)); }
    @GetMapping("/missions/{id}/submissions") public ApiResponse<List<SubmissionView>> submissions(@PathVariable("id") @Positive Long id) { return ApiResponse.success(service.leaderSubmissions(id)); }
    @GetMapping("/missions/{id}/feedback") public ApiResponse<List<SubmissionView>> feedback(@PathVariable("id") @Positive Long id) { return ApiResponse.success(service.myFeedback(id)); }
    @PostMapping("/missions/{id}/submissions/{submissionId}/review") public ApiResponse<SubmissionView> review(@PathVariable("id") @Positive Long id, @PathVariable("submissionId") @Positive Long submissionId, @Valid @RequestBody ReviewRequest request) { return ApiResponse.success(service.review(id, submissionId, request)); }
    @GetMapping("/missions/{id}/submissions/{submissionId}/download") public ResponseEntity<Resource> downloadSubmission(@PathVariable("id") @Positive Long id, @PathVariable("submissionId") @Positive Long submissionId) { return fileResponse(service.downloadSubmission(id, submissionId)); }
    private ResponseEntity<Resource> fileResponse(MissionService.Download download) { return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.contentType())).contentLength(download.size()).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.fileName(), StandardCharsets.UTF_8).build().toString()).header("X-Content-Type-Options", "nosniff").body(download.resource()); }
}
