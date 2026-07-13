package com.auvdidao.a12teachingagent.domain.requirement;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "requirement_summaries")
public class RequirementSummary extends BaseAuditableEntity {

    private Long projectId;
    private Long sourceRequirementId;
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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "requirement_summary_output_types",
            joinColumns = @JoinColumn(name = "summary_id")
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "output_type")
    private List<String> outputTypes = new ArrayList<>();

    private String stylePreference;
    private String interactionType;

    @Enumerated(EnumType.STRING)
    private GenerationMode generationMode;

    @Enumerated(EnumType.STRING)
    private RequirementSummaryStatus status;

    private LocalDateTime confirmedAt;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getSourceRequirementId() {
        return sourceRequirementId;
    }

    public void setSourceRequirementId(Long sourceRequirementId) {
        this.sourceRequirementId = sourceRequirementId;
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

    public List<String> getOutputTypes() {
        return List.copyOf(outputTypes);
    }

    public void setOutputTypes(List<String> outputTypes) {
        this.outputTypes = outputTypes == null ? new ArrayList<>() : new ArrayList<>(outputTypes);
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

    public GenerationMode getGenerationMode() {
        return generationMode;
    }

    public void setGenerationMode(GenerationMode generationMode) {
        this.generationMode = generationMode;
    }

    public RequirementSummaryStatus getStatus() {
        return status;
    }

    public void setStatus(RequirementSummaryStatus status) {
        this.status = status;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
