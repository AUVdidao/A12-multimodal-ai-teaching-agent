package com.auvdidao.a12teachingagent.course.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class CourseDtos {

    private CourseDtos() {
    }

    public record CourseRequest(
            @NotBlank @Size(max = 40) String courseCode,
            @NotBlank @Size(max = 120) String courseName,
            @Size(max = 500) String description
    ) {
    }

    public record ClassGroupRequest(
            @NotBlank @Size(max = 120) String className,
            @Size(max = 80) String cohort,
            @Min(0) Integer studentCount
    ) {
    }

    public record CourseResponse(
            Long id,
            String courseCode,
            String courseName,
            String description,
            Long createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record ClassGroupResponse(
            Long id,
            Long courseId,
            String courseName,
            String className,
            String cohort,
            Integer studentCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record ClassMembershipRequest(
            @NotNull @Positive Long studentId
    ) {
    }

    public record ClassMembershipResponse(
            Long id,
            Long classId,
            Long studentId,
            String username,
            String displayName,
            LocalDateTime createdAt
    ) {
    }

    public record TeacherOption(Long id, String username, String displayName) {
    }

    public record CollaborationReferenceData(
            List<TeacherOption> teachers,
            List<TeacherOption> leaders,
            List<TeacherOption> students,
            List<CourseResponse> courses,
            List<ClassGroupResponse> classes
    ) {
    }
}
