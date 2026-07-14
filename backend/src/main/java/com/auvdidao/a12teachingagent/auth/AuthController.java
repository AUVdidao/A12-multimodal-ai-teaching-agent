package com.auvdidao.a12teachingagent.auth;

import com.auvdidao.a12teachingagent.auth.dto.AuthDtos.AuthSessionResponse;
import com.auvdidao.a12teachingagent.auth.dto.AuthDtos.LoginRequest;
import com.auvdidao.a12teachingagent.auth.dto.AuthDtos.RegisterRequest;
import com.auvdidao.a12teachingagent.auth.dto.AuthDtos.SwitchRoleRequest;
import com.auvdidao.a12teachingagent.auth.dto.AuthDtos.UserProfile;
import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthSessionResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthSessionResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserProfile> me() {
        return ApiResponse.success(authService.me());
    }

    @PostMapping("/switch-role")
    public ApiResponse<UserProfile> switchRole(@Valid @RequestBody SwitchRoleRequest request) {
        return ApiResponse.success(authService.switchRole(request.role()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.success();
    }
}
