package com.auvdidao.a12teachingagent.config;

import com.auvdidao.a12teachingagent.domain.template.TemplateAnalysisResult;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateAnalysisResultRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real PostgreSQL persistence regression for the candidate JSON JDBC binding.
 * This is not a real Analyzer/Provider invocation; it isolates the repository
 * write/read path and deletes its own row before completing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=prod",
        "spring.datasource.url=jdbc:postgresql://database:5432/a12_teaching_agent",
        "spring.datasource.username=a12_user",
        "spring.datasource.password=a12_dev_password",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false",
        "a12.security.enabled=false",
        "a12.security.demo-seed-enabled=false",
        "a12.material-parser.mode=local",
        "a12.artifact-generator.mode=local",
        "a12.ppt-engine.mode=unavailable"
})
class PostgresqlCandidateProfilePersistenceTest {

    @Autowired
    private TemplateAnalysisResultRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void writesReadsAndCleansNonEmptyCandidateProfileWithIdentityAndChecksum() {
        String runId = "0105-pg-candidate-" + UUID.randomUUID();
        String sourceSha256 = "a".repeat(64);
        String snapshotChecksum = "b".repeat(64);
        String candidateJson = "{\"displayName\":\"PG persistence regression\",\"sourceVersionId\":1,"
                + "\"parserSnapshotChecksum\":\"" + snapshotChecksum + "\",\"capacity\":4}";
        String candidateSha256 = sha256(candidateJson);

        TemplateAnalysisResult result = new TemplateAnalysisResult();
        result.setTemplateId(1L);
        result.setProjectId(7L);
        result.setSourceVersionId(1L);
        result.setOwnerUserId(2L);
        result.setAnalysisRunId(runId);
        result.setSourceSha256(sourceSha256);
        result.setParserSnapshotChecksum(snapshotChecksum);
        result.setStatus(TemplateProcessingStatus.SUCCEEDED);
        result.setAdapter("0105-synthetic-persistence-regression");
        result.setAdapterVersion("0105");
        result.setProvider("synthetic-persistence-regression");
        result.setModel("not-a-provider");
        result.setInputSha256(sourceSha256);
        result.setOutputSha256(candidateSha256);
        result.setCandidateProfileJson(candidateJson);
        result.setCandidateProfileSha256(candidateSha256);

        TemplateAnalysisResult saved = repository.saveAndFlush(result);
        Long id = saved.getId();
        entityManager.clear();

        TemplateAnalysisResult reloaded = repository.findByAnalysisRunId(runId).orElseThrow();
        assertNotNull(id);
        assertEquals(candidateJson, reloaded.getCandidateProfileJson());
        assertEquals(candidateSha256, reloaded.getCandidateProfileSha256());
        assertEquals(runId, reloaded.getAnalysisRunId());
        assertEquals(sourceSha256, reloaded.getSourceSha256());
        assertEquals(snapshotChecksum, reloaded.getParserSnapshotChecksum());
        assertEquals(TemplateProcessingStatus.SUCCEEDED, reloaded.getStatus());

        repository.delete(reloaded);
        repository.flush();

        TemplateAnalysisResult failed = new TemplateAnalysisResult();
        failed.setTemplateId(1L);
        failed.setProjectId(7L);
        failed.setSourceVersionId(1L);
        failed.setOwnerUserId(2L);
        failed.setAnalysisRunId("0105-pg-failure-" + UUID.randomUUID());
        failed.setSourceSha256(sourceSha256);
        failed.setParserSnapshotChecksum(snapshotChecksum);
        failed.setStatus(TemplateProcessingStatus.NOT_READY);
        failed.setAdapter("0105-synthetic-persistence-regression");
        failed.setFailureReason("ANALYZER_NOT_READY: Renderer is not available in this controlled regression.");

        repository.saveAndFlush(failed);
        entityManager.clear();
        TemplateAnalysisResult reloadedFailure = repository.findByAnalysisRunId(failed.getAnalysisRunId()).orElseThrow();
        assertEquals(failed.getFailureReason(), reloadedFailure.getFailureReason());
        assertEquals(TemplateProcessingStatus.NOT_READY, reloadedFailure.getStatus());
        assertNotNull(reloadedFailure.getId());
        repository.delete(reloadedFailure);
        repository.flush();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
