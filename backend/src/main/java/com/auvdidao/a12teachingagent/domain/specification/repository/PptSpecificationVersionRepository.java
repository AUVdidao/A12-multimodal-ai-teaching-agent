package com.auvdidao.a12teachingagent.domain.specification.repository;

import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PptSpecificationVersionRepository extends JpaRepository<PptSpecificationVersion, Long> {
    List<PptSpecificationVersion> findByProjectIdOrderByVersionNumberAsc(Long projectId);

    Optional<PptSpecificationVersion> findFirstByProjectIdOrderByVersionNumberDesc(Long projectId);

    List<PptSpecificationVersion> findBySpecificationIdOrderByVersionNumberDesc(String specificationId);

    Optional<PptSpecificationVersion> findByIdAndProjectId(Long id, Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PptSpecificationVersion v where v.id = :id and v.projectId = :projectId")
    Optional<PptSpecificationVersion> findByIdAndProjectIdForUpdate(
            @Param("id") Long id, @Param("projectId") Long projectId
    );
}


