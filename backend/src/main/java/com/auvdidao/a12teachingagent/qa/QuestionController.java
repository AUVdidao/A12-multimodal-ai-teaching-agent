package com.auvdidao.a12teachingagent.qa;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.qa.dto.QuestionDtos.CreateAnswerRequest;
import com.auvdidao.a12teachingagent.qa.dto.QuestionDtos.CreateQuestionRequest;
import com.auvdidao.a12teachingagent.qa.dto.QuestionDtos.QuestionResponse;
import com.auvdidao.a12teachingagent.qa.dto.QuestionDtos.UpdateQuestionStatusRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @PostMapping
    public ApiResponse<QuestionResponse> create(@Valid @RequestBody CreateQuestionRequest request) {
        return ApiResponse.success(questionService.create(request));
    }

    @GetMapping
    public ApiResponse<List<QuestionResponse>> list(
            @RequestParam(required = false) Long publicationId,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.success(questionService.list(publicationId, status));
    }

    @GetMapping("/{questionId}")
    public ApiResponse<QuestionResponse> get(@PathVariable Long questionId) {
        return ApiResponse.success(questionService.get(questionId));
    }

    @PostMapping("/{questionId}/answers")
    public ApiResponse<QuestionResponse> answer(
            @PathVariable Long questionId,
            @Valid @RequestBody CreateAnswerRequest request
    ) {
        return ApiResponse.success(questionService.answer(questionId, request));
    }

    @PutMapping("/{questionId}/status")
    public ApiResponse<QuestionResponse> updateStatus(
            @PathVariable Long questionId,
            @Valid @RequestBody UpdateQuestionStatusRequest request
    ) {
        return ApiResponse.success(questionService.updateStatus(questionId, request));
    }
}
