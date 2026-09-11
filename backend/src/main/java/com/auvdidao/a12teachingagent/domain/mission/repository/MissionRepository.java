package com.auvdidao.a12teachingagent.domain.mission.repository;

import com.auvdidao.a12teachingagent.domain.mission.Mission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MissionRepository extends JpaRepository<Mission, Long> {
    List<Mission> findByAssignedTeacherIdOrderByUpdatedAtDesc(Long teacherId);
    List<Mission> findByCreatedByLeaderIdOrderByUpdatedAtDesc(Long leaderId);
}
