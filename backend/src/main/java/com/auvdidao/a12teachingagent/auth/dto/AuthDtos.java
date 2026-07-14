package com.auvdidao.a12teachingagent.auth.dto;

import com.auvdidao.a12teachingagent.domain.common.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "username is required")
            @Size(min = 3, max = 50, message = "username must contain 3 to 50 characters")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "username contains unsupported characters")
            String username,
            @NotBlank(message = "displayName is required")
            @Size(max = 100, message = "displayName must not exceed 100 characters")
            String displayName,
            @NotBlank(message = "password is required")
            @Size(min = 8, max = 72, message = "password must contain 8 to 72 characters")
            String password
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "username is required")
            String username,
            @NotBlank(message = "password is required")
            String password,
            UserRole activeRole
    ) {
    }

    public record SwitchRoleRequest(
            @NotNull(message = "role is required")
            UserRole role
    ) {
    }

    public record UserProfile(
            Long id,
            String username,
            String displayName,
            List<UserRole> roles,
            UserRole activeRole
    ) {
    }

    public record AuthSessionResponse(
            String token,
            LocalDateTime expiresAt,
            UserProfile user
    ) {
    }
}
