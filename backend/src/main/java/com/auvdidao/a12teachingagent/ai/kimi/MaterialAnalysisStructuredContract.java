package com.auvdidao.a12teachingagent.ai.kimi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class MaterialAnalysisStructuredContract {

    private MaterialAnalysisStructuredContract() {
    }

    public static JsonNode responseFormat(ObjectMapper objectMapper) {
        ObjectNode stringArray = objectMapper.createObjectNode();
        stringArray.put("type", "array");
        stringArray.set("items", objectMapper.createObjectNode().put("type", "string"));

        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("summary", objectMapper.createObjectNode().put("type", "string"));
        properties.set("keywords", stringArray.deepCopy());
        properties.set("teachingUses", stringArray.deepCopy());

        ArrayNode required = objectMapper.createArrayNode()
                .add("summary")
                .add("keywords")
                .add("teachingUses");

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);

        ObjectNode jsonSchema = objectMapper.createObjectNode();
        jsonSchema.put("name", "material_analysis");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", schema);

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.set("json_schema", jsonSchema);
        return responseFormat;
    }
}
