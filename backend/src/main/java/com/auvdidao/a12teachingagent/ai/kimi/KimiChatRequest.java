package com.auvdidao.a12teachingagent.ai.kimi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record KimiChatRequest(
        List<Map<String, String>> messages,
        String model,
        int maxCompletionTokens,
        int timeoutSeconds,
        JsonNode responseFormat
) {
}
