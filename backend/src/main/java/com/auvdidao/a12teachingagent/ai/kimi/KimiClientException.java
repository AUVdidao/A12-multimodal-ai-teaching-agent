package com.auvdidao.a12teachingagent.ai.kimi;

public class KimiClientException extends RuntimeException {

    private final String code;
    private final int statusCode;

    public KimiClientException(String code, String message, int statusCode) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
    }

    public String getCode() {
        return code;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
