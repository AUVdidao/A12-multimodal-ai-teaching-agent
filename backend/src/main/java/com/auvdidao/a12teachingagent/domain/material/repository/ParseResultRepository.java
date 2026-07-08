package com.auvdidao.a12teachingagent.domain.material.repository;

import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParseResultRepository extends JpaRepository<ParseResult, Long> {

    Optional<ParseResult> findFirstByMaterialIdOrderByCreatedAtDesc(Long materialId);
}
