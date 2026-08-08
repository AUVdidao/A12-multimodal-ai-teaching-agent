package com.auvdidao.a12teachingagent.domain.material.repository;

import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ParseResultRepository extends JpaRepository<ParseResult, Long> {

    Optional<ParseResult> findFirstByMaterialIdOrderByCreatedAtDescIdDesc(Long materialId);

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
