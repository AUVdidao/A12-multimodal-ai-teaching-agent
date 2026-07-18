package com.auvdidao.a12teachingagent.domain.qa.repository;

import com.auvdidao.a12teachingagent.domain.qa.Question;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    List<Question> findByStudentIdOrderByUpdatedAtDesc(Long studentId);

    List<Question> findByProjectIdInOrderByUpdatedAtDesc(Collection<Long> projectIds);

    List<Question> findByPublicationIdInOrderByUpdatedAtDesc(Collection<Long> publicationIds);

    Optional<Question> findByPublicationIdAndStudentIdAndTitleIgnoreCase(
            Long publicationId,
            Long studentId,
            String title
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select question from Question question where question.id = :id")
    Optional<Question> findByIdForUpdate(@Param("id") Long id);
}
