package com.auvdidao.a12teachingagent.domain.material.repository;

import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface ParseResultRepository extends JpaRepository<ParseResult, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ParseResult> findFirstByMaterialIdOrderByCreatedAtDescIdDesc(Long materialId);

    /**
     * Read-only callers must not inherit the write lock used by parse state
     * transitions.  Keeping this as an explicit query prevents a future
     * repository method rename from silently reintroducing SELECT FOR UPDATE
     * into the GET parse-result path.
     */
    @Query("""
            select result
            from ParseResult result
            where result.materialId = :materialId
            order by result.createdAt desc, result.id desc
            """)
    Optional<ParseResult> findLatestForRead(@Param("materialId") Long materialId);

    Optional<ParseResult> findByMaterialIdAndAnalysisRunIdAndSourceVersionIdAndParserSnapshotChecksum(
            Long materialId, String analysisRunId, Long sourceVersionId, String parserSnapshotChecksum);

    @Query(value = """
            select count(*)
            from parse_result_sections
            where parse_result_id = :parseResultId
            """, nativeQuery = true)
    long countSectionsByParseResultId(@Param("parseResultId") Long parseResultId);

    @Query(value = """
            select cast(section_value as varchar)
            from parse_result_sections
            where parse_result_id = :parseResultId
            order by section_order asc
            limit 6
            """, nativeQuery = true)
    List<String> findSectionsPreviewByParseResultId(@Param("parseResultId") Long parseResultId);
}
