package com.auvdidao.a12teachingagent.ai.transport;

import com.auvdidao.a12teachingagent.agent.model.ModelFailureKind;

public class TransportFailureException extends RuntimeException {
    private final ModelFailureKind kind;
    private final String safeCode;
    private final int statusCode;
    private final String requestId;

    public TransportFailureException(ModelFailureKind kind, String safeCode, int statusCode, String requestId, String message) {
        super(message);
        this.kind = kind;
        this.safeCode = safeCode;
        this.statusCode = Math.max(0, statusCode);
        this.requestId = requestId;
    }
    public ModelFailureKind kind() { return kind; }
    public String safeCode() { return safeCode; }
    public int statusCode() { return statusCode; }
    public String requestId() { return requestId; }
}
