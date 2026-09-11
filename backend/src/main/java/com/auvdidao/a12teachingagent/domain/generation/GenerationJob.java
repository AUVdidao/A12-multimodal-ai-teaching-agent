package com.auvdidao.a12teachingagent.domain.generation;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ppt_generation_jobs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ppt_generation_job_project_idempotency", columnNames = {"project_id", "idempotency_key"}),
        @UniqueConstraint(name = "uk_ppt_generation_job_execution", columnNames = "execution_id")
})
public class GenerationJob extends BaseAuditableEntity {
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;
    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;
    @Column(name = "execution_id", nullable = false, length = 128)
    private String executionId;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Column(name = "specification_version_id", nullable = false)
    private Long specificationVersionId;
    @Column(name = "specification_version", nullable = false)
    private Integer specificationVersion;
    @Column(name = "specification_checksum", nullable = false, length = 64)
    private String specificationChecksum;
    @Column(name = "template_profile_id", nullable = false)
    private Long templateProfileId;
    @Column(name = "template_profile_version", nullable = false)
    private Integer templateProfileVersion;
    @Column(name = "template_profile_checksum", nullable = false, length = 64)
    private String templateProfileChecksum;
    @Column(name = "asset_manifest_version", nullable = false)
    private Integer assetManifestVersion;
    @Column(name = "asset_manifest_checksum", nullable = false, length = 64)
    private String assetManifestChecksum;
    @Column(name = "input_identity_checksum", nullable = false, length = 64)
    private String inputIdentityChecksum;
    @Column(name = "engine_version", nullable = false, length = 128)
    private String engineVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GenerationJobStatus status;
    // PostgreSQL's JDBC driver rejects Hibernate's non-standard LONG32VARCHAR
    // type code (4001) with SQLState 07006. VARCHAR uses the standard JDBC
    // binding while the migration stores this as TEXT; the explicit column
    // definition preserves an unbounded database text column.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "request_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String requestSnapshotJson;
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "response_receipt_json", columnDefinition = "TEXT")
    private String responseReceiptJson;
    @Column(name = "failure_code", length = 96)
    private String failureCode;
    @Column(name = "failure_message", length = 256)
    private String failureMessage;
    @Version
    @Column(name = "entity_version", nullable = false)
    private Long entityVersion;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long value) { projectId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public Long getRequestedBy() { return requestedBy; }
    public void setRequestedBy(Long value) { requestedBy = value; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String value) { executionId = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public Long getSpecificationVersionId() { return specificationVersionId; }
    public void setSpecificationVersionId(Long value) { specificationVersionId = value; }
    public Integer getSpecificationVersion() { return specificationVersion; }
    public void setSpecificationVersion(Integer value) { specificationVersion = value; }
    public String getSpecificationChecksum() { return specificationChecksum; }
    public void setSpecificationChecksum(String value) { specificationChecksum = value; }
    public Long getTemplateProfileId() { return templateProfileId; }
    public void setTemplateProfileId(Long value) { templateProfileId = value; }
    public Integer getTemplateProfileVersion() { return templateProfileVersion; }
    public void setTemplateProfileVersion(Integer value) { templateProfileVersion = value; }
    public String getTemplateProfileChecksum() { return templateProfileChecksum; }
    public void setTemplateProfileChecksum(String value) { templateProfileChecksum = value; }
    public Integer getAssetManifestVersion() { return assetManifestVersion; }
    public void setAssetManifestVersion(Integer value) { assetManifestVersion = value; }
    public String getAssetManifestChecksum() { return assetManifestChecksum; }
    public void setAssetManifestChecksum(String value) { assetManifestChecksum = value; }
    public String getInputIdentityChecksum() { return inputIdentityChecksum; }
    public void setInputIdentityChecksum(String value) { inputIdentityChecksum = value; }
    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String value) { engineVersion = value; }
    public GenerationJobStatus getStatus() { return status; }
    public void setStatus(GenerationJobStatus value) { status = value; }
    public String getRequestSnapshotJson() { return requestSnapshotJson; }
    public void setRequestSnapshotJson(String value) { requestSnapshotJson = value; }
    public String getResponseReceiptJson() { return responseReceiptJson; }
    public void setResponseReceiptJson(String value) { responseReceiptJson = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { failureCode = value; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String value) { failureMessage = value; }
    public Long getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Long value) { entityVersion = value; }
}
