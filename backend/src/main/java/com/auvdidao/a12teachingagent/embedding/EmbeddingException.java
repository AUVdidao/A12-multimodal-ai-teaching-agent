package com.auvdidao.a12teachingagent.embedding;

public class EmbeddingException extends RuntimeException {

    private final EmbeddingFailureKind kind;
    private final int statusCode;

    public EmbeddingException(EmbeddingFailureKind kind, String message) {
        this(kind, message, 0, null);
    }

    public EmbeddingException(EmbeddingFailureKind kind, String message, int statusCode) {
        this(kind, message, statusCode, null);
    }

    public EmbeddingException(EmbeddingFailureKind kind, String message, Throwable cause) {
        this(kind, message, 0, cause);
    }

    public EmbeddingException(
            EmbeddingFailureKind kind,
            String message,
            int statusCode,
            Throwable cause
    ) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public EmbeddingFailureKind getKind() {
        return kind;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
