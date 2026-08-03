package com.auvdidao.a12teachingagent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

@Component
public class H2EnumCompatibilityMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(H2EnumCompatibilityMigration.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public H2EnumCompatibilityMigration(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!"H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
        }

        // H2 persists Java enum value lists in the column definition. Varchar keeps
        // existing prototype data readable when supported file or usage values evolve.
        log.info("Applying H2 enum compatibility migration for material and knowledge columns");
        try {
            jdbcTemplate.execute("ALTER TABLE uploaded_materials ALTER COLUMN file_type VARCHAR(32)");
            jdbcTemplate.execute("ALTER TABLE material_purposes ALTER COLUMN purpose_type VARCHAR(48)");
            jdbcTemplate.execute("ALTER TABLE knowledge_chunk_usages ALTER COLUMN usage_type VARCHAR(48)");
            migrateClarificationQuestionStatusIfNeeded();
            log.info("H2 enum compatibility migration completed");
        } catch (RuntimeException exception) {
            log.error("H2 enum compatibility migration failed; application startup is stopped to protect existing data", exception);
            throw exception;
        }
    }

    /**
     * Replaces the CHECK constraint Hibernate generated for the clarification
     * question enum in older file databases. This only changes the constraint;
     * existing question rows are left untouched.
     */
    void migrateClarificationQuestionStatusIfNeeded() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = CURRENT_SCHEMA()
                  AND TABLE_NAME = 'CLARIFICATION_QUESTIONS'
                """, Integer.class);
        if (tableCount == null || tableCount == 0) {
            return;
        }

        // Older file databases may still use an H2 ENUM column here. The
        // legacy enum domain rejects OBSOLETE even after its CHECK constraint
        // is replaced, so widen the column before rebuilding that constraint.
        jdbcTemplate.execute("ALTER TABLE \"CLARIFICATION_QUESTIONS\" "
                + "ALTER COLUMN \"STATUS\" VARCHAR(32)");

        List<ConstraintDefinition> constraints = jdbcTemplate.query("""
                SELECT DISTINCT cc.CONSTRAINT_NAME, cc.CHECK_CLAUSE
                FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
                JOIN INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE ccu
                  ON ccu.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
                 AND ccu.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
                WHERE cc.CONSTRAINT_SCHEMA = CURRENT_SCHEMA()
                  AND ccu.TABLE_NAME = 'CLARIFICATION_QUESTIONS'
                  AND ccu.COLUMN_NAME = 'STATUS'
                """, (resultSet, rowNum) -> new ConstraintDefinition(
                resultSet.getString("CONSTRAINT_NAME"),
                resultSet.getString("CHECK_CLAUSE")));

        if (constraints.stream().anyMatch(this::allowsObsolete)) {
            return;
        }

        for (ConstraintDefinition constraint : constraints) {
            jdbcTemplate.execute("ALTER TABLE \"CLARIFICATION_QUESTIONS\" DROP CONSTRAINT "
                    + quoteIdentifier(constraint.name()));
        }

        jdbcTemplate.execute("ALTER TABLE \"CLARIFICATION_QUESTIONS\" ADD CONSTRAINT "
                + "\"CK_CLARIFICATION_QUESTIONS_STATUS\" CHECK (\"STATUS\" IN "
                + "('PENDING', 'ANSWERED', 'OBSOLETE'))");
        log.info("H2 clarification question status constraint now allows PENDING, ANSWERED and OBSOLETE");
    }

    private boolean allowsObsolete(ConstraintDefinition constraint) {
        return constraint.clause() != null
                && constraint.clause().toUpperCase().contains("OBSOLETE");
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record ConstraintDefinition(String name, String clause) {
    }
}
