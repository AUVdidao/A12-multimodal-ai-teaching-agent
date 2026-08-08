package com.auvdidao.a12teachingagent.ai.exception;

public class AiWorkflowUnavailableException extends RuntimeException {

    private final String providerCode;
    private final int providerStatusCode;
    private final AiFailureKind failureKind;

    public AiWorkflowUnavailableException(String message) {
        this(message, null, 0, AiFailureKind.UNKNOWN);
    }

    public AiWorkflowUnavailableException(String message, String providerCode, int providerStatusCode) {
        this(message, providerCode, providerStatusCode, AiFailureKind.UNKNOWN);
    }

    public AiWorkflowUnavailableException(
            String message,
            String providerCode,
            int providerStatusCode,
            AiFailureKind failureKind
    ) {
        super(message);
        this.providerCode = providerCode;
        this.providerStatusCode = providerStatusCode;
        this.failureKind = failureKind == null ? AiFailureKind.UNKNOWN : failureKind;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public int getProviderStatusCode() {
        return providerStatusCode;
    }

    public AiFailureKind getFailureKind() {
        return failureKind;
    }
}
