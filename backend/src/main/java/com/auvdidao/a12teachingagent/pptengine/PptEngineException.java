package com.auvdidao.a12teachingagent.pptengine;

public class PptEngineException extends RuntimeException {
    private final String safeCode;
    private final int statusCode;

    public PptEngineException(String safeCode, int statusCode, String message) {
        super(message == null || message.isBlank() ? "PPT Engine request failed" : message);
        this.safeCode = safeCode == null || safeCode.isBlank() ? "PPT_ENGINE_FAILED" : safeCode;
        this.statusCode = statusCode;
    }

    public String safeCode() { return safeCode; }
    public int statusCode() { return statusCode; }
}
