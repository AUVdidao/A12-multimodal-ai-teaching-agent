package com.auvdidao.a12teachingagent.pptengine;

import com.auvdidao.a12teachingagent.common.exception.GlobalExceptionHandler;
import com.auvdidao.a12teachingagent.domain.generation.GenerationJobStatus;
import com.auvdidao.a12teachingagent.pptengine.PptGenerationDtos.CreateGenerationJobRequest;
import com.auvdidao.a12teachingagent.pptengine.PptGenerationDtos.GenerationJobResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PptGenerationControllerHttpTest {
    private final PptGenerationService service = mock(PptGenerationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PptGenerationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void exposesDedicatedPptGenerationRouteAndForwardsOnlyJobRequest() throws Exception {
        when(service.start(eq(7L), any(CreateGenerationJobRequest.class))).thenReturn(
                new GenerationJobResponse(101L, 7L, 99L, 99L, "execution-1", "idempotency-1", 11L, 3, "a".repeat(64), 21L, 2, "b".repeat(64), 4, "c".repeat(64), "engine-v1", GenerationJobStatus.SUCCEEDED, "{\"status\":\"SUCCEEDED\"}", null, null, LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(post("/api/projects/7/ppt-generation/jobs")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateGenerationJobRequest(11L, "engine-v1", "idempotency-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.engineReceiptJson").value("{\"status\":\"SUCCEEDED\"}"));
    }

    @Test
    void mapsEngineTransportFailureToSafe503WithoutProviderBody() throws Exception {
        when(service.start(eq(7L), any(CreateGenerationJobRequest.class)))
                .thenThrow(new PptEngineException("PPT_ENGINE_TRANSPORT_FAILED", 503, "provider body must not escape"));

        mockMvc.perform(post("/api/projects/7/ppt-generation/jobs")
                        .contentType("application/json")
                        .content("{\"specificationVersionId\":11,\"engineVersion\":\"engine-v1\",\"idempotencyKey\":\"idempotency-2\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("PPT_ENGINE_TRANSPORT_FAILED"));
    }
}
