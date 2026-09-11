package com.auvdidao.a12teachingagent.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class TemplateChecksum {
    private TemplateChecksum() { }

    static String sha256(byte[] value) {
        return sha256(value, 0, value.length);
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String canonicalJson(ObjectMapper objectMapper, JsonNode value) {
        return canonicalize(value);
    }

    static String canonicalJson(ObjectMapper objectMapper, String json) {
        try {
            return canonicalize(objectMapper.readTree(json));
        } catch (Exception exception) {
            throw new IllegalArgumentException("JSON cannot be canonicalized", exception);
        }
    }

    static String canonicalSha256(ObjectMapper objectMapper, String json) {
        return sha256(canonicalJson(objectMapper, json));
    }

    private static String canonicalize(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isObject()) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            value.fields().forEachRemaining(fields::add);
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            StringBuilder result = new StringBuilder("{");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) result.append(',');
                Map.Entry<String, JsonNode> field = fields.get(i);
                result.append(JsonNodeFactory.instance.textNode(field.getKey()).toString());
                result.append(':').append(canonicalize(field.getValue()));
            }
            return result.append('}').toString();
        }
        if (value.isArray()) {
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) result.append(',');
                result.append(canonicalize(value.get(i)));
            }
            return result.append(']').toString();
        }
        return value.toString();
    }

    private static String sha256(byte[] value, int offset, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOfRange(value, offset, offset + length));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
