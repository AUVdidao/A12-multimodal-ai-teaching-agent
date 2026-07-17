package com.auvdidao.a12teachingagent.ai.config;

public enum WorkflowCode {
    CLARIFICATION("WF-01", true),
    REQUIREMENT_SUMMARY("WF-02", true),
    MATERIAL_ANALYSIS("WF-03", true),
    KNOWLEDGE_AND_TEACHING_INTENT("WF-04", true),
    GENERATION_PLAN("WF-05", true),
    CONTENT_DRAFT("WF-06", false),
    REVISION("WF-07", true);

    private final String code;
    private final boolean callable;

    WorkflowCode(String code, boolean callable) {
        this.code = code;
        this.callable = callable;
    }

    public String code() {
        return code;
    }

    public boolean isCallable() {
        return callable;
    }
}
