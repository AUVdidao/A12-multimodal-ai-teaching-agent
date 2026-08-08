package com.auvdidao.a12teachingagent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            migrateParseResultSectionValueIfNeeded();
            migrateParseResultSectionOrderIfNeeded();
            log.info("H2 enum compatibility migration completed");
        } catch (RuntimeException exception) {
            log.error("H2 enum compatibility migration failed; application startup is stopped to protect existing data", exception);
            throw exception;
        }
    }

    /**
     * Widens the legacy collection value column without rebuilding the table.
     * Hibernate's @Lob mapping is represented as CLOB by H2; older prototype
     * databases may still have VARCHAR(255), which truncates real sections.
     */
    void migrateParseResultSectionValueIfNeeded() {
        if (!tableExists("PARSE_RESULT_SECTIONS")
                || !columnExists("PARSE_RESULT_SECTIONS", "SECTION_VALUE")) {
            return;
        }

        Map<String, Object> column = jdbcTemplate.queryForList("""
                SELECT DATA_TYPE, DECLARED_DATA_TYPE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = CURRENT_SCHEMA()
                  AND TABLE_NAME = 'PARSE_RESULT_SECTIONS'
                  AND COLUMN_NAME = 'SECTION_VALUE'
                """).stream().findFirst().orElse(null);
        if (column == null) {
            return;
        }

        String dataType = String.valueOf(column.get("DATA_TYPE"));
        String declaredDataType = String.valueOf(column.get("DECLARED_DATA_TYPE"));
        if (isLongTextType(dataType, declaredDataType)) {
            return;
        }
        if (isCharacterType(dataType, declaredDataType)) {
            jdbcTemplate.execute("ALTER TABLE \"PARSE_RESULT_SECTIONS\" "
                    + "ALTER COLUMN \"SECTION_VALUE\" CLOB");
            log.info("Widened H2 parse result section values to CLOB");
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

    /**
     * Backfills the order column introduced for legacy H2 databases.
     *
     * The original collection order cannot be recovered once an old database
     * was stored without an order column. In that case, use a deterministic
     * fallback based on a temporary row identity. The identity represents the
     * deterministic row order available during migration; it cannot recreate
     * the original collection order. Repeated application is a no-op and
     * existing non-null order values are never changed.
     */
    void migrateParseResultSectionOrderIfNeeded() {
        if (!tableExists("PARSE_RESULT_SECTIONS")
                || !columnExists("PARSE_RESULT_SECTIONS", "PARSE_RESULT_ID")
                || !columnExists("PARSE_RESULT_SECTIONS", "SECTION_VALUE")
                || !columnExists("PARSE_RESULT_SECTIONS", "SECTION_ORDER")) {
            return;
        }

        Long nullOrderCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM "PARSE_RESULT_SECTIONS"
                WHERE "SECTION_ORDER" IS NULL
                """, Long.class);
        if (nullOrderCount == null || nullOrderCount == 0) {
            return;
        }

        final String migrationRowId = "A12_SECTION_MIGRATION_ROW_ID";
        boolean temporaryColumnAdded = false;
        try {
            // Clean up a column left by an interrupted prior run before adding
            // a fresh identity used only to address rows during this migration.
            if (columnExists("PARSE_RESULT_SECTIONS", migrationRowId)) {
                jdbcTemplate.execute("ALTER TABLE \"PARSE_RESULT_SECTIONS\" DROP COLUMN \""
                        + migrationRowId + "\"");
            }
            jdbcTemplate.execute("ALTER TABLE \"PARSE_RESULT_SECTIONS\" ADD COLUMN \""
                    + migrationRowId + "\" BIGINT GENERATED BY DEFAULT AS IDENTITY");
            temporaryColumnAdded = true;

            Map<Long, Set<Integer>> usedOrdersByParseResult = new LinkedHashMap<>();
            jdbcTemplate.query("""
                    SELECT "PARSE_RESULT_ID", "SECTION_ORDER"
                    FROM "PARSE_RESULT_SECTIONS"
                    WHERE "SECTION_ORDER" IS NOT NULL
                    ORDER BY "PARSE_RESULT_ID", "SECTION_ORDER"
                    """, resultSet -> {
                Long parseResultId = resultSet.getLong("PARSE_RESULT_ID");
                int sectionOrder = resultSet.getInt("SECTION_ORDER");
                usedOrdersByParseResult
                        .computeIfAbsent(parseResultId, ignored -> new HashSet<>())
                        .add(sectionOrder);
            });

            List<SectionRow> nullOrderRows = jdbcTemplate.query("""
                    SELECT "PARSE_RESULT_ID", "A12_SECTION_MIGRATION_ROW_ID"
                    FROM "PARSE_RESULT_SECTIONS"
                    WHERE "SECTION_ORDER" IS NULL
                    ORDER BY "PARSE_RESULT_ID", "A12_SECTION_MIGRATION_ROW_ID"
                    """, (resultSet, rowNum) -> new SectionRow(
                    resultSet.getLong("PARSE_RESULT_ID"),
                    resultSet.getLong(migrationRowId)));

            List<SectionOrderUpdate> updates = new java.util.ArrayList<>(nullOrderRows.size());
            for (SectionRow row : nullOrderRows) {
                Set<Integer> usedOrders = usedOrdersByParseResult
                        .computeIfAbsent(row.parseResultId(), ignored -> new HashSet<>());
                int nextOrder = 0;
                while (usedOrders.contains(nextOrder)) {
                    nextOrder++;
                }
                usedOrders.add(nextOrder);
                updates.add(new SectionOrderUpdate(row.migrationRowId(), nextOrder));
            }

            jdbcTemplate.batchUpdate("""
                    UPDATE "PARSE_RESULT_SECTIONS"
                    SET "SECTION_ORDER" = ?
                    WHERE "A12_SECTION_MIGRATION_ROW_ID" = ?
                      AND "SECTION_ORDER" IS NULL
                    """, updates, updates.size(), (statement, update) -> {
                statement.setInt(1, update.sectionOrder());
                statement.setLong(2, update.migrationRowId());
            });
            log.info("Backfilled {} legacy parse result section order values", updates.size());
        } finally {
            if (temporaryColumnAdded) {
                jdbcTemplate.execute("ALTER TABLE \"PARSE_RESULT_SECTIONS\" DROP COLUMN \""
                        + migrationRowId + "\"");
            }
        }
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = CURRENT_SCHEMA()
                  AND TABLE_NAME = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = CURRENT_SCHEMA()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private boolean isLongTextType(String dataType, String declaredDataType) {
        String type = (dataType + " " + declaredDataType).toUpperCase(java.util.Locale.ROOT);
        return type.contains("CLOB")
                || type.contains("CHARACTER LARGE OBJECT")
                || type.contains("TEXT");
    }

    private boolean isCharacterType(String dataType, String declaredDataType) {
        String type = (dataType + " " + declaredDataType).toUpperCase(java.util.Locale.ROOT);
        return type.contains("CHARACTER VARYING")
                || type.contains("VARCHAR")
                || type.equals("CHARACTER")
                || type.endsWith(" CHARACTER")
                || type.contains("CHAR(");
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

    private record SectionRow(Long parseResultId, long migrationRowId) {
    }

    private record SectionOrderUpdate(long migrationRowId, int sectionOrder) {
    }
}
