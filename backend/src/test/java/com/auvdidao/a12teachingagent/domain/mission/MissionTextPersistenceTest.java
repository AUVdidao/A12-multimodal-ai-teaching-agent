package com.auvdidao.a12teachingagent.domain.mission;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.service.ServiceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionTextPersistenceTest {

    @Test
    void migratesFreshH2ThroughV16ValidatesTargetEntitiesAndRoundTripsLongUnicodeText() {
        String url = "jdbc:h2:mem:mission_text_0243_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load();

        assertEquals(16, flyway.migrate().migrationsExecuted);
        assertEquals("16", flyway.info().current().getVersion().getVersion());
        assertTrue(List.of("11", "16").stream().allMatch(version ->
                java.util.Arrays.stream(flyway.info().applied())
                        .anyMatch(migration -> version.equals(migration.getVersion().getVersion()))));

        List<List<String>> targetColumns = List.of(
                List.of("LESSONFORGE_MISSIONS", "DESCRIPTION"),
                List.of("LESSONFORGE_MISSIONS", "REJECTION_REASON"),
                List.of("LESSONFORGE_MESSAGES", "CONTENT"),
                List.of("LESSONFORGE_SUBMISSIONS", "REVIEW_NOTE"));
        targetColumns.forEach(tableAndColumn -> {
            assertEquals("CHARACTER VARYING", jdbc.queryForObject(
                    "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                    String.class, tableAndColumn.get(0), tableAndColumn.get(1)));
        });

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
        EntityManagerFactory entityManagerFactory = null;
        EntityManager entityManager = null;
        try {
            Metadata metadata = new MetadataSources(serviceRegistry)
                    .addAnnotatedClass(Mission.class)
                    .addAnnotatedClass(MissionMessage.class)
                    .addAnnotatedClass(MissionSubmission.class)
                    .getMetadataBuilder()
                    .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                    .build();
            entityManagerFactory = metadata.getSessionFactoryBuilder().build();
            entityManager = entityManagerFactory.createEntityManager();

            String description = controlledText("任务描述🌏", "描述校验");
            String rejectionReason = controlledText("驳回原因🚫", "原因校验");
            String messageContent = controlledText("消息内容💬", "消息校验");
            String reviewNote = controlledText("评审备注✅", "备注校验");
            assertTrue(description.length() > 255);
            assertTrue(rejectionReason.length() > 255);
            assertTrue(messageContent.length() > 255);
            assertTrue(reviewNote.length() > 255);

            Mission mission = new Mission();
            mission.setTitle("0243 controlled mission");
            mission.setDescription(description);
            mission.setAssignedTeacherId(243L);
            mission.setCreatedByLeaderId(243L);
            mission.setStatus(MissionStatus.ASSIGNED);
            mission.setRejectionReason(rejectionReason);

            MissionMessage message = new MissionMessage();
            message.setMissionId(243L);
            message.setConversationId(243L);
            message.setSenderId(243L);
            message.setSenderRole("TEACHER");
            message.setContent(messageContent);

            MissionSubmission submission = new MissionSubmission();
            submission.setMissionId(243L);
            submission.setUploadedBy(243L);
            submission.setReviewerId(243L);
            submission.setOriginalFileName("controlled-0243.pptx");
            submission.setContentType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
            submission.setFileSize(243L);
            submission.setSha256("b".repeat(64));
            submission.setStorageKey("controlled/0243/submission.pptx");
            submission.setStatus(MissionSubmissionStatus.SUBMITTED);
            submission.setReviewNote(reviewNote);

            entityManager.getTransaction().begin();
            entityManager.persist(mission);
            entityManager.persist(message);
            entityManager.persist(submission);
            entityManager.getTransaction().commit();
            Long missionId = mission.getId();
            Long messageId = message.getId();
            Long submissionId = submission.getId();
            entityManager.clear();

            Mission reloadedMission = entityManager.find(Mission.class, missionId);
            MissionMessage reloadedMessage = entityManager.find(MissionMessage.class, messageId);
            MissionSubmission reloadedSubmission = entityManager.find(MissionSubmission.class, submissionId);
            assertNotNull(reloadedMission);
            assertNotNull(reloadedMessage);
            assertNotNull(reloadedSubmission);
            assertRoundTrip(description, reloadedMission.getDescription());
            assertRoundTrip(rejectionReason, reloadedMission.getRejectionReason());
            assertRoundTrip(messageContent, reloadedMessage.getContent());
            assertRoundTrip(reviewNote, reloadedSubmission.getReviewNote());

            String updatedDescription = controlledText("更新后的任务描述🚀", "更新描述");
            String updatedMessage = controlledText("更新后的消息📝", "更新消息");
            reloadedMission.setDescription(updatedDescription);
            reloadedMission.setRejectionReason(null);
            reloadedMessage.setContent(updatedMessage);
            reloadedSubmission.setReviewNote("");
            entityManager.getTransaction().begin();
            entityManager.merge(reloadedMission);
            entityManager.merge(reloadedMessage);
            entityManager.merge(reloadedSubmission);
            entityManager.getTransaction().commit();
            entityManager.clear();

            assertRoundTrip(updatedDescription, entityManager.find(Mission.class, missionId).getDescription());
            assertNull(entityManager.find(Mission.class, missionId).getRejectionReason());
            assertRoundTrip(updatedMessage, entityManager.find(MissionMessage.class, messageId).getContent());
            assertEquals("", entityManager.find(MissionSubmission.class, submissionId).getReviewNote());
        } finally {
            if (entityManager != null && entityManager.isOpen()) {
                entityManager.close();
            }
            if (entityManagerFactory != null && entityManagerFactory.isOpen()) {
                entityManagerFactory.close();
            }
            StandardServiceRegistryBuilder.destroy(serviceRegistry);
        }
    }

    private static String controlledText(String prefix, String token) {
        return prefix + "-" + (token + "-教学回归🌐").repeat(90);
    }

    private static void assertRoundTrip(String expected, String actual) {
        assertEquals(expected.length(), actual.length());
        assertEquals(expected, actual);
    }
}
