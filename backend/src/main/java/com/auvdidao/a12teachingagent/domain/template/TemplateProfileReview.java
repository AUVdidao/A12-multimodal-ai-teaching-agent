package com.auvdidao.a12teachingagent.domain.template;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "template_profile_reviews")
public class TemplateProfileReview extends BaseCreatedEntity {

    private Long templateId;
    private Long profileVersionId;
    private Long reviewerUserId;

    @Enumerated(EnumType.STRING)
    private TemplateProfileReviewAction action;

    @Column(nullable = false, length = 64)
    private String checksumAtAction;

    @Column(length = 500)
    private String note;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getProfileVersionId() { return profileVersionId; }
    public void setProfileVersionId(Long profileVersionId) { this.profileVersionId = profileVersionId; }
    public Long getReviewerUserId() { return reviewerUserId; }
    public void setReviewerUserId(Long reviewerUserId) { this.reviewerUserId = reviewerUserId; }
    public TemplateProfileReviewAction getAction() { return action; }
    public void setAction(TemplateProfileReviewAction action) { this.action = action; }
    public String getChecksumAtAction() { return checksumAtAction; }
    public void setChecksumAtAction(String checksumAtAction) { this.checksumAtAction = checksumAtAction; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}


