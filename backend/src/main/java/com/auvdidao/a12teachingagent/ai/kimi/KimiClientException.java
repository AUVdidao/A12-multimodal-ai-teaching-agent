package com.auvdidao.a12teachingagent.ai.kimi;

import com.auvdidao.a12teachingagent.ai.exception.AiFailureKind;

public class KimiClientException extends RuntimeException {

    private final String code;
    private final int statusCode;
    private final AiFailureKind failureKind;

    public KimiClientException(String code, String message, int statusCode) {
        this(code, message, statusCode, AiFailureKind.UNKNOWN);
    }

    public KimiClientException(String code, String message, int statusCode, AiFailureKind failureKind) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
        this.failureKind = failureKind == null ? AiFailureKind.UNKNOWN : failureKind;
    }

    public String getCode() {
        return code;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public AiFailureKind getFailureKind() {
        return failureKind;
    }
}
