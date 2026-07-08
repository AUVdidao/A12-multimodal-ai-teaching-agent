package com.auvdidao.a12teachingagent.domain.knowledge.repository;

import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByCourseNameContainingIgnoreCase(String courseName);
}
