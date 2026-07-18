package com.auvdidao.a12teachingagent.domain.publication.repository;

import com.auvdidao.a12teachingagent.domain.publication.Publication;
import com.auvdidao.a12teachingagent.domain.publication.PublicationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PublicationRepository extends JpaRepository<Publication, Long> {

    boolean existsByApprovalRequestIdAndClassId(Long approvalRequestId, Long classId);

    Optional<Publication> findByApprovalRequestIdAndClassId(Long approvalRequestId, Long classId);

    List<Publication> findByPublishedByOrderByPublishedAtDesc(Long publishedBy);

    List<Publication> findByPublishedByAndStatusOrderByPublishedAtDesc(
            Long publishedBy,
            PublicationStatus status
    );

    List<Publication> findByProjectIdInOrderByPublishedAtDesc(Collection<Long> projectIds);

    List<Publication> findByProjectIdInAndStatusOrderByPublishedAtDesc(
            Collection<Long> projectIds,
            PublicationStatus status
    );

    List<Publication> findByClassIdInAndStatusOrderByPublishedAtDesc(
            Collection<Long> classIds,
            PublicationStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select publication from Publication publication where publication.id = :id")
    Optional<Publication> findByIdForUpdate(@Param("id") Long id);
}
