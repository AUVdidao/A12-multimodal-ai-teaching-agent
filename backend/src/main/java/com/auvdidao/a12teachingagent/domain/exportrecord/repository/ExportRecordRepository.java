package com.auvdidao.a12teachingagent.domain.exportrecord.repository;

import com.auvdidao.a12teachingagent.domain.exportrecord.ExportRecord;
import com.auvdidao.a12teachingagent.domain.common.ExportType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExportRecordRepository extends JpaRepository<ExportRecord, Long> {

    List<ExportRecord> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    Optional<ExportRecord> findByProjectIdAndExportType(Long projectId, ExportType exportType);
}
