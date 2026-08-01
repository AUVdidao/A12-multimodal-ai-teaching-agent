package com.auvdidao.a12teachingagent.clarification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClarificationQuestionRepository extends JpaRepository<ClarificationQuestionEntity, Long> {

    Optional<ClarificationQuestionEntity> findFirstByProjectIdAndStatusOrderByCreatedAtDescIdDesc(
            Long projectId,
            ClarificationQuestionStatus status
    );

    Optional<ClarificationQuestionEntity> findByQuestionId(String questionId);
}
