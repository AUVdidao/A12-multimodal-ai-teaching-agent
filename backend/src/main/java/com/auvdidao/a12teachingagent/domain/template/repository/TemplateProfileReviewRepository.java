package com.auvdidao.a12teachingagent.domain.template.repository;

import com.auvdidao.a12teachingagent.domain.template.TemplateProfileReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TemplateProfileReviewRepository extends JpaRepository<TemplateProfileReview, Long> {
    List<TemplateProfileReview> findByProfileVersionIdOrderByCreatedAtAsc(Long profileVersionId);
}


