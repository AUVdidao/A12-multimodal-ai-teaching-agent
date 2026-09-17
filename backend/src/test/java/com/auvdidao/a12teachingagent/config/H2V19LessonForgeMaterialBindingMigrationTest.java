package com.auvdidao.a12teachingagent.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class H2V19LessonForgeMaterialBindingMigrationTest {

    @Test
    void freshH2MigratesThroughV22AndEnforcesSourceIdentity() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:h2_v19_lessonforge_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();

        assertEquals(22, flyway.migrate().migrationsExecuted);
        assertEquals("22", flyway.info().current().getVersion().getVersion());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'LESSONFORGE_MATERIAL_BINDINGS'",
                Integer.class));

        jdbc.update("INSERT INTO projects (id) VALUES (1)");
        jdbc.update("INSERT INTO uploaded_materials (id, project_id, file_name, original_file_name, file_extension, file_type, content_type, file_path, file_size, upload_status, parse_status) VALUES (1, 1, 'stored.pdf', 'source.pdf', 'pdf', 'PDF', 'application/pdf', '1/stored.pdf', 4, 'UPLOADED', 'NOT_STARTED')");
        String sha = "a".repeat(64);
        jdbc.update("INSERT INTO lessonforge_material_bindings (mission_id, mission_file_id, owner_user_id, actor_user_id, rag_project_id, rag_material_id, source_sha256, source_size, original_filename, binding_status) VALUES (7, 9, 101, 101, 1, 1, ?, 4, 'source.pdf', 'BOUND')", sha);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM lessonforge_material_bindings", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'LESSONFORGE_TEXT_LOB_MIGRATION_AUDIT'",
                Integer.class));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "INSERT INTO lessonforge_material_bindings (mission_id, mission_file_id, owner_user_id, actor_user_id, rag_project_id, rag_material_id, source_sha256, source_size, original_filename, binding_status) VALUES (7, 9, 101, 101, 1, 1, ?, 4, 'source.pdf', 'BOUND')",
                sha));
    }
}
