package com.auvdidao.a12teachingagent.domain.course.repository;

import com.auvdidao.a12teachingagent.domain.course.ClassGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClassGroupRepository extends JpaRepository<ClassGroup, Long> {

    List<ClassGroup> findByCourseIdOrderByClassNameAsc(Long courseId);

    List<ClassGroup> findAllByOrderByClassNameAsc();

    Optional<ClassGroup> findByCourseIdAndClassNameIgnoreCase(Long courseId, String className);
}
