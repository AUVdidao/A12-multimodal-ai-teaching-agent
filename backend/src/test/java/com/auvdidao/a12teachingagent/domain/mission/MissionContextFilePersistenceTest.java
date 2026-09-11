package com.auvdidao.a12teachingagent.domain.mission;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.flywaydb.core.Flyway;

import java.util.Properties;
import java.util.UUID;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionContextFilePersistenceTest {

    @Test
    void persistsUpdatesAndClearsLongUnicodeFailureReasonOnFreshV16Schema() {
        String url = "jdbc:h2:mem:mission_context_file_0240_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load();
        flyway.migrate();

        assertEquals(16, flyway.info().applied().length);
        assertTrue(Arrays.stream(flyway.info().applied())
                .map(migration -> migration.getVersion().getVersion())
                .toList()
                .containsAll(java.util.List.of("11", "16")));
        assertEquals("16", flyway.info().current().getVersion().getVersion());
        assertEquals("CHARACTER VARYING", jdbcTemplate.queryForObject(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'LESSONFORGE_CONTEXT_FILES' "
                        + "AND COLUMN_NAME = 'FAILURE_REASON'", String.class));

        Properties settings = new Properties();
        settings.put(AvailableSettings.JAKARTA_JDBC_URL, url);
        settings.put(AvailableSettings.JAKARTA_JDBC_USER, "sa");
        settings.put(AvailableSettings.JAKARTA_JDBC_PASSWORD, "");
        settings.put(AvailableSettings.JAKARTA_JDBC_DRIVER, "org.h2.Driver");
        settings.put(AvailableSettings.DIALECT, "org.hibernate.dialect.H2Dialect");
        settings.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        settings.put(AvailableSettings.HBM2DDL_AUTO, "validate");
        settings.put(AvailableSettings.SHOW_SQL, "false");

        ServiceRegistry serviceRegistry = new StandardServiceRegistryBuilder()
                .applySettings(settings)
                .build();
        Metadata metadata = new MetadataSources(serviceRegistry)
                .addAnnotatedClass(MissionContextFile.class)
                .getMetadataBuilder()
                .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .build();
        EntityManagerFactory entityManagerFactory = metadata.getSessionFactoryBuilder().build();
        EntityManager entityManager = entityManagerFactory.createEntityManager();

        String initial = "初始失败原因🌏-" + "Unicode回归内容-".repeat(80);
        String updated = "更新失败原因🚀-" + "第二次读写校验-".repeat(90);
        assertTrue(initial.length() > 255);
        assertTrue(updated.length() > 255);

        MissionContextFile file = new MissionContextFile();
        file.setMissionId(240L);
        file.setUploadedBy(240L);
        file.setOriginalFileName("controlled-regression.txt");
        file.setContentType("text/plain");
        file.setFileExtension("txt");
        file.setFileSize(initial.length());
        file.setSha256("a".repeat(64));
        file.setStorageKey("controlled/0240/mission-context-file.txt");
        file.setKind(MissionFileKind.MATERIAL);
        file.setFailureReason(initial);

        entityManager.getTransaction().begin();
        entityManager.persist(file);
        entityManager.getTransaction().commit();
        Long id = file.getId();
        entityManager.clear();
        MissionContextFile reloaded = entityManager.find(MissionContextFile.class, id);
        assertEquals(initial, reloaded.getFailureReason());
        assertEquals(initial.length(), reloaded.getFailureReason().length());

        reloaded.setFailureReason(updated);
        entityManager.getTransaction().begin();
        entityManager.merge(reloaded);
        entityManager.getTransaction().commit();
        entityManager.clear();
        reloaded = entityManager.find(MissionContextFile.class, id);
        assertEquals(updated, reloaded.getFailureReason());
        assertEquals(updated.length(), reloaded.getFailureReason().length());

        reloaded.setFailureReason(null);
        entityManager.getTransaction().begin();
        entityManager.merge(reloaded);
        entityManager.getTransaction().commit();
        entityManager.clear();
        assertNull(entityManager.find(MissionContextFile.class, id).getFailureReason());

        reloaded = entityManager.find(MissionContextFile.class, id);
        reloaded.setFailureReason("");
        entityManager.getTransaction().begin();
        entityManager.merge(reloaded);
        entityManager.getTransaction().commit();
        entityManager.clear();
        assertEquals("", entityManager.find(MissionContextFile.class, id).getFailureReason());

        entityManager.getTransaction().begin();
        entityManager.remove(entityManager.find(MissionContextFile.class, id));
        entityManager.getTransaction().commit();
        entityManager.close();
        entityManagerFactory.close();
        StandardServiceRegistryBuilder.destroy(serviceRegistry);
    }
}
