package com.auvdidao.a12.pptengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class PreflightApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthIsIndependentAndHealthy() throws Exception {
        mockMvc.perform(get("/internal/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void validPreflightReturnsPlanAndDoesNotEchoContent() throws Exception {
        String response = mockMvc.perform(post("/internal/v1/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(ContractFixtures.request(objectMapper))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedback.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.plan.slides[0].blockToSlotBindings.block-001").value("slot-body"))
                .andExpect(jsonPath("$.plan.slides[0].assetToSlotBindings.asset-001").value("slot-image"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("植物利用光能");
        assertThat(response).doesNotContain("sourceReference");
    }

    @Test
    void unknownFieldReturns400AndSafeFeedback() throws Exception {
        JsonNode raw = objectMapper.valueToTree(ContractFixtures.request(objectMapper));
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw).put("unknownField", "secret-provider-response");

        String response = mockMvc.perform(post("/internal/v1/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(raw)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.diagnostics[0].code").value("CONTRACT_INVALID"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("secret-provider-response");
    }

    @Test
    void malformedJsonReturns400WithoutParserDetails() throws Exception {
        String response = mockMvc.perform(post("/internal/v1/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.diagnostics[0].code").value("CONTRACT_INVALID"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("JsonParseException");
        assertThat(response).doesNotContain("at com.");
    }

    @Test
    void semanticFailureReturns422AndStructuredFeedback() throws Exception {
        var base = ContractFixtures.specification(objectMapper);
        var invalid = ContractFixtures.withChecksum(objectMapper,
                new com.auvdidao.a12.pptengine.contract.ContractModels.LockedPptSpecification(
                        base.contractVersion(), base.specificationId(), base.projectId(), base.version(),
                        com.auvdidao.a12.pptengine.contract.ContractTypes.SpecificationStatus.DRAFT,
                        base.templateProfileId(), base.templateProfileVersion(), base.targetSlideCount(),
                        base.slideCountTolerance(), base.locale(), base.provider(), base.model(),
                        base.aiSupplementPolicy(), base.lockedBy(), base.lockedAt(), null, base.slides()));
        var request = new com.auvdidao.a12.pptengine.contract.ContractModels.EnginePreflightRequest(
                com.auvdidao.a12.pptengine.contract.ContractTypes.V1, "req-api-draft", invalid,
                ContractFixtures.profile());

        mockMvc.perform(post("/internal/v1/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("SPECIFICATION_NOT_LOCKED"));
    }

    @Test
    void oversizedRequestReturns413WithSafeFeedback() throws Exception {
        byte[] oversized = new byte[(int) com.auvdidao.a12.pptengine.contract.ResourceLimits.MAX_REQUEST_BYTES + 1];
        String response = mockMvc.perform(post("/internal/v1/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.diagnostics[0].messageKey").value("request.tooLarge"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("stackTrace");
    }
}
