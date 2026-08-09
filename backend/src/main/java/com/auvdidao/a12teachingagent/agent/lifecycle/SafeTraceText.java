package com.auvdidao.a12teachingagent.agent.lifecycle;

import java.util.regex.Pattern;

final class SafeTraceText {
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|secret|password|credential)\\s*[:=]\\s*\\S+"
    );
    private static final Pattern BEARER_VALUE = Pattern.compile(
            "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern STACK_FRAME = Pattern.compile(
            "(?i)\\bat\\s+[\\w.$]+\\([^)]*:\\d+\\)"
    );

    private SafeTraceText() {
    }

    static String normalize(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (SECRET_ASSIGNMENT.matcher(normalized).find()
                || BEARER_VALUE.matcher(normalized).find()
                || STACK_FRAME.matcher(normalized).find()) {
            throw new IllegalArgumentException(field + " contains a prohibited sensitive or stack-trace pattern");
        }
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    static String hash(String value) {
        return normalize(value, 256, "hash");
    }
}
