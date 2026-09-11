package com.auvdidao.a12teachingagent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;

/**
 * Compatibility migration for the current H2-backed prototype. Production
 * deployments must carry the same columns through their managed schema
 * migration; this runner only widens legacy H2 columns and is idempotent.
 */
@Component
public class TemplateProfileSchemaMigration implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public TemplateProfileSchemaMigration(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!"H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) return;
        }
        widenTextColumn("TEMPLATE_PROCESSING_RUNS", "FAILURE_REASON");
        widenTextColumn("TEMPLATE_STRUCTURAL_SNAPSHOTS", "SNAPSHOT_JSON");
        widenTextColumn("TEMPLATE_PROFILE_VERSIONS", "PROFILE_JSON");
        widenTextColumn("TEMPLATE_PROFILE_VERSIONS", "CAPABILITY_VIEW_JSON");
    }

    void widenTextColumn(String tableName, String columnName) {
        if (!columnExists(tableName, columnName)) return;
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT DATA_TYPE, DECLARED_DATA_TYPE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = CURRENT_SCHEMA()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, tableName, columnName);
        if (rows.isEmpty()) return;
        String type = (String.valueOf(rows.get(0).get("DATA_TYPE")) + " " + rows.get(0).get("DECLARED_DATA_TYPE"))
                .toUpperCase(Locale.ROOT);
        if (type.contains("CLOB") || type.contains("TEXT") || type.contains("LARGE OBJECT")) return;
        jdbcTemplate.execute("ALTER TABLE \"" + quote(tableName) + "\" ALTER COLUMN \"" + quote(columnName) + "\" CLOB");
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = CURRENT_SCHEMA() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private String quote(String identifier) {
        return identifier.replace("\"", "\"\"");
    }
}


