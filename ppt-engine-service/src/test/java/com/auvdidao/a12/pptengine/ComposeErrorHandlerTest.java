package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.JsonSchemaCatalog;
import com.auvdidao.a12.pptengine.service.PreflightService;
import com.auvdidao.a12.pptengine.web.ApiExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ComposeErrorHandlerTest {

    @Autowired
    private PreflightService preflightService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JsonSchemaCatalog schemaCatalog;

    @Test
    void controlledCompose500UsesV2ErrorEnvelopeWithoutPlan() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/v1/compose-plan");
        ResponseEntity<?> response = new ApiExceptionHandler(preflightService).unexpected(request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        var raw = objectMapper.valueToTree(response.getBody());
        assertThat(raw.has("plan")).isFalse();
        assertThat(raw.path("contractVersion").asText()).isEqualTo("2.0.0");
        assertThat(raw.at("/feedback/status").asText()).isEqualTo("FAILED");
        assertThat(schemaCatalog.violationCount(
                "engine-compose-plan-error-response.schema.json", raw)).isZero();
    }
}
