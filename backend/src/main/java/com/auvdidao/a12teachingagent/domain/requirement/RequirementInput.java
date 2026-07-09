package com.auvdidao.a12teachingagent.domain.requirement;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import com.auvdidao.a12teachingagent.domain.common.InputType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "requirement_inputs")
public class RequirementInput extends BaseAuditableEntity {

    private Long projectId;
    private String gradeLevel;
    private String subject;
    private String topic;
    private String lessonDuration;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String teachingGoals;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String keyPoints;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String difficultPoints;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String outputTypes;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String rawRequirementText;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    private InputType inputType;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getGradeLevel() {
        return gradeLevel;
    }

    public void setGradeLevel(String gradeLevel) {
        this.gradeLevel = gradeLevel;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getLessonDuration() {
        return lessonDuration;
    }

    public void setLessonDuration(String lessonDuration) {
        this.lessonDuration = lessonDuration;
    }

    public String getTeachingGoals() {
        return teachingGoals;
    }

    public void setTeachingGoals(String teachingGoals) {
        this.teachingGoals = teachingGoals;
    }

    public String getKeyPoints() {
        return keyPoints;
    }

    public void setKeyPoints(String keyPoints) {
        this.keyPoints = keyPoints;
    }

    public String getDifficultPoints() {
        return difficultPoints;
    }

    public void setDifficultPoints(String difficultPoints) {
        this.difficultPoints = difficultPoints;
    }

    public String getOutputTypes() {
        return outputTypes;
    }

    public void setOutputTypes(String outputTypes) {
        this.outputTypes = outputTypes;
    }

    public String getRawRequirementText() {
        return rawRequirementText;
    }

    public void setRawRequirementText(String rawRequirementText) {
        this.rawRequirementText = rawRequirementText;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public InputType getInputType() {
        return inputType;
    }

    public void setInputType(InputType inputType) {
        this.inputType = inputType;
    }
}
