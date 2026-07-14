package com.auvdidao.a12teachingagent.domain.generation;

import com.auvdidao.a12teachingagent.domain.common.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "generation_plans")
public class GenerationPlan extends BaseAuditableEntity {

    private Long projectId;
    private Long teachingIntentId;
    private String provider;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String pptOutline;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String docOutline;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String interactionPlan;

    private Boolean confirmed;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getTeachingIntentId() {
        return teachingIntentId;
    }

    public void setTeachingIntentId(Long teachingIntentId) {
        this.teachingIntentId = teachingIntentId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getPptOutline() {
        return pptOutline;
    }

    public void setPptOutline(String pptOutline) {
        this.pptOutline = pptOutline;
    }

    public String getDocOutline() {
        return docOutline;
    }

    public void setDocOutline(String docOutline) {
        this.docOutline = docOutline;
    }

    public String getInteractionPlan() {
        return interactionPlan;
    }

    public void setInteractionPlan(String interactionPlan) {
        this.interactionPlan = interactionPlan;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }
}
