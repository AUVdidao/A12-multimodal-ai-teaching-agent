package com.auvdidao.a12teachingagent.course;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.course.dto.CourseDtos.ClassGroupRequest;
import com.auvdidao.a12teachingagent.course.dto.CourseDtos.ClassMembershipRequest;
import com.auvdidao.a12teachingagent.course.dto.CourseDtos.CourseRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @PostMapping("/courses")
    public ApiResponse<?> createCourse(@Valid @RequestBody CourseRequest request) {
        return ApiResponse.success(courseService.createCourse(request));
    }

    @GetMapping("/courses")
    public ApiResponse<?> listCourses() {
        return ApiResponse.success(courseService.listCourses());
    }

    @PostMapping("/courses/{courseId}/classes")
    public ApiResponse<?> createClassGroup(
            @PathVariable Long courseId,
            @Valid @RequestBody ClassGroupRequest request
    ) {
        return ApiResponse.success(courseService.createClassGroup(courseId, request));
    }

    @GetMapping("/classes")
    public ApiResponse<?> listClasses(@RequestParam(required = false) Long courseId) {
        return ApiResponse.success(courseService.listClasses(courseId));
    }

    @GetMapping("/classes/{classId}/members")
    public ApiResponse<?> listClassMembers(@PathVariable Long classId) {
        return ApiResponse.success(courseService.listClassMembers(classId));
    }

    @PostMapping("/classes/{classId}/members")
    public ApiResponse<?> addClassMember(
            @PathVariable Long classId,
            @Valid @RequestBody ClassMembershipRequest request
    ) {
        return ApiResponse.success(courseService.addClassMember(classId, request.studentId()));
    }

    @DeleteMapping("/classes/{classId}/members/{studentId}")
    public ApiResponse<Void> removeClassMember(
            @PathVariable Long classId,
            @PathVariable Long studentId
    ) {
        courseService.removeClassMember(classId, studentId);
        return ApiResponse.success();
    }

    @GetMapping("/collaboration/reference-data")
    public ApiResponse<?> collaborationReferenceData() {
        return ApiResponse.success(courseService.referenceData());
    }
}
