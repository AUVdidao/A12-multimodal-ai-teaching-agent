package com.auvdidao.a12teachingagent.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(A12SecurityProperties.class)
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BearerTokenAuthenticationFilter bearerFilter,
            ApiSecurityErrorHandler errorHandler,
            A12SecurityProperties properties
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        if (!properties.isEnabled()) {
            http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(requests -> requests
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/health", "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                    .requestMatchers("/h2-console/**").denyAll()
                    .requestMatchers("/api/v1/auth/**").authenticated()
                    .requestMatchers("/api/v1/**").authenticated()
                    .requestMatchers("/api/**").hasRole("TEACHER")
                    .anyRequest().permitAll());
        }

        http.addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
