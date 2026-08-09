package com.auvdidao.a12teachingagent.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleEmbeddingProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void oneInputUsesOpenAiCompatibleEmbeddingsProtocol() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer((exchange, body) -> {
            requestBody.set(body);
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeResponse(exchange, 200, response("server-model", List.of(List.of(0.1, 0.2)), List.of(0)));
        });
        EmbeddingProperties properties = properties(32, 5);

        EmbeddingBatchResult result = provider(properties).embed(List.of("plain text"));

        assertThat(authorization).hasValue("Bearer test-secret");
        assertThat(requestBody.get().path("model").asText()).isEqualTo("embedding-model");
        assertThat(requestBody.get().path("input").get(0).asText()).isEqualTo("plain text");
        assertThat(requestBody.get().fieldNames()).toIterable().containsExactlyInAnyOrder("model", "input");
        assertThat(result.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(result.model()).isEqualTo("server-model");
        assertThat(result.dimensions()).isEqualTo(2);
        assertThat(result.vectors()).extracting(EmbeddingVector::index).containsExactly(0);
        assertThat(result.vectors().get(0).values()).containsExactly(0.1, 0.2);
    }

    @Test
    void batchesInputsAndRestoresOrderWhenEachBatchIsReturnedReversed() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer((exchange, body) -> {
            requests.incrementAndGet();
            ArrayNode data = objectMapper.createArrayNode();
            ArrayNode inputs = (ArrayNode) body.path("input");
            for (int index = inputs.size() - 1; index >= 0; index--) {
                int originalIndex = Integer.parseInt(inputs.get(index).asText().substring("text-".length()));
                ObjectNode item = data.addObject();
                item.put("index", index);
                item.putArray("embedding").add(originalIndex).add(originalIndex + 0.5);
            }
            writeResponse(exchange, 200, response("embedding-model", data));
        });
        EmbeddingProperties properties = properties(32, 5);
        List<String> inputs = new ArrayList<>();
        for (int index = 0; index < 70; index++) {
            inputs.add("text-" + index);
        }

        EmbeddingBatchResult result = provider(properties).embed(inputs);

        assertThat(requests).hasValue(3);
        assertThat(result.vectors()).extracting(EmbeddingVector::index).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 70).boxed().toList()
        );
        for (int index = 0; index < 70; index++) {
            assertThat(result.vectors().get(index).values().get(0)).isEqualTo((double) index);
        }
    }

    @Test
    void rejectsInconsistentDimensions() throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, 200, response(
                "embedding-model",
                List.of(List.of(1.0, 2.0), List.of(3.0)),
                List.of(0, 1)
        )));

        assertInvalid(properties(32, 5), List.of("a", "b"), "inconsistent dimensions");
    }

    @Test
    void rejectsDuplicateIndex() throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, 200, response(
                "embedding-model",
                List.of(List.of(1.0), List.of(2.0)),
                List.of(0, 0)
        )));

        assertInvalid(properties(32, 5), List.of("a", "b"), "duplicate");
    }

    @Test
    void rejectsMissingVector() throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, 200, response(
                "embedding-model",
                List.of(List.of(1.0)),
                List.of(0)
        )));

        assertInvalid(properties(32, 5), List.of("a", "b"), "data count");
    }

    @Test
    void rejectsNaNVectorValue() throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, 200,
                "{\"model\":\"embedding-model\",\"data\":[{\"index\":0,\"embedding\":[\"NaN\"]}]}"));

        assertInvalid(properties(32, 5), List.of("a"), "non-numeric");
    }

    @Test
    void rejectsInfinityVectorValue() throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, 200,
                "{\"model\":\"embedding-model\",\"data\":[{\"index\":0,\"embedding\":[\"Infinity\"]}]}"));

        assertInvalid(properties(32, 5), List.of("a"), "non-numeric");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500})
    void classifiesHttpFailuresWithoutLeakingSecretOrInput(int status) throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, status,
                "{\"error\":{\"message\":\"very-secret-source-text\",\"api_key\":\"test-secret\"}}"));
        EmbeddingProperties properties = properties(32, 5);

        assertThatThrownBy(() -> provider(properties).embed(List.of("very-secret-source-text")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                    assertThat(exception.getKind()).isEqualTo(switch (status) {
                        case 401 -> EmbeddingFailureKind.AUTHENTICATION;
                        case 429 -> EmbeddingFailureKind.RATE_LIMITED;
                        default -> EmbeddingFailureKind.UPSTREAM_FAILURE;
                    });
                    assertThat(exception).hasMessageNotContaining("test-secret");
                    assertThat(exception).hasMessageNotContaining("very-secret-source-text");
                });
    }

    @Test
    void classifiesTimeout() throws Exception {
        startServer((exchange, body) -> {
            try {
                Thread.sleep(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        EmbeddingProperties properties = properties(32, 1);

        assertThatThrownBy(() -> provider(properties).embed(List.of("a")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.TIMEOUT));
    }

    @Test
    void classifiesTransportFailure() {
        EmbeddingProperties properties = properties(32, 5);
        properties.setBaseUrl("http://127.0.0.1:1/v1");

        assertThatThrownBy(() -> provider(properties).embed(List.of("a")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.TRANSPORT));
    }

    @Test
    void disabledProviderFailsClosedWithoutMakingHttpRequest() {
        EmbeddingProperties properties = new EmbeddingProperties();

        assertThatThrownBy(() -> provider(properties).embed(List.of("a")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.NOT_CONFIGURED));
    }

    @Test
    void rejectsEmptyAndBlankInputs() {
        EmbeddingProperties properties = properties(32, 5);

        assertThatThrownBy(() -> provider(properties).embed(List.of()))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_INPUT));
        assertThatThrownBy(() -> provider(properties).embed(List.of(" ")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_INPUT));
    }

    @Test
    void defaultsAreDisabledAndUseIndependentEnvironmentVariables() {
        EmbeddingProperties properties = new EmbeddingProperties();

        assertThat(properties.getProvider()).isEqualTo("DISABLED");
        assertThat(properties.getBaseUrl()).isEmpty();
        assertThat(properties.getApiKey()).isEmpty();
        assertThat(properties.getModel()).isEmpty();
        assertThat(properties.getTimeoutSeconds()).isEqualTo(60);
        assertThat(properties.getBatchSize()).isEqualTo(32);
    }

    private EmbeddingProperties properties(int batchSize, long timeoutSeconds) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setProvider("OPENAI_COMPATIBLE");
        String baseUrl = server == null
                ? "http://127.0.0.1:1/v1"
                : "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        properties.setBaseUrl(baseUrl);
        properties.setApiKey("test-secret");
        properties.setModel("embedding-model");
        properties.setBatchSize(batchSize);
        properties.setTimeoutSeconds(timeoutSeconds);
        return properties;
    }

    private OpenAiCompatibleEmbeddingProvider provider(EmbeddingProperties properties) {
        return new OpenAiCompatibleEmbeddingProvider(objectMapper, properties);
    }

    private void assertInvalid(EmbeddingProperties properties, List<String> inputs, String message) {
        assertThatThrownBy(() -> provider(properties).embed(inputs))
                .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                    assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_RESPONSE);
                    assertThat(exception).hasMessageContaining(message);
                });
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            JsonNode body = objectMapper.readTree(exchange.getRequestBody());
            handler.handle(exchange, body);
        });
        server.start();
    }

    private static String response(String model, List<List<Double>> vectors, List<Integer> indices) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode data = mapper.createArrayNode();
        for (int index = 0; index < vectors.size(); index++) {
            ObjectNode item = data.addObject();
            item.put("index", indices.get(index));
            ArrayNode embedding = item.putArray("embedding");
            vectors.get(index).forEach(embedding::add);
        }
        return response(model, data);
    }

    private static String response(String model, ArrayNode data) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.set("data", data);
        return root.toString();
    }

    private static void writeResponse(HttpExchange exchange, int status, String responseBody) throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange, JsonNode body) throws IOException;
    }
}
