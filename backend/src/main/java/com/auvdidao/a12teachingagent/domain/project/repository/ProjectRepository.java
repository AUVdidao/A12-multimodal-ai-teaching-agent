package com.auvdidao.a12teachingagent.domain.project.repository;

import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.project.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :projectId")
    Optional<Project> findByIdForUpdate(@Param("projectId") Long projectId);
}
