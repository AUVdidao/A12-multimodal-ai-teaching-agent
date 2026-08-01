package com.auvdidao.a12teachingagent.clarification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ClarificationQuestionRepository extends JpaRepository<ClarificationQuestionEntity, Long> {

    Optional<ClarificationQuestionEntity> findFirstByProjectIdAndStatusOrderByCreatedAtDescIdDesc(
            Long projectId,
            ClarificationQuestionStatus status
    );

    Optional<ClarificationQuestionEntity> findByQuestionId(String questionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from ClarificationQuestionEntity q where q.questionId = :questionId")
    Optional<ClarificationQuestionEntity> findByQuestionIdForUpdate(@Param("questionId") String questionId);
}
