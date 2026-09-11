package com.auvdidao.a12teachingagent.agent.model;

import java.util.Objects;

/** Safe failure crossing the ModelGateway boundary. It intentionally carries no upstream body or credential. */
public class ModelFailureException extends RuntimeException {
    private final ModelFailureKind kind;
    private final String safeCode;
    private final int statusCode;

    public ModelFailureException(ModelFailureKind kind, String safeCode, int statusCode, String safeMessage) {
        super(normalizeMessage(safeMessage));
        this.kind = Objects.requireNonNull(kind, "kind");
        this.safeCode = normalizeCode(safeCode);
        this.statusCode = Math.max(0, statusCode);
    }

    public ModelFailureKind kind() {
        return kind;
    }

    public String safeCode() {
        return safeCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return kind.retryable();
    }

    private static String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            return "MODEL_FAILURE";
        }
        String normalized = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

    private static String normalizeMessage(String value) {
        if (value == null || value.isBlank()) {
            return "Model request failed";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        normalized = normalized.replaceAll("(?i)sk-[A-Za-z0-9_-]+", "[REDACTED]");
        normalized = normalized.replaceAll("(?i)(api[_ -]?key|authorization|bearer|password|secret|token)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
        return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
    }
}


