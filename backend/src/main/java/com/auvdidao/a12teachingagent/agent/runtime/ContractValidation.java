package com.auvdidao.a12teachingagent.agent.runtime;

import java.util.List;
import java.util.UUID;

final class ContractValidation {
    private ContractValidation() {
    }

    static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    static String optionalText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when supplied");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    static String uuidText(String value, String field) {
        String validated = requiredText(value, field, 36);
        try {
            UUID.fromString(validated);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
        return validated;
    }

    static String optionalUuidText(String value, String field) {
        return value == null ? null : uuidText(value, field);
    }

    static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    static void requireRange(long value, long minimum, long maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
    }

    static <T> List<T> immutableList(List<T> values, String field, int maxSize) {
        List<T> normalized = values == null ? List.of() : List.copyOf(values);
        if (normalized.size() > maxSize) {
            throw new IllegalArgumentException(field + " must contain at most " + maxSize + " items");
        }
        return normalized;
    }

    static List<String> immutableTextList(
            List<String> values,
            String field,
            int maxItems,
            int maxItemLength
    ) {
        List<String> normalized = immutableList(values, field, maxItems);
        for (int index = 0; index < normalized.size(); index++) {
            requiredText(normalized.get(index), field + "[" + index + "]", maxItemLength);
        }
        return normalized;
    }
}
