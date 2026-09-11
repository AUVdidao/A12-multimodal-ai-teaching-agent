package com.auvdidao.a12teachingagent.domain.mission.repository;
import com.auvdidao.a12teachingagent.domain.mission.MissionConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface MissionConversationRepository extends JpaRepository<MissionConversation, Long> { Optional<MissionConversation> findByMissionId(Long missionId); }
