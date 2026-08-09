package com.auvdidao.a12teachingagent.agent.runtime;

/** Bounded pointer to a prior agent output; the output body is never embedded here. */
public record OutputRef(String type, String id, String hash) {
    public OutputRef {
        type = ContractValidation.requiredText(type, "type", 64);
        id = ContractValidation.requiredText(id, "id", 128);
        hash = ContractValidation.requiredText(hash, "hash", 256);
    }
}
