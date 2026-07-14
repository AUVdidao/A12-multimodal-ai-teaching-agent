package com.auvdidao.a12teachingagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
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
                tokenService.authenticate(rawToken).ifPresent(principal -> {
                    SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + principal.activeRole().name());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, List.of(authority));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            }
        }
        filterChain.doFilter(request, response);
    }
}
