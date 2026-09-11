package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ResourceLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestBoundaryHttpTest {

    private static final List<String> LIMITED_ENDPOINTS = List.of(
            "/internal/v1/preflight",
            "/internal/v1/compose-plan");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Test
    void knownContentLengthOfTwoMillionAndOneReturnsSafe413() throws Exception {
        byte[] body = new byte[(int) ResourceLimits.MAX_REQUEST_BYTES + 1];
        for (String endpoint : LIMITED_ENDPOINTS) {
            assertSafeTooLarge(endpoint, send(endpoint, HttpRequest.BodyPublishers.ofByteArray(body)));
        }
    }

    @Test
    void realChunkedBodyOfTwoMillionAndOneReturnsSafe413() throws Exception {
        byte[] body = new byte[(int) ResourceLimits.MAX_REQUEST_BYTES + 1];
        for (String endpoint : LIMITED_ENDPOINTS) {
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofInputStream(
                    () -> new ByteArrayInputStream(body));
            assertThat(publisher.contentLength()).isEqualTo(-1);
            assertSafeTooLarge(endpoint, send(endpoint, publisher));
        }
    }

    @Test
    void exactTwoMillionByteBoundaryIsNotRejectedAsTooLarge() throws Exception {
        byte[] body = new byte[(int) ResourceLimits.MAX_REQUEST_BYTES];
        for (String endpoint : LIMITED_ENDPOINTS) {
            HttpResponse<String> response = send(endpoint, HttpRequest.BodyPublishers.ofByteArray(body));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("contract.invalid");
            assertThat(response.body()).doesNotContain("request.tooLarge");
        }
    }

    @Test
    void exactChunkedBoundaryIsNotRejectedAsTooLarge() throws Exception {
        byte[] body = new byte[(int) ResourceLimits.MAX_REQUEST_BYTES];
        for (String endpoint : LIMITED_ENDPOINTS) {
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofInputStream(
                    () -> new ByteArrayInputStream(body));
            assertThat(publisher.contentLength()).isEqualTo(-1);
            HttpResponse<String> response = send(endpoint, publisher);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).doesNotContain("request.tooLarge");
        }
    }

    private HttpResponse<String> send(
            String endpoint,
            HttpRequest.BodyPublisher publisher) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + endpoint))
                .header("Content-Type", "application/json")
                .POST(publisher)
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertSafeTooLarge(String endpoint, HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(413);
        JsonNode body = objectMapper.readTree(response.body());
        String diagnosticPath = endpoint.endsWith("compose-plan")
                ? "/feedback/diagnostics/0" : "/diagnostics/0";
        if (endpoint.endsWith("compose-plan")) {
            assertThat(body.path("contractVersion").asText()).isEqualTo("2.0.0");
            assertThat(body.path("feedbackContractVersion").asText()).isEqualTo("2.0.0");
            assertThat(body.at("/feedback/status").asText()).isEqualTo("FAILED");
        } else {
            assertThat(body.path("contractVersion").asText()).isEqualTo("1.0.0");
            assertThat(body.path("outcome").asText()).isEqualTo("REJECTED");
        }
        assertThat(body.at(diagnosticPath + "/code").asText()).isEqualTo("CONTRACT_INVALID");
        assertThat(body.at(diagnosticPath + "/messageKey").asText()).isEqualTo("request.tooLarge");
        assertThat(body.at(diagnosticPath + "/safeDetails/limitBytes").asText()).isEqualTo("2000000");
        assertThat(response.body())
                .doesNotContain("JsonParseException")
                .doesNotContain("stackTrace")
                .doesNotContain("D:\\")
                .doesNotContain("/home/");
    }
}
