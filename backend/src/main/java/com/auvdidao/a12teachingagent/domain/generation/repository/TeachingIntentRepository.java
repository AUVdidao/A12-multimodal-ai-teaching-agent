package com.auvdidao.a12teachingagent.domain.generation.repository;

import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TeachingIntentRepository extends JpaRepository<TeachingIntent, Long> {

    Optional<TeachingIntent> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);
}
