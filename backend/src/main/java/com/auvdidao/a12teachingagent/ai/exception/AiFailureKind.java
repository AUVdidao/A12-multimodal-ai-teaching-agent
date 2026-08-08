package com.auvdidao.a12teachingagent.ai.exception;

/** Provider-neutral classification for failures returned by an AI workflow. */
public enum AiFailureKind {
    NOT_CONFIGURED(false),
    AUTHENTICATION(false),
    PERMISSION(false),
    INVALID_REQUEST(false),
    RATE_LIMITED(true),
    TIMEOUT(true),
    TRANSPORT(true),
    UPSTREAM_FAILURE(true),
    INTERRUPTED(false),
    TRUNCATED_RESPONSE(false),
    INVALID_JSON(false),
    SCHEMA_MISMATCH(false),
    VALIDATION_FAILED(false),
    UNKNOWN(false);

    private final boolean transientFailure;

    AiFailureKind(boolean transientFailure) {
        this.transientFailure = transientFailure;
    }

    public boolean isTransient() {
        return transientFailure;
    }
}
