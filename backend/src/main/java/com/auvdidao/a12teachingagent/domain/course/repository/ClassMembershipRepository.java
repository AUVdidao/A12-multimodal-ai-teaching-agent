package com.auvdidao.a12teachingagent.domain.course.repository;

import com.auvdidao.a12teachingagent.domain.course.ClassMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClassMembershipRepository extends JpaRepository<ClassMembership, Long> {

    List<ClassMembership> findByStudentId(Long studentId);

    List<ClassMembership> findByClassId(Long classId);

    boolean existsByClassIdAndStudentId(Long classId, Long studentId);

    Optional<ClassMembership> findByClassIdAndStudentId(Long classId, Long studentId);
}
