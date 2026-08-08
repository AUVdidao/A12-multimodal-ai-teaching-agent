package com.auvdidao.a12teachingagent.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2EnumCompatibilityMigrationTest {

    private JdbcTemplate jdbcTemplate;
    private H2EnumCompatibilityMigration migration;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:clarification-migration;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE clarification_questions (
                    id BIGINT PRIMARY KEY,
                    status ENUM('PENDING', 'ANSWERED') NOT NULL,
                    CONSTRAINT ck_legacy_status CHECK (status IN ('PENDING', 'ANSWERED'))
                )
                """);
        migration = new H2EnumCompatibilityMigration(dataSource, jdbcTemplate);
    }

    @Test
    void replacesLegacyConstraintAndPreservesExistingStatuses() {
        jdbcTemplate.update("INSERT INTO clarification_questions (id, status) VALUES (1, 'PENDING')");
        jdbcTemplate.update("INSERT INTO clarification_questions (id, status) VALUES (2, 'ANSWERED')");

        assertThrows(RuntimeException.class, () ->
                jdbcTemplate.update("INSERT INTO clarification_questions (id, status) VALUES (3, 'OBSOLETE')"));

        migration.migrateClarificationQuestionStatusIfNeeded();
        jdbcTemplate.update("INSERT INTO clarification_questions (id, status) VALUES (3, 'OBSOLETE')");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, status FROM clarification_questions ORDER BY id");
        assertEquals(List.of("PENDING", "ANSWERED", "OBSOLETE"),
                rows.stream().map(row -> row.get("STATUS").toString()).toList());
    }

    @Test
    void migrationIsIdempotentAndObsoleteCanBeMarked() {
        migration.migrateClarificationQuestionStatusIfNeeded();

        assertDoesNotThrow(() -> migration.migrateClarificationQuestionStatusIfNeeded());
        jdbcTemplate.update("INSERT INTO clarification_questions (id, status) VALUES (1, 'PENDING')");
        jdbcTemplate.update("UPDATE clarification_questions SET status = 'OBSOLETE' WHERE id = 1");

        assertEquals("OBSOLETE", jdbcTemplate.queryForObject(
                "SELECT status FROM clarification_questions WHERE id = 1", String.class));
    }

    @Test
    void backfillsNullSectionOrdersPerParseResultAndIsIdempotent() {
        createParseResultSectionsTable();
        insertSection(10L, "第一章", null);
        insertSection(10L, "第二章", null);
        insertSection(10L, "第三章", null);
        insertSection(20L, "甲", null);
        insertSection(20L, "乙", null);

        migration.migrateParseResultSectionOrderIfNeeded();
        List<Map<String, Object>> firstRun = sectionRows();

        assertEquals(List.of(
                        "10:第一章:0", "10:第二章:1", "10:第三章:2",
                        "20:甲:0", "20:乙:1"),
                formatSectionRows(firstRun));

        migration.migrateParseResultSectionOrderIfNeeded();
        assertEquals(firstRun, sectionRows());
    }

    @Test
    void preservesExistingOrdersAndFillsTheSmallestAvailableOrder() {
        createParseResultSectionsTable();
        insertSection(30L, "已有零", 0);
        insertSection(30L, "待回填", null);
        insertSection(30L, "已有二", 2);

        migration.migrateParseResultSectionOrderIfNeeded();

        assertEquals(List.of(
                        "30:已有零:0", "30:待回填:1", "30:已有二:2"),
                formatSectionRows(sectionRows()));
    }

    @Test
    void skipsWhenSectionOrderColumnDoesNotExist() {
        jdbcTemplate.execute("""
                CREATE TABLE parse_result_sections (
                    parse_result_id BIGINT NOT NULL,
                    section_value VARCHAR(255)
                )
                """);
        insertSectionWithoutOrder(40L, "第一章");

        assertDoesNotThrow(() -> migration.migrateParseResultSectionOrderIfNeeded());
        assertEquals("第一章", jdbcTemplate.queryForObject(
                "SELECT section_value FROM parse_result_sections WHERE parse_result_id = 40",
                String.class));
    }

    @Test
    void widensLegacySectionValuesAndPreservesLongDataIdempotently() {
        createParseResultSectionsTable();
        insertSection(50L, "short section", 0);
        String longSection = "long-section-content-".repeat(300);
        insertSection(50L, longSection.substring(0, 200), 1);

        migration.migrateParseResultSectionValueIfNeeded();

        assertTrue(isLongTextColumn(), "section_value should be a long text type after migration");
        jdbcTemplate.update("""
                UPDATE parse_result_sections
                SET section_value = ?
                WHERE parse_result_id = 50 AND section_order = 1
                """, longSection);
        assertEquals(longSection, jdbcTemplate.queryForObject("""
                SELECT section_value
                FROM parse_result_sections
                WHERE parse_result_id = 50 AND section_order = 1
                """, String.class));

        List<Map<String, Object>> firstRun = sectionRows();
        migration.migrateParseResultSectionValueIfNeeded();

        assertEquals(firstRun, sectionRows());
        assertTrue(isLongTextColumn(), "repeated migration must keep the long text type");
    }

    @Test
    void skipsWhenSectionTableOrValueColumnDoesNotExist() {
        assertDoesNotThrow(() -> migration.migrateParseResultSectionValueIfNeeded());

        jdbcTemplate.execute("""
            CREATE TABLE parse_result_sections (
                parse_result_id BIGINT NOT NULL
            )
            """);
        assertDoesNotThrow(() -> migration.migrateParseResultSectionValueIfNeeded());
    }

    @Test
    void skipsAlreadyLongSectionValueWithoutChangingRows() {
        jdbcTemplate.execute("""
                CREATE TABLE parse_result_sections (
                    parse_result_id BIGINT NOT NULL,
                    section_value CLOB,
                    section_order INT
                )
                """);
        String longSection = "already-long-".repeat(400);
        insertSection(60L, longSection, 0);

        String typeBefore = sectionValueType();
        migration.migrateParseResultSectionValueIfNeeded();

        assertEquals(typeBefore, sectionValueType());
        assertEquals(longSection, jdbcTemplate.queryForObject("""
                SELECT section_value FROM parse_result_sections
                WHERE parse_result_id = 60 AND section_order = 0
                """, String.class));
    }

    private void createParseResultSectionsTable() {
        jdbcTemplate.execute("""
                CREATE TABLE parse_result_sections (
                    parse_result_id BIGINT NOT NULL,
                    section_value VARCHAR(255),
                    section_order INT
                )
                """);
    }

    private void insertSection(Long parseResultId, String sectionValue, Integer sectionOrder) {
        jdbcTemplate.update("""
                INSERT INTO parse_result_sections (parse_result_id, section_value, section_order)
                VALUES (?, ?, ?)
                """, parseResultId, sectionValue, sectionOrder);
    }

    private void insertSectionWithoutOrder(Long parseResultId, String sectionValue) {
        jdbcTemplate.update("""
                INSERT INTO parse_result_sections (parse_result_id, section_value)
                VALUES (?, ?)
                """, parseResultId, sectionValue);
    }

    private List<Map<String, Object>> sectionRows() {
        return jdbcTemplate.queryForList("""
                SELECT parse_result_id, section_value, section_order
                FROM parse_result_sections
                ORDER BY parse_result_id, section_order
                """);
    }

    private List<String> formatSectionRows(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> row.get("PARSE_RESULT_ID") + ":"
                        + row.get("SECTION_VALUE") + ":"
                        + row.get("SECTION_ORDER"))
                .toList();
    }

    private boolean isLongTextColumn() {
        String type = sectionValueType().toUpperCase();
        return type.contains("CLOB") || type.contains("LARGE OBJECT") || type.contains("TEXT");
    }

    private String sectionValueType() {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(DATA_TYPE, '') || ':' || COALESCE(DECLARED_DATA_TYPE, '')
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = CURRENT_SCHEMA()
                  AND TABLE_NAME = 'PARSE_RESULT_SECTIONS'
                  AND COLUMN_NAME = 'SECTION_VALUE'
                """, String.class);
    }
}
