package com.auvdidao.a12teachingagent.domain.project.repository;

import com.auvdidao.a12teachingagent.domain.project.ProjectVisit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectVisitRepository extends JpaRepository<ProjectVisit, Long> {

    Optional<ProjectVisit> findByUserIdAndProjectId(Long userId, Long projectId);

    List<ProjectVisit> findTop20ByUserIdOrderByLastVisitedAtDesc(Long userId);
}
