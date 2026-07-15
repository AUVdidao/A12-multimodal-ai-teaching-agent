package com.auvdidao.a12teachingagent.domain.course.repository;

import com.auvdidao.a12teachingagent.domain.course.Course;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {

    Optional<Course> findByCourseCodeIgnoreCase(String courseCode);

    List<Course> findAllByOrderByCourseNameAsc();
}
