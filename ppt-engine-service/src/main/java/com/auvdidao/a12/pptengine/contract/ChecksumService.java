package com.auvdidao.a12.pptengine.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
public class ChecksumService {

    private final ObjectMapper objectMapper;

    public ChecksumService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Canonical JSON rule: object keys are sorted recursively, arrays retain their
     * input order, and the root checksum field is excluded before UTF-8 SHA-256.
     */
    public String compute(ContractModels.LockedPptSpecification specification) {
        return computeCanonicalExcluding(specification, Set.of("checksum"));
    }

    public String computeProfile(ContractModels.ConfirmedTemplateProfile profile) {
        return computeCanonicalExcluding(profile, Set.of());
    }

    public String computeManifest(CompositionModels.ApprovedAssetManifest manifest) {
        return computeCanonicalExcluding(manifest, Set.of("manifestChecksum"));
    }

    /** Attempt identity may change on retry; every Job binding must remain byte-identical. */
    public String computeGenerationJobBinding(CompositionModels.GenerationJob generationJob) {
        return computeCanonicalExcluding(
                generationJob, Set.of("executionAttemptId", "jobBindingChecksum"));
    }

    public String computePlan(CompositionModels.ComposedPresentationPlan plan) {
        return computeCanonicalExcluding(plan, Set.of("planChecksum"));
    }

    public String sha256Utf8(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public String sha256Parts(String... parts) {
        return sha256Utf8(String.join("\u0000", parts));
    }

    private String computeCanonicalExcluding(Object value, Set<String> excludedRootFields) {
        JsonNode source = objectMapper.valueToTree(value);
        if (!(source instanceof ObjectNode objectNode)) {
            throw new IllegalStateException("Canonical checksum input must serialize as an object");
        }
        excludedRootFields.forEach(objectNode::remove);
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(canonicalize(objectNode));
            return sha256(canonical);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Canonical JSON serialization failed", exception);
        }
    }

    public boolean matches(ContractModels.LockedPptSpecification specification) {
        return specification.checksum() != null && specification.checksum().equals(compute(specification));
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node == null ? JsonNodeFactory.instance.nullNode() : node;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> result.add(canonicalize(child)));
            return result;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        Collections.sort(fieldNames);
        for (String fieldName : fieldNames) {
            result.set(fieldName, canonicalize(node.get(fieldName)));
        }
        return result;
    }

    private String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
