package com.auvdidao.a12teachingagent.domain.generation;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
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
@Table(name = "teaching_intents")
public class TeachingIntent extends BaseAuditableEntity {

    private Long projectId;
    private Long requirementSummaryId;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String generationGoal;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String contentBasis;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String teachingApproach;

    private String interactionMode;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "teaching_intent_output_types", joinColumns = @JoinColumn(name = "intent_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "output_type")
    private List<String> outputTypes = new ArrayList<>();

    private String stylePreference;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "teaching_intent_evidence", joinColumns = @JoinColumn(name = "intent_id"))
    @OrderColumn(name = "sort_order")
    private List<TeachingIntentEvidence> evidenceItems = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private TeachingIntentStatus status;

    private LocalDateTime confirmedAt;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getRequirementSummaryId() {
        return requirementSummaryId;
    }

    public void setRequirementSummaryId(Long requirementSummaryId) {
        this.requirementSummaryId = requirementSummaryId;
    }

    public String getGenerationGoal() {
        return generationGoal;
    }

    public void setGenerationGoal(String generationGoal) {
        this.generationGoal = generationGoal;
    }

    public String getContentBasis() {
        return contentBasis;
    }

    public void setContentBasis(String contentBasis) {
        this.contentBasis = contentBasis;
    }

    public String getTeachingApproach() {
        return teachingApproach;
    }

    public void setTeachingApproach(String teachingApproach) {
        this.teachingApproach = teachingApproach;
    }

    public String getInteractionMode() {
        return interactionMode;
    }

    public void setInteractionMode(String interactionMode) {
        this.interactionMode = interactionMode;
    }

    public List<String> getOutputTypes() {
        return List.copyOf(outputTypes);
    }

    public void setOutputTypes(List<String> outputTypes) {
        this.outputTypes = outputTypes == null ? new ArrayList<>() : new ArrayList<>(outputTypes);
    }

    public String getStylePreference() {
        return stylePreference;
    }

    public void setStylePreference(String stylePreference) {
        this.stylePreference = stylePreference;
    }

    public List<TeachingIntentEvidence> getEvidenceItems() {
        return List.copyOf(evidenceItems);
    }

    public void setEvidenceItems(List<TeachingIntentEvidence> evidenceItems) {
        this.evidenceItems = evidenceItems == null ? new ArrayList<>() : new ArrayList<>(evidenceItems);
    }

    public TeachingIntentStatus getStatus() {
        return status;
    }

    public void setStatus(TeachingIntentStatus status) {
        this.status = status;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
