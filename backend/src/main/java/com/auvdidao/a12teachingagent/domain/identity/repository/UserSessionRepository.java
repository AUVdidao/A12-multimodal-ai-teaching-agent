package com.auvdidao.a12teachingagent.domain.identity.repository;

import com.auvdidao.a12teachingagent.domain.identity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    boolean existsByTokenHash(String tokenHash);
}
