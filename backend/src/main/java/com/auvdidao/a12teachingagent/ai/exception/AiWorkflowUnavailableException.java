package com.auvdidao.a12teachingagent.ai.exception;

public class AiWorkflowUnavailableException extends RuntimeException {

    private final String providerCode;
    private final int providerStatusCode;

    public AiWorkflowUnavailableException(String message) {
        this(message, null, 0);
    }

    public AiWorkflowUnavailableException(String message, String providerCode, int providerStatusCode) {
        super(message);
        this.providerCode = providerCode;
        this.providerStatusCode = providerStatusCode;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public int getProviderStatusCode() {
        return providerStatusCode;
    }
}
