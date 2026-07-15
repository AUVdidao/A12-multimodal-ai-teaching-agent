package com.auvdidao.a12teachingagent.domain.qa.repository;

import com.auvdidao.a12teachingagent.domain.qa.QuestionAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface QuestionAnswerRepository extends JpaRepository<QuestionAnswer, Long> {

    List<QuestionAnswer> findByQuestionIdInOrderByCreatedAtAsc(Collection<Long> questionIds);
}
