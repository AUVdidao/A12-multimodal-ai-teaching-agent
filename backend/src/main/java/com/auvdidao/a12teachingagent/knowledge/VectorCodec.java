package com.auvdidao.a12teachingagent.knowledge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class VectorCodec {

    private final ObjectMapper objectMapper;

    @Autowired
    public VectorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public VectorCodec() {
        this(new ObjectMapper());
    }

    public String encode(List<Double> vector) {
        List<Double> validated = validate(vector);
        try {
            return objectMapper.writeValueAsString(validated);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Vector cannot be encoded as JSON", exception);
        }
    }

    public List<Double> decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Vector JSON must not be blank");
        }

        try {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            if (root == null || !root.isArray() || root.isEmpty()) {
                throw new IllegalArgumentException("Vector JSON must be a non-empty numeric array");
            }

            List<Double> vector = new ArrayList<>(root.size());
            for (JsonNode value : root) {
                if (!value.isNumber()) {
                    throw new IllegalArgumentException("Vector JSON contains a non-numeric value");
                }
                double number = value.doubleValue();
                if (!Double.isFinite(number)) {
                    throw new IllegalArgumentException("Vector JSON contains a non-finite value");
                }
                vector.add(number);
            }
            return List.copyOf(vector);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Vector JSON is invalid", exception);
        }
    }

    private static List<Double> validate(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("Vector must be non-empty");
        }

        List<Double> validated = new ArrayList<>(vector.size());
        for (Double value : vector) {
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Vector values must be finite numbers");
            }
            validated.add(value);
        }
        return List.copyOf(validated);
    }
}
