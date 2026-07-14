package com.auvdidao.a12teachingagent.security;

import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.UnauthorizedException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
public class CurrentUserService {

    public AuthenticatedUser requireUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new UnauthorizedException("Authentication is required");
        }
        return principal;
    }

    public AuthenticatedUser requireRole(UserRole... roles) {
        AuthenticatedUser principal = requireUser();
        if (Arrays.stream(roles).noneMatch(role -> role == principal.activeRole())) {
            throw new ForbiddenException("The active role is not allowed to perform this operation");
        }
        return principal;
    }
}
