package com.auvdidao.a12teachingagent.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2V11EngineNativeProfileMigrationTest {

    @Test
    void migratesDevelopmentSchemaThroughV11AndCanRepeatV11ColumnStatements() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:h2_v11_engine_native_profile;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE projects (id BIGINT PRIMARY KEY)");

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target("11")
                .load();

        assertEquals(10, flyway.migrate().migrationsExecuted);
        assertEquals("11", flyway.info().current().getVersion().getVersion());
        assertEquals("CHARACTER VARYING", jdbc.queryForObject(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'TEMPLATE_PROFILE_VERSIONS' "
                        + "AND COLUMN_NAME = 'ENGINE_NATIVE_PROFILE_JSON'",
                String.class));
        assertTrue(jdbc.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'TEMPLATE_PROFILE_VERSIONS' "
                        + "AND COLUMN_NAME = 'ENGINE_NATIVE_PROFILE_JSON'",
                Long.class) > 64);
        assertEquals("CHARACTER VARYING", jdbc.queryForObject(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'TEMPLATE_PROFILE_VERSIONS' "
                        + "AND COLUMN_NAME = 'ENGINE_NATIVE_PROFILE_CHECKSUM'",
                String.class));
        assertEquals(64, jdbc.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'TEMPLATE_PROFILE_VERSIONS' "
                        + "AND COLUMN_NAME = 'ENGINE_NATIVE_PROFILE_CHECKSUM'",
                Integer.class));

        jdbc.execute("ALTER TABLE template_profile_versions "
                + "ADD COLUMN IF NOT EXISTS engine_native_profile_json TEXT");
        jdbc.execute("ALTER TABLE template_profile_versions "
                + "ADD COLUMN IF NOT EXISTS engine_native_profile_checksum VARCHAR(64)");
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }
}
