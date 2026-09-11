package com.auvdidao.a12teachingagent.domain.mission.repository;
import com.auvdidao.a12teachingagent.domain.mission.MissionContextFile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface MissionContextFileRepository extends JpaRepository<MissionContextFile, Long> { List<MissionContextFile> findByMissionIdOrderByCreatedAtAscIdAsc(Long missionId); Optional<MissionContextFile> findByIdAndMissionId(Long id, Long missionId); }
