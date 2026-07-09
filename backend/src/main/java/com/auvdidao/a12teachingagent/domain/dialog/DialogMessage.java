package com.auvdidao.a12teachingagent.domain.dialog;

import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import com.auvdidao.a12teachingagent.domain.common.DialogRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "dialog_messages")
public class DialogMessage extends BaseCreatedEntity {

    private Long projectId;
    private String sessionId;

    @Enumerated(EnumType.STRING)
    private DialogRole role;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String content;
    private Integer roundNo;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public DialogRole getRole() {
        return role;
    }

    public void setRole(DialogRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
    }
}
