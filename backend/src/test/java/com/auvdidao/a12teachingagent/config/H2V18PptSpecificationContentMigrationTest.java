package com.auvdidao.a12teachingagent.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2V18PptSpecificationContentMigrationTest {

    @Test
    void existingContentIsPreservedWhenV18ChangesOnlyTheTargetColumn() throws Exception {
        DriverManagerDataSource dataSource = dataSource("existing");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        flywayConfiguration(dataSource).target("2").load().migrate();

        String content = "中日韩 mixed emoji 👩‍🏫 é\nline 2 " + "x".repeat(260);
        jdbc.update("INSERT INTO projects (id) VALUES (1)");
        jdbc.update("INSERT INTO ppt_specification_versions (specification_id, project_id, version_number, status, contract_version, template_profile_id, template_profile_version, template_capability_view_version, template_capability_view_checksum, target_slide_count, slide_count_tolerance, locale, provider, model, ai_supplement_policy, checksum, entity_version) VALUES ('spec-1', 1, 1, 'DRAFT', 'v1', 'profile-1', 1, 1, 'capability-checksum', 1, 0, 'zh-CN', 'test', 'test-model', 'NONE', 'seed', 0)");
        long versionId = jdbc.queryForObject("SELECT id FROM ppt_specification_versions WHERE project_id = 1", Long.class);
        jdbc.update("INSERT INTO ppt_specification_slides (version_id, slide_id, position, page_number, title, teaching_goal, semantic_layout_json) VALUES (?, 'slide-1', 1, 1, 'title', 'goal', '{}')", versionId);
        long slideId = jdbc.queryForObject("SELECT id FROM ppt_specification_slides WHERE version_id = ?", Long.class, versionId);
        jdbc.update("INSERT INTO ppt_specification_content_blocks (slide_id, block_id, position, type, content, source_type, source_reference, locked) VALUES (?, 'block-1', 1, 'BODY', ?, 'MANUAL', 'test', FALSE)", slideId, content);
        String beforeHash = sha256(jdbc.queryForObject("SELECT content FROM ppt_specification_content_blocks WHERE block_id = 'block-1'", String.class));

        Flyway fullFlyway = flyway(dataSource);
        assertEquals(16, fullFlyway.migrate().migrationsExecuted);
        assertEquals("18", fullFlyway.info().current().getVersion().getVersion());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ppt_specification_content_blocks", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ppt_specification_content_blocks WHERE content = ''", Integer.class) == 0 ? 1 : 0);
        assertEquals(beforeHash, sha256(jdbc.queryForObject("SELECT content FROM ppt_specification_content_blocks WHERE block_id = 'block-1'", String.class)));
        assertEquals(content, jdbc.queryForObject("SELECT content FROM ppt_specification_content_blocks WHERE block_id = 'block-1'", String.class));
        jdbc.update("INSERT INTO ppt_specification_content_blocks (slide_id, block_id, position, type, content, source_type, source_reference, locked) VALUES (?, 'block-empty', 2, 'TEXT', '', 'MANUAL', 'test', FALSE)", slideId);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ppt_specification_content_blocks WHERE content = ''", Integer.class));
        assertEquals("CHARACTER VARYING", jdbc.queryForObject("SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'PPT_SPECIFICATION_CONTENT_BLOCKS' AND COLUMN_NAME = 'CONTENT'", String.class));
        assertEquals("NO", jdbc.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'PPT_SPECIFICATION_CONTENT_BLOCKS' AND COLUMN_NAME = 'CONTENT'", String.class));
        assertTrue(content.length() > 255);
    }

    @Test
    void freshH2MigratesThroughV18() {
        DriverManagerDataSource dataSource = dataSource("fresh");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway flyway = flyway(dataSource);

        assertEquals(18, flyway.migrate().migrationsExecuted);
        assertEquals("18", flyway.info().current().getVersion().getVersion());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'PPT_SPECIFICATION_CONTENT_BLOCKS' AND COLUMN_NAME = 'CONTENT' AND DATA_TYPE = 'CHARACTER VARYING'", Integer.class));
    }

    private static DriverManagerDataSource dataSource(String suffix) {
        return new DriverManagerDataSource("jdbc:h2:mem:h2_v18_content_" + suffix + "_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
    }

    private static Flyway flyway(DriverManagerDataSource dataSource) {
        return flywayConfiguration(dataSource).load();
    }

    private static FluentConfiguration flywayConfiguration(DriverManagerDataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format("%02x", item));
        return result.toString();
    }
}
