package com.auvdidao.a12teachingagent.domain.qa;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "question_answers",
        indexes = @Index(name = "idx_question_answer_question_created", columnList = "question_id,created_at")
)
public class QuestionAnswer extends BaseCreatedEntity {

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(nullable = false, length = 5000)
    private String content;

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Long getTeacherId() {
        return teacherId;
    }

    public void setTeacherId(Long teacherId) {
        this.teacherId = teacherId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
