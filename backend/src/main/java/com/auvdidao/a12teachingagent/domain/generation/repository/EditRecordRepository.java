package com.auvdidao.a12teachingagent.domain.generation.repository;

import com.auvdidao.a12teachingagent.domain.generation.EditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EditRecordRepository extends JpaRepository<EditRecord, Long> {

    List<EditRecord> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
