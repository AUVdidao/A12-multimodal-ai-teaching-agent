package com.auvdidao.a12teachingagent.publication;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.publication.dto.PublicationDtos.LearningTaskDetail;
import com.auvdidao.a12teachingagent.publication.dto.PublicationDtos.LearningTaskSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/student/learning-tasks")
public class StudentLearningTaskController {

    private final PublicationService publicationService;

    public StudentLearningTaskController(PublicationService publicationService) {
        this.publicationService = publicationService;
    }

    @GetMapping
    public ApiResponse<List<LearningTaskSummary>> list() {
        return ApiResponse.success(publicationService.listLearningTasks());
    }

    @GetMapping("/{publicationId}")
    public ApiResponse<LearningTaskDetail> get(@PathVariable Long publicationId) {
        return ApiResponse.success(publicationService.getLearningTask(publicationId));
    }
}
