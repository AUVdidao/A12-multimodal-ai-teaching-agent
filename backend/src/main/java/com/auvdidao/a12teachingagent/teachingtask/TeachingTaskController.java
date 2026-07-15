package com.auvdidao.a12teachingagent.teachingtask;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.domain.common.TeachingTaskStatus;
import com.auvdidao.a12teachingagent.teachingtask.dto.TeachingTaskDtos.TaskStatusRequest;
import com.auvdidao.a12teachingagent.teachingtask.dto.TeachingTaskDtos.TaskSubmissionRequest;
import com.auvdidao.a12teachingagent.teachingtask.dto.TeachingTaskDtos.TeachingTaskRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teaching-tasks")
public class TeachingTaskController {

    private final TeachingTaskService taskService;

    public TeachingTaskController(TeachingTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ApiResponse<?> create(@Valid @RequestBody TeachingTaskRequest request) {
        return ApiResponse.success(taskService.create(request));
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) TeachingTaskStatus status) {
        return ApiResponse.success(taskService.list(status));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<?> get(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.get(taskId));
    }

    @PutMapping("/{taskId}")
    public ApiResponse<?> update(
            @PathVariable Long taskId,
            @Valid @RequestBody TeachingTaskRequest request
    ) {
        return ApiResponse.success(taskService.update(taskId, request));
    }

    @PutMapping("/{taskId}/status")
    public ApiResponse<?> updateStatus(
            @PathVariable Long taskId,
            @Valid @RequestBody TaskStatusRequest request
    ) {
        return ApiResponse.success(taskService.updateStatus(taskId, request));
    }

    @PostMapping("/{taskId}/submit")
    public ApiResponse<?> submit(
            @PathVariable Long taskId,
            @Valid @RequestBody TaskSubmissionRequest request
    ) {
        return ApiResponse.success(taskService.submit(taskId, request));
    }
}
