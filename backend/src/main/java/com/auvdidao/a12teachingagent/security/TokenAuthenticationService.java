package com.auvdidao.a12teachingagent.security;

import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.UnauthorizedException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserSession;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class TokenAuthenticationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final UserSessionRepository sessionRepository;
    private final AppUserRepository userRepository;
    private final UserRoleAssignmentRepository roleRepository;
    private final A12SecurityProperties properties;

    public TokenAuthenticationService(
            UserSessionRepository sessionRepository,
            AppUserRepository userRepository,
            UserRoleAssignmentRepository roleRepository,
            A12SecurityProperties properties
    ) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.properties = properties;
    }

    @Transactional
    public IssuedToken issue(AppUser user, UserRole activeRole) {
        if (!roleRepository.existsByUserIdAndRole(user.getId(), activeRole)) {
            throw new ForbiddenException("The requested role is not assigned to this user");
        }

        String rawToken;
        String tokenHash;
        do {
            byte[] bytes = new byte[TOKEN_BYTES];
            SECURE_RANDOM.nextBytes(bytes);
            rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            tokenHash = hash(rawToken);
        } while (sessionRepository.existsByTokenHash(tokenHash));

        LocalDateTime now = LocalDateTime.now();
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setTokenHash(tokenHash);
        session.setActiveRole(activeRole);
        session.setExpiresAt(now.plusHours(Math.max(1, properties.getSessionHours())));
        session.setLastUsedAt(now);
        session = sessionRepository.save(session);
        return new IssuedToken(rawToken, session);
    }

    @Transactional
    public Optional<AuthenticatedUser> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        UserSession session = sessionRepository.findByTokenHashAndRevokedAtIsNull(hash(rawToken)).orElse(null);
        if (session == null || !session.getExpiresAt().isAfter(LocalDateTime.now())) {
            return Optional.empty();
        }
        AppUser user = userRepository.findById(session.getUserId()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !roleRepository.existsByUserIdAndRole(user.getId(), session.getActiveRole())) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        if (session.getLastUsedAt() == null || session.getLastUsedAt().isBefore(now.minusMinutes(5))) {
            session.setLastUsedAt(now);
            sessionRepository.save(session);
        }
        return Optional.of(toPrincipal(session, user));
    }

    @Transactional
    public AuthenticatedUser switchRole(Long sessionId, Long userId, UserRole role) {
        UserSession session = requireActiveSession(sessionId, userId);
        if (!roleRepository.existsByUserIdAndRole(userId, role)) {
            throw new ForbiddenException("The requested role is not assigned to this user");
        }
        session.setActiveRole(role);
        session.setLastUsedAt(LocalDateTime.now());
        sessionRepository.save(session);
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
        return toPrincipal(session, user);
    }

    @Transactional
    public void revoke(Long sessionId, Long userId) {
        UserSession session = requireActiveSession(sessionId, userId);
        session.setRevokedAt(LocalDateTime.now());
        sessionRepository.save(session);
    }

    private UserSession requireActiveSession(Long sessionId, Long userId) {
        UserSession session = sessionRepository.findById(sessionId)
                .filter(item -> userId.equals(item.getUserId()))
                .filter(item -> item.getRevokedAt() == null)
                .filter(item -> item.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new UnauthorizedException("Authentication session is no longer active"));
        return session;
    }

    private static AuthenticatedUser toPrincipal(UserSession session, AppUser user) {
        return new AuthenticatedUser(
                session.getId(),
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                session.getActiveRole()
        );
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record IssuedToken(String rawToken, UserSession session) {
    }
}
