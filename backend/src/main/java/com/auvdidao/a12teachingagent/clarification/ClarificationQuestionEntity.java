package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "clarification_questions")
public class ClarificationQuestionEntity extends BaseCreatedEntity {

    private String questionId;
    private Long projectId;
    private String targetField;
    private String question;

    @Enumerated(EnumType.STRING)
    private ClarificationQuestionStatus status = ClarificationQuestionStatus.PENDING;

    private LocalDateTime answeredAt;

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getTargetField() {
        return targetField;
    }

    public void setTargetField(String targetField) {
        this.targetField = targetField;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public ClarificationQuestionStatus getStatus() {
        return status;
    }

    public void setStatus(ClarificationQuestionStatus status) {
        this.status = status;
    }

    public LocalDateTime getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(LocalDateTime answeredAt) {
        this.answeredAt = answeredAt;
    }
}
