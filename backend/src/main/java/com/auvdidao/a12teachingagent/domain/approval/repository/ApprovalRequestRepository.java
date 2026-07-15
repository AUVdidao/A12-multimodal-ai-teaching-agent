package com.auvdidao.a12teachingagent.domain.approval.repository;

import com.auvdidao.a12teachingagent.domain.approval.ApprovalRequest;
import com.auvdidao.a12teachingagent.domain.approval.ApprovalStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {

    List<ApprovalRequest> findBySubmittedByOrderByCreatedAtDesc(Long submittedBy);

    List<ApprovalRequest> findBySubmittedByAndStatusOrderByCreatedAtDesc(
            Long submittedBy,
            ApprovalStatus status
    );

    List<ApprovalRequest> findByReviewerIdOrderByCreatedAtDesc(Long reviewerId);

    List<ApprovalRequest> findByReviewerIdAndStatusOrderByCreatedAtDesc(
            Long reviewerId,
            ApprovalStatus status
    );

    boolean existsByActiveArtifactVersionId(Long artifactVersionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ApprovalRequest request where request.id = :id")
    Optional<ApprovalRequest> findByIdForUpdate(@Param("id") Long id);
}
