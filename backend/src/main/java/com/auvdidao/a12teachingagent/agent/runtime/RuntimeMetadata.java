package com.auvdidao.a12teachingagent.agent.runtime;

import java.util.List;

/** Sanitized runtime observations; prompts, secrets, and model reasoning do not belong here. */
public record RuntimeMetadata(
        String provider,
        String model,
        List<String> warnings,
        List<String> toolCallIds,
        List<String> retrievedChunkIds
) {
    private static final int MAX_WARNINGS = 20;
    private static final int MAX_WARNING_CHARS = 512;
    private static final int MAX_TOOL_CALL_IDS = 100;
    private static final int MAX_TOOL_CALL_ID_CHARS = 128;
    private static final int MAX_RETRIEVED_CHUNK_IDS = 50;
    private static final int MAX_RETRIEVED_CHUNK_ID_CHARS = 128;

    public RuntimeMetadata {
        provider = ContractValidation.optionalText(provider, "provider", 128);
        model = ContractValidation.optionalText(model, "model", 128);
        warnings = ContractValidation.immutableTextList(
                warnings,
                "warnings",
                MAX_WARNINGS,
                MAX_WARNING_CHARS
        );
        toolCallIds = ContractValidation.immutableTextList(
                toolCallIds,
                "toolCallIds",
                MAX_TOOL_CALL_IDS,
                MAX_TOOL_CALL_ID_CHARS
        );
        retrievedChunkIds = ContractValidation.immutableTextList(
                retrievedChunkIds,
                "retrievedChunkIds",
                MAX_RETRIEVED_CHUNK_IDS,
                MAX_RETRIEVED_CHUNK_ID_CHARS
        );
    }

    public static RuntimeMetadata empty() {
        return new RuntimeMetadata(null, null, List.of(), List.of(), List.of());
    }
}
