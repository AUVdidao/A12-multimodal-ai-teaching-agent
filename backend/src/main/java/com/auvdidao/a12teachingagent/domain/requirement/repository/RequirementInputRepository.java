package com.auvdidao.a12teachingagent.domain.requirement.repository;

import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequirementInputRepository extends JpaRepository<RequirementInput, Long> {

    List<RequirementInput> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    Optional<RequirementInput> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);
}
