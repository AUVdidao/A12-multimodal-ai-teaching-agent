package com.auvdidao.a12teachingagent.auth;

import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.security.A12SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class DemoIdentitySeeder implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoIdentitySeeder.class);

    private final A12SecurityProperties properties;
    private final AppUserRepository userRepository;
    private final UserRoleAssignmentRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoIdentitySeeder(
            A12SecurityProperties properties,
            AppUserRepository userRepository,
            UserRoleAssignmentRepository roleRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isDemoSeedEnabled()) {
            return;
        }
        A12SecurityProperties.Demo demo = properties.getDemo();
        upsert("leader", "教研负责人", demo.getLeaderPassword(), List.of(UserRole.LEADER));
        upsert("teacher", "张老师", demo.getTeacherPassword(), List.of(UserRole.TEACHER));
        upsert("student", "学生用户", demo.getStudentPassword(), List.of(UserRole.STUDENT));
        upsert("multi", "协同负责人", demo.getMultiPassword(), List.of(UserRole.TEACHER, UserRole.LEADER));
        LOGGER.info("Demo identity seed completed for leader, teacher, student and multi-role accounts");
    }

    private void upsert(String username, String displayName, String password, List<UserRole> roles) {
        if (password == null || password.isBlank()) {
            LOGGER.warn("Skipping demo account {} because its password is not configured", username);
            return;
        }
        AppUser user = userRepository.findByUsernameIgnoreCase(username).orElseGet(AppUser::new);
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(true);
        user = userRepository.save(user);

        for (UserRole role : roles) {
            if (!roleRepository.existsByUserIdAndRole(user.getId(), role)) {
                UserRoleAssignment assignment = new UserRoleAssignment();
                assignment.setUserId(user.getId());
                assignment.setRole(role);
                roleRepository.save(assignment);
            }
        }
    }
}
