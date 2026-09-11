package com.auvdidao.a12teachingagent.config;

import com.auvdidao.a12teachingagent.ai.connection.ModelConnectionEntity;
import com.auvdidao.a12teachingagent.ai.connection.ModelConnectionProtocol;
import com.auvdidao.a12teachingagent.ai.connection.ModelConnectionVerificationStatus;
import com.auvdidao.a12teachingagent.ai.credential.AiCredentialCryptoService;
import com.auvdidao.a12teachingagent.ai.credential.AiCredentialProperties;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2V17ModelConnectionMigrationTest {

    @Test
    void cleanH2MigratesV1ThroughV17ToVarcharLikeEncryptedApiKey() throws Exception {
        DriverManagerDataSource dataSource = dataSource("clean");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway flyway = flyway(dataSource).load();
        assertEquals(17, flyway.migrate().migrationsExecuted);
        assertEquals("17", flyway.info().current().getVersion().getVersion());

        try (var connection = dataSource.getConnection()) {
            assertEquals("H2", connection.getMetaData().getDatabaseProductName());
        }
        assertEquals("CHARACTER VARYING", columnValue(jdbc, "DATA_TYPE", String.class));
        assertEquals(1_000_000_000L, columnValue(jdbc, "CHARACTER_MAXIMUM_LENGTH", Long.class));
        assertEquals("NO", columnValue(jdbc, "IS_NULLABLE", String.class));
    }

    @Test
    void v16ToV17PreservesOpaqueCiphertextAndModelConnectionConstraints() throws Exception {
        DriverManagerDataSource dataSource = dataSource("upgrade");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway flyway = flyway(dataSource).target("16").load();
        assertEquals(16, flyway.migrate().migrationsExecuted);
        assertEquals("16", flyway.info().current().getVersion().getVersion());

        AiCredentialCryptoService crypto = cryptoService();
        String controlledPlaintext = "opaque-round-trip-" + UUID.randomUUID() + "-汉字";
        String ciphertext = crypto.encrypt(controlledPlaintext);
        jdbc.update("INSERT INTO model_connections "
                        + "(owner_user_id, name, protocol, base_url, model_id, encrypted_api_key, key_hint, enabled, "
                        + "verification_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                7001L, "migration-regression", "OPENAI_COMPATIBLE", "https://example.invalid/v1", "controlled-model",
                ciphertext, crypto.hint(controlledPlaintext), true, "UNVERIFIED");

        MigrationSnapshot before = snapshot(jdbc);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM model_connections", Integer.class));
        assertEquals(ciphertext, jdbc.queryForObject("SELECT encrypted_api_key FROM model_connections WHERE owner_user_id = 7001", String.class));

        assertEquals(1, flyway(dataSource).load().migrate().migrationsExecuted);
        assertEquals("17", flyway.info().current().getVersion().getVersion());

        MigrationSnapshot after = snapshot(jdbc);
        assertEquals(before.rowCount(), after.rowCount());
        assertEquals(before.ciphertextSha256(), after.ciphertextSha256());
        assertEquals(before.ciphertextLength(), after.ciphertextLength());
        assertEquals(before.ciphertextUnicodeSummary(), after.ciphertextUnicodeSummary());
        assertEquals(before.constraintNames(), after.constraintNames());
        assertEquals(before.indexNames(), after.indexNames());
        assertEquals("CHARACTER VARYING", columnValue(jdbc, "DATA_TYPE", String.class));
        assertEquals(1_000_000_000L, columnValue(jdbc, "CHARACTER_MAXIMUM_LENGTH", Long.class));
        assertEquals("NO", columnValue(jdbc, "IS_NULLABLE", String.class));
        assertEquals(ciphertext, jdbc.queryForObject("SELECT encrypted_api_key FROM model_connections WHERE owner_user_id = 7001", String.class));
        assertEquals(controlledPlaintext, crypto.decrypt(ciphertext));

        try (SessionFactory sessionFactory = sessionFactory(dataSource)) {
            Long id = persistConnection(sessionFactory, crypto.encrypt(controlledPlaintext), crypto.hint(controlledPlaintext));
            assertOpaqueRoundTrip(sessionFactory, id, controlledPlaintext, crypto);
        }

        try (SessionFactory restartedSessionFactory = sessionFactory(dataSource)) {
            Long id = jdbc.queryForObject("SELECT id FROM model_connections WHERE name = 'hibernate-regression'", Long.class);
            assertOpaqueRoundTrip(restartedSessionFactory, id, controlledPlaintext, crypto);
            assertFalse(jdbc.queryForObject("SELECT encrypted_api_key FROM model_connections WHERE id = ?", String.class, id)
                    .equals(controlledPlaintext));
            jdbc.update("DELETE FROM model_connections WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM model_connections WHERE owner_user_id = 7001");
    }

    private static void assertOpaqueRoundTrip(SessionFactory sessionFactory, Long id, String plaintext,
                                              AiCredentialCryptoService crypto) {
        try (Session session = sessionFactory.openSession()) {
            ModelConnectionEntity loaded = session.get(ModelConnectionEntity.class, id);
            assertNotEquals(plaintext, loaded.getEncryptedApiKey());
            assertEquals(plaintext, crypto.decrypt(loaded.getEncryptedApiKey()));
        }
    }

    private static Long persistConnection(SessionFactory sessionFactory, String ciphertext, String keyHint) {
        ModelConnectionEntity entity = new ModelConnectionEntity();
        entity.setOwnerUserId(7002L);
        entity.setName("hibernate-regression");
        entity.setProtocol(ModelConnectionProtocol.OPENAI_COMPATIBLE);
        entity.setBaseUrl("https://example.invalid/v1");
        entity.setModelId("controlled-model");
        entity.setEncryptedApiKey(ciphertext);
        entity.setKeyHint(keyHint);
        entity.setEnabled(true);
        entity.setVerificationStatus(ModelConnectionVerificationStatus.UNVERIFIED);

        try (Session session = sessionFactory.openSession()) {
            var transaction = session.beginTransaction();
            session.persist(entity);
            transaction.commit();
            return entity.getId();
        }
    }

    private static SessionFactory sessionFactory(DriverManagerDataSource dataSource) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.datasource", dataSource)
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.show_sql", "false")
                .build();
        try {
            return new MetadataSources(registry)
                    .addAnnotatedClass(ModelConnectionEntity.class)
                    .buildMetadata()
                    .buildSessionFactory();
        } catch (RuntimeException exception) {
            StandardServiceRegistryBuilder.destroy(registry);
            throw exception;
        }
    }

    private static MigrationSnapshot snapshot(JdbcTemplate jdbc) {
        String ciphertext = jdbc.queryForObject(
                "SELECT encrypted_api_key FROM model_connections WHERE owner_user_id = 7001", String.class);
        return new MigrationSnapshot(
                jdbc.queryForObject("SELECT COUNT(*) FROM model_connections", Integer.class),
                sha256(ciphertext),
                ciphertext.length(),
                sha256(ciphertext.codePoints().mapToObj(Integer::toString).collect(Collectors.joining(","))),
                names(jdbc, "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'MODEL_CONNECTIONS'"),
                names(jdbc, "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE TABLE_NAME = 'MODEL_CONNECTIONS'"));
    }

    private static Set<String> names(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForList(sql, String.class).stream()
                .map(H2V17ModelConnectionMigrationTest::normalizeH2GeneratedName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String normalizeH2GeneratedName(String name) {
        if (name.matches("PRIMARY_KEY_[A-Z0-9]+")) return "PRIMARY_KEY";
        return name.replaceFirst("_INDEX_[A-Z0-9]+$", "");
    }

    private static <T> T columnValue(JdbcTemplate jdbc, String field, Class<T> type) {
        return jdbc.queryForObject("SELECT " + field + " FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'MODEL_CONNECTIONS' AND COLUMN_NAME = 'ENCRYPTED_API_KEY'", type);
    }

    private static DriverManagerDataSource dataSource(String suffix) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:h2_v17_model_connection_" + suffix + "_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(DriverManagerDataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1");
    }

    private static AiCredentialCryptoService cryptoService() {
        AiCredentialProperties properties = new AiCredentialProperties();
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        return new AiCredentialCryptoService(properties);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record MigrationSnapshot(int rowCount, String ciphertextSha256, int ciphertextLength,
                                     String ciphertextUnicodeSummary, Set<String> constraintNames,
                                     Set<String> indexNames) {
    }
}
