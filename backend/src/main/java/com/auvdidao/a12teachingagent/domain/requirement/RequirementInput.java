package com.auvdidao.a12teachingagent.domain.requirement;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import com.auvdidao.a12teachingagent.domain.common.InputType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "requirement_inputs")
public class RequirementInput extends BaseAuditableEntity {

    private Long projectId;
    private String gradeLevel;
    private String subject;
    private String topic;
    private String baselineLevel;
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

    private String stylePreference;
    private String interactionType;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "requirement_input_output_types",
            joinColumns = @JoinColumn(name = "requirement_id")
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "output_type")
    private List<String> outputTypes = new ArrayList<>();

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

    public String getBaselineLevel() {
        return baselineLevel;
    }

    public void setBaselineLevel(String baselineLevel) {
        this.baselineLevel = baselineLevel;
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

    public String getStylePreference() {
        return stylePreference;
    }

    public void setStylePreference(String stylePreference) {
        this.stylePreference = stylePreference;
    }

    public String getInteractionType() {
        return interactionType;
    }

    public void setInteractionType(String interactionType) {
        this.interactionType = interactionType;
    }

    public List<String> getOutputTypes() {
        return List.copyOf(outputTypes);
    }

    public void setOutputTypes(List<String> outputTypes) {
        this.outputTypes = outputTypes == null ? new ArrayList<>() : new ArrayList<>(outputTypes);
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
