package com.auvdidao.a12teachingagent.agent.runtime;

/** Immutable pointer to a business object or immutable business snapshot. */
public record BusinessRef(
        BusinessRefType type,
        String id,
        String version,
        String hash
) {
    public BusinessRef {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        id = ContractValidation.requiredText(id, "id", 128);
        version = ContractValidation.optionalText(version, "version", 64);
        hash = ContractValidation.optionalText(hash, "hash", 256);
        if (type == BusinessRefType.ARTIFACT_VERSION && hash == null) {
            throw new IllegalArgumentException("hash is required for ARTIFACT_VERSION");
        }
    }
}
