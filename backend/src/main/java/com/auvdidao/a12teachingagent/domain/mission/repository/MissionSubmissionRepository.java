package com.auvdidao.a12teachingagent.domain.mission.repository;
import com.auvdidao.a12teachingagent.domain.mission.MissionSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface MissionSubmissionRepository extends JpaRepository<MissionSubmission, Long> { List<MissionSubmission> findByMissionIdOrderByCreatedAtDesc(Long missionId); List<MissionSubmission> findByMissionIdAndUploadedByOrderByCreatedAtDesc(Long missionId, Long uploadedBy); }
