package com.auvdidao.a12teachingagent.clarification;

public record MissingField(
        String field,
        String label,
        String reason
) {
}
