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
    private final AtomicInteger requestCount = new AtomicInteger();

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
            writeResponse(exchange, 200, response("embedding-model", List.of(List.of(0.1, 0.2)), List.of(0)));
        });
        EmbeddingProperties properties = properties(32, 5);

        EmbeddingBatchResult result = provider(properties).embed(List.of("plain text"));

        assertThat(authorization).hasValue("Bearer test-secret");
        assertThat(requestBody.get().path("model").asText()).isEqualTo("embedding-model");
        assertThat(requestBody.get().path("input").get(0).asText()).isEqualTo("plain text");
        assertThat(requestBody.get().fieldNames()).toIterable().containsExactlyInAnyOrder("model", "input");
        assertThat(result.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(result.model()).isEqualTo("embedding-model");
        assertThat(result.dimensions()).isEqualTo(2);
        assertThat(result.vectors()).extracting(EmbeddingVector::index).containsExactly(0);
        assertThat(result.vectors().get(0).values()).containsExactly(0.1, 0.2);
    }

    @Test
    void acceptsMissingResponseModelAndUsesRequestedModel() throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}"));

        EmbeddingBatchResult result = provider(properties(32, 5)).embed(List.of("plain text"));

        assertThat(result.model()).isEqualTo("embedding-model");
    }

    @Test
    void acceptsTrimmedResponseModelWhenIdentityMatchesExactly() throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, 200,
                response(" embedding-model ", List.of(List.of(0.1, 0.2)), List.of(0))));

        EmbeddingBatchResult result = provider(properties(32, 5)).embed(List.of("plain text"));

        assertThat(result.model()).isEqualTo("embedding-model");
    }

    @Test
    void rejectsDifferentResponseModelWithoutRetry() throws Exception {
        startServer((exchange, body) -> {
            requestCount.incrementAndGet();
            writeResponse(exchange, 200,
                    response("different-model", List.of(List.of(0.1, 0.2)), List.of(0)));
        });
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(3);

        assertThatThrownBy(() -> provider(properties).embed(List.of("plain text")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_RESPONSE));
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void rejectsCaseChangedResponseModel() throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, 200,
                response("EMBEDDING-MODEL", List.of(List.of(0.1, 0.2)), List.of(0))));

        assertThatThrownBy(() -> provider(properties(32, 5)).embed(List.of("plain text")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_RESPONSE));
    }

    @Test
    void rejectsModelChangeBetweenBatchesWithoutRetry() throws Exception {
        AtomicInteger batches = new AtomicInteger();
        startServer((exchange, body) -> {
            requestCount.incrementAndGet();
            String model = batches.getAndIncrement() == 0 ? "embedding-model" : "different-model";
            writeResponse(exchange, 200, response(model, List.of(List.of(0.1, 0.2)), List.of(0)));
        });
        EmbeddingProperties properties = properties(1, 5);
        properties.setRequestAttempts(3);

        assertThatThrownBy(() -> provider(properties).embed(List.of("first", "second")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_RESPONSE));
        assertThat(requestCount).hasValue(2);
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
    void descriptorDefaultsToDisabledWithoutExposingCredentials() {
        EmbeddingProviderDescriptor descriptor = provider(new EmbeddingProperties()).describe();

        assertThat(descriptor.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(descriptor.model()).isEmpty();
        assertThat(descriptor.enabled()).isFalse();
        assertThat(descriptor.configured()).isFalse();
        assertThat(descriptor.toString()).doesNotContain("test-secret");
    }

    @Test
    void descriptorReportsCompleteLocalConfiguration() {
        EmbeddingProviderDescriptor descriptor = provider(properties(32, 5)).describe();

        assertThat(descriptor.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(descriptor.model()).isEqualTo("embedding-model");
        assertThat(descriptor.enabled()).isTrue();
        assertThat(descriptor.configured()).isTrue();
    }

    @Test
    void descriptorReportsMissingBaseUrlAsNotConfigured() {
        EmbeddingProperties properties = properties(32, 5);
        properties.setBaseUrl("");

        assertThat(provider(properties).describe().configured()).isFalse();
    }

    @Test
    void descriptorReportsMissingApiKeyAsNotConfigured() {
        EmbeddingProperties properties = properties(32, 5);
        properties.setApiKey("");

        assertThat(provider(properties).describe().configured()).isFalse();
    }

    @Test
    void descriptorReportsMissingModelAsNotConfigured() {
        EmbeddingProperties properties = properties(32, 5);
        properties.setModel("");

        assertThat(provider(properties).describe().configured()).isFalse();
    }

    @Test
    void descriptorReportsNonPositiveTimeoutAsNotConfigured() {
        EmbeddingProperties properties = properties(32, 5);
        properties.setTimeoutSeconds(0);

        assertThat(provider(properties).describe().configured()).isFalse();
    }

    @Test
    void descriptorReportsInvalidBatchSizeAsNotConfigured() {
        EmbeddingProperties properties = properties(32, 5);
        properties.setBatchSize(0);

        assertThat(provider(properties).describe().configured()).isFalse();
    }

    @Test
    void descriptorReportsInvalidRequestAttemptsAsNotConfigured() {
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(0);

        assertThat(provider(properties).describe().configured()).isFalse();
    }

    @Test
    void descriptorReportsNegativeRetryDelayAsNotConfigured() {
        EmbeddingProperties properties = properties(32, 5);
        properties.setRetryDelayMillis(-1);

        assertThat(provider(properties).describe().configured()).isFalse();
    }

    @Test
    void descriptorReportsUnsupportedProviderAsEnabledButNotConfigured() {
        EmbeddingProperties properties = properties(32, 5);
        properties.setProvider("OTHER");

        EmbeddingProviderDescriptor descriptor = provider(properties).describe();

        assertThat(descriptor.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(descriptor.enabled()).isTrue();
        assertThat(descriptor.configured()).isFalse();
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
        assertThat(properties.getRequestAttempts()).isEqualTo(2);
        assertThat(properties.getRetryDelayMillis()).isEqualTo(1000);
    }

    @Test
    void retries429ThenSucceeds() throws Exception {
        startSequence(List.of(error(429), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        EmbeddingBatchResult result = provider(properties).embed(List.of("alpha"));

        assertThat(requestCount).hasValue(2);
        assertThat(result.vectors()).extracting(EmbeddingVector::index).containsExactly(0);
    }

    @Test
    void retries503ThenSucceeds() throws Exception {
        startSequence(List.of(error(503), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        provider(properties).embed(List.of("alpha"));

        assertThat(requestCount).hasValue(2);
    }

    @Test
    void retries408ThenSucceeds() throws Exception {
        startSequence(List.of(error(408), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        provider(properties).embed(List.of("alpha"));

        assertThat(requestCount).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 504})
    void retriesTransientFiveHundredStatusesThenSucceeds(int status) throws Exception {
        startSequence(List.of(error(status), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        provider(properties).embed(List.of("alpha"));

        assertThat(requestCount).hasValue(2);
    }

    @Test
    void retriesTimeoutThenSucceeds() throws Exception {
        startSequence(List.of(new ResponseSpec(200, null, null, 1_500), success()));
        EmbeddingProperties properties = properties(32, 1);
        properties.setRequestAttempts(2);

        provider(properties).embed(List.of("alpha"));

        assertThat(requestCount).hasValue(2);
    }

    @Test
    void retriesTransportFailureThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startServer((exchange, body) -> {
            if (calls.getAndIncrement() == 0) {
                exchange.close();
                return;
            }
            writeResponse(exchange, 200, successFor(body));
        });
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        provider(properties).embed(List.of("alpha"));

        assertThat(calls).hasValue(2);
    }

    @Test
    void exhausted429RetriesRetainsRateLimitedKind() throws Exception {
        startSequence(List.of(error(429), error(429)));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        assertThatThrownBy(() -> provider(properties).embed(List.of("alpha")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                    assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.RATE_LIMITED);
                    assertThat(exception.getStatusCode()).isEqualTo(429);
                    assertThat(exception).hasMessageNotContaining("alpha");
                    assertThat(exception).hasMessageNotContaining("test-secret");
                });
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void exhausted503RetriesRetainsUpstreamFailureKind() throws Exception {
        startSequence(List.of(error(503), error(503)));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        assertThatThrownBy(() -> provider(properties).embed(List.of("alpha")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                    assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.UPSTREAM_FAILURE);
                    assertThat(exception.getStatusCode()).isEqualTo(503);
                });
        assertThat(requestCount).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void doesNotRetryNonTransientHttpFailures(int status) throws Exception {
        startSequence(List.of(error(status), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        assertThatThrownBy(() -> provider(properties).embed(List.of("alpha")))
                .isInstanceOf(EmbeddingException.class);

        assertThat(requestCount).hasValue(1);
    }

    @Test
    void doesNotRetryMalformedJson() throws Exception {
        startSequence(List.of(new ResponseSpec(200, "not-json", null, 0), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        assertThatThrownBy(() -> provider(properties).embed(List.of("alpha")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_RESPONSE));
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void doesNotRetryInvalidVectorDimensions() throws Exception {
        startSequence(List.of(new ResponseSpec(200, inconsistentDimensionsResponse(), null, 0), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        assertThatThrownBy(() -> provider(properties).embed(List.of("alpha", "beta")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_RESPONSE));
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void attemptsOneDisablesTransientRetry() throws Exception {
        startSequence(List.of(error(503), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(1);

        assertThatThrownBy(() -> provider(properties).embed(List.of("alpha")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.UPSTREAM_FAILURE));
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void rejectsInvalidRetryAttemptsConfiguration() throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, 200, successFor(body)));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(0);

        assertThatThrownBy(() -> provider(properties).embed(List.of("alpha")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.NOT_CONFIGURED));
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void rejectsNegativeRetryDelayConfiguration() throws Exception {
        startServer((exchange, body) -> writeResponse(exchange, 200, successFor(body)));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRetryDelayMillis(-1);

        assertThatThrownBy(() -> provider(properties).embed(List.of("alpha")))
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.NOT_CONFIGURED));
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void retriesOnlyTheFailedBatchAndPreservesGlobalOrder() throws Exception {
        startSequence(List.of(success(), error(503), success(), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);
        List<String> inputs = new ArrayList<>();
        for (int index = 0; index < 70; index++) {
            inputs.add("text-" + index);
        }

        EmbeddingBatchResult result = provider(properties).embed(inputs);

        assertThat(requestCount).hasValue(4);
        assertThat(result.vectors()).hasSize(70);
        for (int index = 0; index < 70; index++) {
            assertThat(result.vectors().get(index).index()).isEqualTo(index);
            assertThat(result.vectors().get(index).values().get(0)).isEqualTo((double) index);
        }
    }

    @Test
    void honorsIntegerRetryAfterWithConfiguredDelayAsLowerBound() throws Exception {
        startSequence(List.of(new ResponseSpec(429, errorBody(), "2", 0), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);
        AtomicReference<Long> sleptMillis = new AtomicReference<>();

        provider(properties, millis -> sleptMillis.set(millis)).embed(List.of("alpha"));

        assertThat(sleptMillis).hasValue(2_000L);
    }

    @Test
    void interruptedRetryRestoresInterruptAndStopsImmediately() throws Exception {
        startSequence(List.of(error(503), success()));
        EmbeddingProperties properties = properties(32, 5);
        properties.setRequestAttempts(2);

        try {
            assertThatThrownBy(() -> provider(properties, millis -> {
                throw new InterruptedException("test interrupt");
            }).embed(List.of("alpha")))
                    .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.TRANSPORT);
                        assertThat(Thread.currentThread().isInterrupted()).isTrue();
                    });
            assertThat(requestCount).hasValue(1);
        } finally {
            Thread.interrupted();
        }
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
        properties.setRequestAttempts(1);
        properties.setRetryDelayMillis(0);
        return properties;
    }

    private OpenAiCompatibleEmbeddingProvider provider(EmbeddingProperties properties) {
        return new OpenAiCompatibleEmbeddingProvider(objectMapper, properties);
    }

    private OpenAiCompatibleEmbeddingProvider provider(
            EmbeddingProperties properties,
            OpenAiCompatibleEmbeddingProvider.Sleeper sleeper
    ) {
        return new OpenAiCompatibleEmbeddingProvider(objectMapper, properties, sleeper);
    }

    private void assertInvalid(EmbeddingProperties properties, List<String> inputs, String message) {
        assertThatThrownBy(() -> provider(properties).embed(inputs))
                .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                    assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_RESPONSE);
                    assertThat(exception).hasMessageContaining(message);
                });
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        requestCount.set(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            JsonNode body = objectMapper.readTree(exchange.getRequestBody());
            handler.handle(exchange, body);
        });
        server.start();
    }

    private void startSequence(List<ResponseSpec> responses) throws IOException {
        startServer((exchange, body) -> {
            int index = requestCount.getAndIncrement();
            ResponseSpec response = responses.get(Math.min(index, responses.size() - 1));
            if (response.delayMillis() > 0) {
                try {
                    Thread.sleep(response.delayMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (response.retryAfter() != null) {
                exchange.getResponseHeaders().set("Retry-After", response.retryAfter());
            }
            writeResponse(exchange, response.status(), response.body() == null
                    ? successFor(body)
                    : response.body());
        });
    }

    private static ResponseSpec success() {
        return new ResponseSpec(200, null, null, 0);
    }

    private static ResponseSpec error(int status) {
        return new ResponseSpec(status, errorBody(), null, 0);
    }

    private static String errorBody() {
        return "{\"error\":{\"message\":\"temporary provider failure\"}}";
    }

    private static String inconsistentDimensionsResponse() {
        return response(
                "embedding-model",
                List.of(List.of(1.0, 2.0), List.of(3.0)),
                List.of(0, 1)
        );
    }

    private String successFor(JsonNode requestBody) {
        ArrayNode data = objectMapper.createArrayNode();
        ArrayNode inputs = (ArrayNode) requestBody.path("input");
        for (int index = inputs.size() - 1; index >= 0; index--) {
            String input = inputs.get(index).asText();
            int value = input.startsWith("text-")
                    ? Integer.parseInt(input.substring("text-".length()))
                    : index;
            ObjectNode item = data.addObject();
            item.put("index", index);
            item.putArray("embedding").add(value).add(value + 0.5);
        }
        return response("embedding-model", data);
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

    private record ResponseSpec(int status, String body, String retryAfter, long delayMillis) {
    }
}
