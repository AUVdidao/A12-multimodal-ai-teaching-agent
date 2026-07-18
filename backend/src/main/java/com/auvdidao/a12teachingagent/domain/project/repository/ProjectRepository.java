package com.auvdidao.a12teachingagent.domain.project.repository;

import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByStatus(ProjectStatus status);

    List<Project> findAllByOrderByUpdatedAtDescCreatedAtDesc();

    List<Project> findByOwnerUserIdOrderByUpdatedAtDescCreatedAtDesc(Long ownerUserId);

    List<Project> findAllByDeletedAtIsNullOrderByUpdatedAtDescCreatedAtDesc();

    List<Project> findByOwnerUserIdAndDeletedAtIsNullOrderByUpdatedAtDescCreatedAtDesc(Long ownerUserId);

    List<Project> findByOwnerUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long ownerUserId);

    Optional<Project> findByOwnerUserIdAndProjectNameIgnoreCase(Long ownerUserId, String projectName);
}
