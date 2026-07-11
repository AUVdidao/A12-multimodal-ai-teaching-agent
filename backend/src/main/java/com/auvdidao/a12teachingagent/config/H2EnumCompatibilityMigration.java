package com.auvdidao.a12teachingagent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

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
            log.info("H2 enum compatibility migration completed");
        } catch (RuntimeException exception) {
            log.error("H2 enum compatibility migration failed; application startup is stopped to protect existing data", exception);
            throw exception;
        }
    }
}
