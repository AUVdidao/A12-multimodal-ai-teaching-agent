package com.auvdidao.a12teachingagent.domain.exportrecord.repository;

import com.auvdidao.a12teachingagent.domain.exportrecord.ExportRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExportRecordRepository extends JpaRepository<ExportRecord, Long> {

    List<ExportRecord> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
