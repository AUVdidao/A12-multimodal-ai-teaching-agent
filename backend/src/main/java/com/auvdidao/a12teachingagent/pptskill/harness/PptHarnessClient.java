package com.auvdidao.a12teachingagent.pptskill.harness;

public interface PptHarnessClient {
    PptHarnessDtos.JobResponse start(PptHarnessDtos.StartRequest request);
    PptHarnessDtos.JobResponse get(String taskId);
    PptHarnessDtos.QaReport qaReport(String taskId);
    byte[] download(String taskId);
    String eventsUrl(String taskId);
    com.fasterxml.jackson.databind.JsonNode listTemplates();
    com.fasterxml.jackson.databind.JsonNode getTemplate(String templateId, String version);
}
