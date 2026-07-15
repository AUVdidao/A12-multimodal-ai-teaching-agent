package com.auvdidao.a12teachingagent.domain.teachingtask.repository;

import com.auvdidao.a12teachingagent.domain.common.TeachingTaskStatus;
import com.auvdidao.a12teachingagent.domain.teachingtask.TeachingTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeachingTaskRepository extends JpaRepository<TeachingTask, Long> {

    List<TeachingTask> findByCreatedByOrderByUpdatedAtDesc(Long createdBy);

    List<TeachingTask> findByCreatedByAndTaskStatusOrderByUpdatedAtDesc(
            Long createdBy,
            TeachingTaskStatus taskStatus
    );

    List<TeachingTask> findByAssigneeIdOrderByUpdatedAtDesc(Long assigneeId);

    List<TeachingTask> findByAssigneeIdAndTaskStatusOrderByUpdatedAtDesc(
            Long assigneeId,
            TeachingTaskStatus taskStatus
    );

    boolean existsByCreatedByAndTaskNameIgnoreCase(Long createdBy, String taskName);
}
