package com.auvdidao.a12teachingagent.domain.mission.repository;
import com.auvdidao.a12teachingagent.domain.mission.MissionMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface MissionMessageRepository extends JpaRepository<MissionMessage, Long> { List<MissionMessage> findByMissionIdOrderByCreatedAtAscIdAsc(Long missionId); }
