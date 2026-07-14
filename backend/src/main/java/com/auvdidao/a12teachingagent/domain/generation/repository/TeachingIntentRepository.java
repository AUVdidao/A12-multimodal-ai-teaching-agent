package com.auvdidao.a12teachingagent.domain.generation.repository;

import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TeachingIntentRepository extends JpaRepository<TeachingIntent, Long> {

    Optional<TeachingIntent> findFirstByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);

    Optional<TeachingIntent> findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(
            Long projectId,
            TeachingIntentStatus status
    );
}
