package com.auvdidao.a12teachingagent.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public record StructuredOutputContract<T>(
        String schemaName,
        JsonNode schemaDefinition,
        boolean strict,
        Class<T> targetType,
        RepairPolicy repairPolicy
) {
    public static final int MAX_SCHEMA_NAME_CHARS = 64;
    public static final int MAX_SCHEMA_BYTES = 256_000;

    public StructuredOutputContract {
        if (schemaName == null || schemaName.isBlank() || schemaName.length() > MAX_SCHEMA_NAME_CHARS) {
            throw new IllegalArgumentException("schemaName is invalid");
        }
        schemaDefinition = Objects.requireNonNull(schemaDefinition, "schemaDefinition").deepCopy();
        if (!schemaDefinition.isObject() || schemaDefinition.toString().length() > MAX_SCHEMA_BYTES) {
            throw new IllegalArgumentException("schemaDefinition must be a bounded JSON object");
        }
        targetType = Objects.requireNonNull(targetType, "targetType");
        repairPolicy = repairPolicy == null ? RepairPolicy.NONE : repairPolicy;
    }

    public static <T> StructuredOutputContract<T> strict(
            String schemaName, JsonNode schemaDefinition, Class<T> targetType
    ) {
        return new StructuredOutputContract<>(schemaName, schemaDefinition, true, targetType, RepairPolicy.NONE);
    }
}


