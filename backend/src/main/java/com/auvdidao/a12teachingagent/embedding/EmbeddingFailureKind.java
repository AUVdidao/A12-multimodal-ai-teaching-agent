package com.auvdidao.a12teachingagent.embedding;

public enum EmbeddingFailureKind {
    INVALID_INPUT,
    NOT_CONFIGURED,
    AUTHENTICATION,
    RATE_LIMITED,
    TIMEOUT,
    TRANSPORT,
    UPSTREAM_FAILURE,
    INVALID_RESPONSE
}
