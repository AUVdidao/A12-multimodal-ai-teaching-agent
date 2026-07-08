package com.auvdidao.a12teachingagent.domain.project;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "projects")
public class Project extends BaseAuditableEntity {

    private String projectName;
    private String courseName;
    private String chapterTopic;
    private String targetAudience;
    private Integer lessonDurationMinutes;

    @Enumerated(EnumType.STRING)
    private GenerationMode generationMode;

    @Enumerated(EnumType.STRING)
    private ProjectStatus status;

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
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

    public GenerationMode getGenerationMode() {
        return generationMode;
    }

    public void setGenerationMode(GenerationMode generationMode) {
        this.generationMode = generationMode;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectStatus status) {
        this.status = status;
    }
}
