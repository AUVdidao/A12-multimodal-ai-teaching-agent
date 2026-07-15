package com.auvdidao.a12teachingagent.domain.course;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "class_groups",
        uniqueConstraints = @UniqueConstraint(name = "uk_course_class_name", columnNames = {"course_id", "class_name"})
)
public class ClassGroup extends BaseAuditableEntity {

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "class_name", nullable = false, length = 120)
    private String className;

    @Column(length = 80)
    private String cohort;

    @Column(nullable = false)
    private Integer studentCount = 0;

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getCohort() {
        return cohort;
    }

    public void setCohort(String cohort) {
        this.cohort = cohort;
    }

    public Integer getStudentCount() {
        return studentCount;
    }

    public void setStudentCount(Integer studentCount) {
        this.studentCount = studentCount;
    }
}
