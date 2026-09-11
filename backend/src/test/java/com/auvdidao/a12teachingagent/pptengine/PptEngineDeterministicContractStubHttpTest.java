package com.auvdidao.a12teachingagent.pptengine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ExecutionContext;
import static com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ExecutionResponse;
import static com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.GenerationRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PptEngineDeterministicContractStubHttpTest {
    private HttpServer deterministicContractStub;

    @AfterEach
    void stopStub() {
        if (deterministicContractStub != null) {
            deterministicContractStub.stop(0);
        }
    }

    @Test
    void realHttpRoundTripSerializesContractWithoutCredentials() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestContentType = new AtomicReference<>();
        deterministicContractStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        deterministicContractStub.createContext("/internal/v2/execute", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    {"contractVersion":"2.0.0","executionId":"exec-stub-1","engineVersion":"contract-stub-v1","status":"SUCCEEDED",
                     "specificationChecksum":"spec-sha","templateProfileChecksum":"profile-sha",
                     "assetManifestChecksum":"manifest-sha","artifacts":[
                       {"artifactId":"artifact-stub-1","artifactType":"PPTX",
                        "storageKey":"contract-stub/artifact-stub-1.pptx",
                        "sha256":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                        "fileSize":1,"mediaType":"application/vnd.openxmlformats-officedocument.presentationml.presentation"}
                     ],"feedback":[{"code":"TEXT_FIT_REVIEW","severity":"WARNING","impact":"FOLLOW_UP",
                       "operationId":"op-1","slideId":"slide-1","pageNumber":1,"messageKey":"textFit.review",
                       "safeDetails":{"reason":"font-boundary"}}]}
                    """.replaceAll("\\s+", "");
            send(exchange, 200, response);
        });
        deterministicContractStub.start();

        HttpPptEngineClient client = new HttpPptEngineClient(
                "http://127.0.0.1:" + deterministicContractStub.getAddress().getPort(), 2000);
        HttpPptEngineClient.ObservedResponse observed = client.executeObserved(new GenerationRequest(
                "ppt-engine-contract-v1",
                new ExecutionContext(11L, "exec-stub-1", 22L, 33L, 44L, "contract-stub-v1"),
                null, null, List.of()));
        ExecutionResponse response = observed.body();

        assertNotNull(response);
        assertEquals(200, observed.statusCode());
        assertEquals("application/json", observed.contentType());
        assertEquals("2.0.0", response.contractVersion());
        assertEquals("exec-stub-1", response.executionId());
        assertEquals("contract-stub-v1", response.engineVersion());
        assertEquals(PptEngineContracts.EngineStatus.SUCCEEDED, response.status());
        assertEquals("spec-sha", response.specificationChecksum());
        assertEquals("profile-sha", response.templateProfileChecksum());
        assertEquals("manifest-sha", response.assetManifestChecksum());
        assertEquals(1, response.artifacts().size());
        assertEquals(1, response.feedback().size());
        assertEquals("TEXT_FIT_REVIEW", response.feedback().get(0).code());
        assertEquals("FOLLOW_UP", response.feedback().get(0).impact());
        assertEquals("font-boundary", response.feedback().get(0).safeDetails().get("reason"));
        var artifact = response.artifacts().get(0);
        assertEquals("artifact-stub-1", artifact.artifactId());
        assertEquals("PPTX", artifact.artifactType());
        assertEquals("contract-stub/artifact-stub-1.pptx", artifact.storageKey());
        assertEquals("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", artifact.sha256());
        assertEquals(1L, artifact.fileSize());
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", artifact.mediaType());
        assertEquals("application/json", requestContentType.get());
        JsonNode requestJson = new ObjectMapper().readTree(requestBody.get());
        assertEquals("POST", requestMethod.get());
        assertEquals("2.0.0", requestJson.path("contractVersion").asText());
        assertEquals("exec-stub-1", requestJson.path("requestId").asText());
        assertEquals("exec-stub-1", requestJson.path("generationJob").path("executionAttemptId").asText());
        assertEquals("contract-stub-v1", requestJson.path("generationJob").path("engineBuildVersion").asText());
        String body = requestBody.get().toLowerCase();
        assertFalse(body.contains("authorization"));
        assertFalse(body.contains("apikey"));
        assertFalse(body.contains("credential"));
        assertFalse(body.contains("secret"));
    }

    @Test
    void realHttpErrorBecomesSafeEngineFailureWithoutBodyLeak() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestContentType = new AtomicReference<>();
        deterministicContractStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        deterministicContractStub.createContext("/internal/v2/execute", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 503, "{\"error\":\"provider secret must never be surfaced\"}");
        });
        deterministicContractStub.start();

        HttpPptEngineClient client = new HttpPptEngineClient(
                "http://127.0.0.1:" + deterministicContractStub.getAddress().getPort(), 2000);
        HttpPptEngineClient.ObservedResponse observed = client.executeObserved(
                new GenerationRequest("ppt-engine-contract-v1", null, null, null, List.of()));
        assertEquals(503, observed.statusCode());
        assertEquals("application/json", observed.contentType());
        assertEquals(null, observed.body());

        PptEngineException failure = assertThrows(PptEngineException.class,
                () -> client.execute(new GenerationRequest("ppt-engine-contract-v1", null, null, null, List.of())));

        assertEquals("PPT_ENGINE_HTTP_503", failure.safeCode());
        assertEquals(502, failure.statusCode());
        assertFalse(failure.getMessage().toLowerCase().contains("secret"));
        assertEquals("POST", requestMethod.get());
        assertEquals("application/json", requestContentType.get());
        assertNotNull(requestBody.get());
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
