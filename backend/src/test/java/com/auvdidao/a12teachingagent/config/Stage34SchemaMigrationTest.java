package com.auvdidao.a12teachingagent.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Stage34SchemaMigrationTest {

    @Test
    void versionedMigrationCreatesStage34TablesConstraintsAndIndexesOnCleanH2() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:stage34_migration;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE projects (id BIGINT PRIMARY KEY)");

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        assertEquals(17, flyway.migrate().migrationsExecuted);
        assertEquals("18", flyway.info().current().getVersion().getVersion());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'TEMPLATE_PROFILE_VERSIONS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_NAME = 'UK_TEMPLATE_PROFILES_TEMPLATE_VERSION'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = 'IX_PPT_SPECIFICATIONS_PROJECT_STATUS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'CANDIDATE_ASSETS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'APPROVED_ASSET_MANIFESTS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_NAME = 'UK_MANIFEST_PROJECT_ACTIVE_KEY'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'APPROVED_ASSET_MANIFESTS' AND COLUMN_NAME = 'ACTIVE_KEY'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'PPT_GENERATION_JOBS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_NAME = 'UK_PPT_GENERATION_JOB_PROJECT_IDEMPOTENCY'", Integer.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void cleanH2DatabaseExecutesApplicationBaselineThenStage34Migration() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:stage34_clean_migration;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        assertEquals(18, flyway.migrate().migrationsExecuted);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'PROJECTS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'TEMPLATE_PROFILE_VERSIONS'", Integer.class));
        assertEquals("18", flyway.info().current().getVersion().getVersion());
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }
}
