package com.auvdidao.a12teachingagent.qa.dto;

import com.auvdidao.a12teachingagent.domain.qa.QuestionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class QuestionDtos {

    private QuestionDtos() {
    }

    public record CreateQuestionRequest(
            @NotNull @Positive Long publicationId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 5000) String content
    ) {
    }

    public record CreateAnswerRequest(
            @NotBlank @Size(max = 5000) String content
    ) {
    }

    public record UpdateQuestionStatusRequest(
            @NotBlank @Size(max = 20) String status
    ) {
    }

    public record QuestionAnswerResponse(
            Long id,
            Long questionId,
            Long teacherId,
            String teacherName,
            String content,
            LocalDateTime createdAt
    ) {
    }

    public record QuestionResponse(
            Long id,
            Long publicationId,
            Long projectId,
            Long studentId,
            String studentName,
            String title,
            String content,
            QuestionStatus status,
            LocalDateTime answeredAt,
            LocalDateTime closedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<QuestionAnswerResponse> answers
    ) {
    }
}
