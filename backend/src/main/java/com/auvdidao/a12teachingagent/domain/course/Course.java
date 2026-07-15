package com.auvdidao.a12teachingagent.domain.course;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "courses")
public class Course extends BaseAuditableEntity {

    @Column(nullable = false, unique = true, length = 40)
    private String courseCode;

    @Column(nullable = false, length = 120)
    private String courseName;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Long createdBy;

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
