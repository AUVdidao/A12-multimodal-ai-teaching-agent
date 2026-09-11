package com.auvdidao.a12teachingagent.domain.material;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "parse_results")
public class ParseResult extends BaseAuditableEntity {

    private Long materialId;
    /** Server-owned parser attempt identity; never accepted from the client. */
    @Column(name = "analysis_run_id", length = 128)
    private String analysisRunId;

    /** Current material model uses the immutable uploaded-material id as source version. */
    @Column(name = "source_version_id")
    private Long sourceVersionId;

    /** SHA-256 of the deterministic parser output snapshot, not AI/client input. */
    @Column(name = "parser_snapshot_checksum", length = 64)
    private String parserSnapshotChecksum;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String extractedText;

    private Integer pageCount;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "parse_result_keywords", joinColumns = @JoinColumn(name = "parse_result_id"))
    @Column(name = "keyword_value")
    private List<String> keywords = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "parse_result_stages", joinColumns = @JoinColumn(name = "parse_result_id"))
    @Column(name = "stage_value")
    private List<String> applicableTeachingStages = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "parse_result_sections", joinColumns = @JoinColumn(name = "parse_result_id"))
    @Lob
    @Column(name = "section_value", columnDefinition = "TEXT")
    @OrderColumn(name = "section_order")
    private List<String> sections = new ArrayList<>();

    private Integer chunkCount;

    private Long parseDurationMs;

    @Enumerated(EnumType.STRING)
    private MaterialParseStatus parseStatus;

    private String failureReason;
    private LocalDateTime parsedAt;

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public String getAnalysisRunId() {
        return analysisRunId;
    }

    public void setAnalysisRunId(String analysisRunId) {
        this.analysisRunId = analysisRunId;
    }

    public Long getSourceVersionId() {
        return sourceVersionId;
    }

    public void setSourceVersionId(Long sourceVersionId) {
        this.sourceVersionId = sourceVersionId;
    }

    public String getParserSnapshotChecksum() {
        return parserSnapshotChecksum;
    }

    public void setParserSnapshotChecksum(String parserSnapshotChecksum) {
        this.parserSnapshotChecksum = parserSnapshotChecksum;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public List<String> getKeywords() {
        return List.copyOf(keywords);
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords == null ? new ArrayList<>() : new ArrayList<>(keywords);
    }

    public List<String> getApplicableTeachingStages() {
        return List.copyOf(applicableTeachingStages);
    }

    public void setApplicableTeachingStages(List<String> applicableTeachingStages) {
        this.applicableTeachingStages = applicableTeachingStages == null
                ? new ArrayList<>()
                : new ArrayList<>(applicableTeachingStages);
    }

    public List<String> getSections() {
        return List.copyOf(sections);
    }

    public void setSections(List<String> sections) {
        this.sections = sections == null ? new ArrayList<>() : new ArrayList<>(sections);
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public Long getParseDurationMs() {
        return parseDurationMs;
    }

    public void setParseDurationMs(Long parseDurationMs) {
        this.parseDurationMs = parseDurationMs;
    }

    public MaterialParseStatus getParseStatus() {
        return parseStatus;
    }

    public void setParseStatus(MaterialParseStatus parseStatus) {
        this.parseStatus = parseStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getParsedAt() {
        return parsedAt;
    }

    public void setParsedAt(LocalDateTime parsedAt) {
        this.parsedAt = parsedAt;
    }
}
