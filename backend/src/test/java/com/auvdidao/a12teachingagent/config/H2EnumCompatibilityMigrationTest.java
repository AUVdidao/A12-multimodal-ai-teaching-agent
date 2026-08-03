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
}
