package com.auvdidao.a12teachingagent.ai.connection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelConnectionRepository extends JpaRepository<ModelConnectionEntity, Long> {
    List<ModelConnectionEntity> findAllByOwnerUserIdOrderByCreatedAtAscIdAsc(Long ownerUserId);
    Optional<ModelConnectionEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);
    boolean existsByOwnerUserIdAndNameIgnoreCase(Long ownerUserId, String name);
}
