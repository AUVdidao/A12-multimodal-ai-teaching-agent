package com.auvdidao.a12teachingagent.domain.requirement;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "requirement_summaries")
public class RequirementSummary extends BaseAuditableEntity {

    private Long projectId;
    private String courseName;
    private String chapterTopic;
    private String targetAudience;
    private Integer lessonDurationMinutes;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String teachingGoals;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String knowledgePoints;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String keyDifficulties;

    private String coursewareStyle;
    private String interactionType;
    private String outputTypes;
    private Boolean confirmed;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getChapterTopic() {
        return chapterTopic;
    }

    public void setChapterTopic(String chapterTopic) {
        this.chapterTopic = chapterTopic;
    }

    public String getTargetAudience() {
        return targetAudience;
    }

    public void setTargetAudience(String targetAudience) {
        this.targetAudience = targetAudience;
    }

    public Integer getLessonDurationMinutes() {
        return lessonDurationMinutes;
    }

    public void setLessonDurationMinutes(Integer lessonDurationMinutes) {
        this.lessonDurationMinutes = lessonDurationMinutes;
    }

    public String getTeachingGoals() {
        return teachingGoals;
    }

    public void setTeachingGoals(String teachingGoals) {
        this.teachingGoals = teachingGoals;
    }

    public String getKnowledgePoints() {
        return knowledgePoints;
    }

    public void setKnowledgePoints(String knowledgePoints) {
        this.knowledgePoints = knowledgePoints;
    }

    public String getKeyDifficulties() {
        return keyDifficulties;
    }

    public void setKeyDifficulties(String keyDifficulties) {
        this.keyDifficulties = keyDifficulties;
    }

    public String getCoursewareStyle() {
        return coursewareStyle;
    }

    public void setCoursewareStyle(String coursewareStyle) {
        this.coursewareStyle = coursewareStyle;
    }

    public String getInteractionType() {
        return interactionType;
    }

    public void setInteractionType(String interactionType) {
        this.interactionType = interactionType;
    }

    public String getOutputTypes() {
        return outputTypes;
    }

    public void setOutputTypes(String outputTypes) {
        this.outputTypes = outputTypes;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }
}
