package com.auvdidao.a12teachingagent.ai.kimi;

import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KimiChatClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void legacyCompleteDelegatesToStructuredTransport() throws Exception {
        AtomicReference<JsonNode> requestBody = respond(200, success("legacy answer", "stop"));

        String content = client(1).complete(messages(), "kimi-k2.6", 321, 5);

        assertThat(content).isEqualTo("legacy answer");
        assertThat(requestBody.get().path("max_completion_tokens").asInt()).isEqualTo(321);
        assertThat(requestBody.get().has("max_tokens")).isFalse();
        assertThat(requestBody.get().has("response_format")).isFalse();
    }

    @Test
    void structuredCompleteSendsResponseFormatAndPreservesFinishReason() throws Exception {
        AtomicReference<JsonNode> requestBody = respond(200, success("{\"summary\":\"ok\"}", "stop"));
        JsonNode responseFormat = objectMapper.readTree("""
                {"type":"json_schema","json_schema":{"name":"probe","strict":true}}
                """);

        KimiChatResponse response = client(1).complete(new KimiChatRequest(
                messages(), "kimi-k2.6", 456, 5, responseFormat
        ));

        assertThat(response.content()).isEqualTo("{\"summary\":\"ok\"}");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(requestBody.get().path("max_completion_tokens").asInt()).isEqualTo(456);
        assertThat(requestBody.get().has("max_tokens")).isFalse();
        assertThat(requestBody.get().path("response_format")).isEqualTo(responseFormat);
    }

    @Test
    void missingContentRetainsStableInvalidResponseError() throws Exception {
        respond(200, "{\"choices\":[{\"message\":{},\"finish_reason\":\"stop\"}]}");

        assertThatThrownBy(() -> client(1).complete(request(null)))
                .isInstanceOfSatisfying(KimiClientException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("KIMI_INVALID_RESPONSE");
                    assertThat(exception.getStatusCode()).isEqualTo(502);
                    assertThat(exception).hasMessage("Kimi response content is missing");
                });
    }

    @Test
    void httpErrorsRetainExistingStatusAndRetryBehavior() throws Exception {
        for (int status : List.of(401, 429, 500)) {
            stopServer();
            server = null;
            respond(status, "{\"error\":{\"code\":\"provider_error\",\"message\":\"request rejected\"}}");

            assertThatThrownBy(() -> client(1).complete(request(null)))
                    .isInstanceOfSatisfying(KimiClientException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo("KIMI_REQUEST_FAILED");
                        assertThat(exception.getStatusCode()).isEqualTo(status);
                        assertThat(exception).hasMessageContaining("Kimi returned HTTP " + status);
                    });
        }
    }

    @Test
    void timeoutRetainsStableTimeoutError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(Duration.ofSeconds(2).toMillis());
                writeResponse(exchange, 200, success("late", "stop"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        assertThatThrownBy(() -> client(1).complete(new KimiChatRequest(
                messages(), "kimi-k2.6", 50, 1, null
        ))).isInstanceOfSatisfying(KimiClientException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("KIMI_TIMEOUT");
            assertThat(exception.getStatusCode()).isEqualTo(504);
        });
    }

    private KimiChatRequest request(JsonNode responseFormat) {
        return new KimiChatRequest(messages(), "kimi-k2.6", 100, 5, responseFormat);
    }

    private List<Map<String, String>> messages() {
        return List.of(Map.of("role", "user", "content", "Hello"));
    }

    private KimiChatClient client(int attempts) {
        KimiAssistantProperties properties = new KimiAssistantProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setApiKey("test-key");
        properties.setRequestAttempts(attempts);
        properties.setRetryDelayMillis(0);
        properties.setConnectTimeoutSeconds(1);
        return new KimiChatClient(objectMapper, properties);
    }

    private AtomicReference<JsonNode> respond(int status, String responseBody) throws IOException {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            writeResponse(exchange, status, responseBody);
        });
        server.start();
        return requestBody;
    }

    private static void writeResponse(HttpExchange exchange, int status, String responseBody) throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String success(String content, String finishReason) {
        return "{\"choices\":[{\"message\":{\"content\":"
                + quote(content)
                + "},\"finish_reason\":"
                + quote(finishReason)
                + "}]}";
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
