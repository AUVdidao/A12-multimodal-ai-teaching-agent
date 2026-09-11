package com.auvdidao.a12teachingagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenAuthenticationService tokenService;
    private final A12SecurityProperties properties;

    public BearerTokenAuthenticationFilter(
            TokenAuthenticationService tokenService,
            A12SecurityProperties properties
    ) {
        this.tokenService = tokenService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (properties.isEnabled() && SecurityContextHolder.getContext().getAuthentication() == null) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
                String rawToken = authorization.substring(BEARER_PREFIX.length()).trim();
                if (isInternalServiceToken(rawToken, request)) {
                    AuthenticatedUser principal = internalPrincipal(request);
                    if (principal != null) {
                        String role = request.getRequestURI().startsWith("/api/v1/internal/")
                                ? "ROLE_INTERNAL_SERVICE" : "ROLE_TEACHER";
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                                principal, null, List.of(new SimpleGrantedAuthority(role))));
                    }
                } else {
                    tokenService.authenticate(rawToken).ifPresent(principal -> {
                        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + principal.activeRole().name());
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(principal, null, List.of(authority));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isInternalServiceToken(String rawToken, HttpServletRequest request) {
        String configured = properties.getInternalServiceToken();
        if (configured == null || configured.isBlank() || rawToken == null || rawToken.isBlank()) return false;
        if (!MessageDigest.isEqual(configured.strip().getBytes(StandardCharsets.UTF_8), rawToken.getBytes(StandardCharsets.UTF_8))) return false;
        return request.getRequestURI().startsWith("/api/");
    }

    private AuthenticatedUser internalPrincipal(HttpServletRequest request) {
        Long actor = parsePositive(request.getHeader("X-LessonForge-Actor-User-Id"));
        if (request.getRequestURI().startsWith("/api/v1/internal/")) {
            return new AuthenticatedUser(null, actor, "lessonforge-service", "LessonForge service", UserRole.TEACHER);
        }
        return actor == null ? null : new AuthenticatedUser(null, actor, "lessonforge-service", "LessonForge service", UserRole.TEACHER);
    }

    private Long parsePositive(String raw) {
        try {
            long value = Long.parseLong(raw == null ? "" : raw.strip());
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
