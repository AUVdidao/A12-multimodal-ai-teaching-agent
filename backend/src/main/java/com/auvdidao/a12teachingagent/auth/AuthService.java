package com.auvdidao.a12teachingagent.auth;

import com.auvdidao.a12teachingagent.auth.dto.AuthDtos.AuthSessionResponse;
import com.auvdidao.a12teachingagent.auth.dto.AuthDtos.LoginRequest;
import com.auvdidao.a12teachingagent.auth.dto.AuthDtos.RegisterRequest;
import com.auvdidao.a12teachingagent.auth.dto.AuthDtos.UserProfile;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.UnauthorizedException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.security.TokenAuthenticationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class AuthService {

    private static final List<UserRole> DEFAULT_ROLE_ORDER = List.of(
            UserRole.TEACHER,
            UserRole.LEADER,
            UserRole.STUDENT
    );

    private final AppUserRepository userRepository;
    private final UserRoleAssignmentRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenAuthenticationService tokenService;
    private final CurrentUserService currentUserService;

    public AuthService(
            AppUserRepository userRepository,
            UserRoleAssignmentRepository roleRepository,
            PasswordEncoder passwordEncoder,
            TokenAuthenticationService tokenService,
            CurrentUserService currentUserService
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public AuthSessionResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.username());
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException("Username is already registered");
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEnabled(true);
        user = userRepository.save(user);

        UserRoleAssignment assignment = new UserRoleAssignment();
        assignment.setUserId(user.getId());
        assignment.setRole(UserRole.STUDENT);
        roleRepository.save(assignment);

        return issueSession(user, UserRole.STUDENT);
    }

    @Transactional
    public AuthSessionResponse login(LoginRequest request) {
        AppUser user = userRepository.findByUsernameIgnoreCase(normalizeUsername(request.username()))
                .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
                .orElseThrow(this::invalidCredentials);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        List<UserRole> roles = rolesFor(user.getId());
        if (roles.isEmpty()) {
            throw new ForbiddenException("No platform role is assigned to this account");
        }
        UserRole activeRole = request.activeRole() == null ? preferredRole(roles) : request.activeRole();
        if (!roles.contains(activeRole)) {
            throw new ForbiddenException("The requested role is not assigned to this user");
        }
        return issueSession(user, activeRole);
    }

    @Transactional(readOnly = true)
    public UserProfile me() {
        AuthenticatedUser principal = currentUserService.requireUser();
        AppUser user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
        return profile(user, principal.activeRole());
    }

    @Transactional
    public UserProfile switchRole(UserRole role) {
        if (role == null) {
            throw new ForbiddenException("A target role is required");
        }
        AuthenticatedUser current = currentUserService.requireUser();
        AuthenticatedUser switched = tokenService.switchRole(current.sessionId(), current.userId(), role);
        AppUser user = userRepository.findById(switched.userId())
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
        return profile(user, switched.activeRole());
    }

    @Transactional
    public void logout() {
        AuthenticatedUser principal = currentUserService.requireUser();
        tokenService.revoke(principal.sessionId(), principal.userId());
    }

    private AuthSessionResponse issueSession(AppUser user, UserRole activeRole) {
        TokenAuthenticationService.IssuedToken issued = tokenService.issue(user, activeRole);
        return new AuthSessionResponse(
                issued.rawToken(),
                issued.session().getExpiresAt(),
                profile(user, activeRole)
        );
    }

    private UserProfile profile(AppUser user, UserRole activeRole) {
        return new UserProfile(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                rolesFor(user.getId()),
                activeRole
        );
    }

    private List<UserRole> rolesFor(Long userId) {
        return roleRepository.findByUserIdOrderByRoleAsc(userId).stream()
                .map(UserRoleAssignment::getRole)
                .distinct()
                .sorted(Comparator.comparingInt(DEFAULT_ROLE_ORDER::indexOf))
                .toList();
    }

    private UserRole preferredRole(List<UserRole> roles) {
        return DEFAULT_ROLE_ORDER.stream().filter(roles::contains).findFirst().orElse(roles.get(0));
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("Username or password is incorrect");
    }
}
