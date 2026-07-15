package com.auvdidao.a12teachingagent.domain.identity.repository;

import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignment, Long> {

    List<UserRoleAssignment> findByUserIdOrderByRoleAsc(Long userId);

    List<UserRoleAssignment> findByRoleOrderByUserIdAsc(UserRole role);

    boolean existsByUserIdAndRole(Long userId, UserRole role);
}
