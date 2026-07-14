package com.auvdidao.a12teachingagent.security;

import com.auvdidao.a12teachingagent.domain.common.UserRole;

public record AuthenticatedUser(
        Long sessionId,
        Long userId,
        String username,
        String displayName,
        UserRole activeRole
) {
}
