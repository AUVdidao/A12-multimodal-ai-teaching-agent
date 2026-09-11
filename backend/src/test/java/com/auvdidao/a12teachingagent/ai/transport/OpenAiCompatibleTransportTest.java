package com.auvdidao.a12teachingagent.ai.transport;

import com.auvdidao.a12teachingagent.agent.model.ModelFailureKind;
import com.auvdidao.a12teachingagent.agent.model.ModelMessage;
import com.auvdidao.a12teachingagent.agent.model.ModelRequest;
import com.auvdidao.a12teachingagent.ai.connection.ModelConnectionProtocol;
import com.auvdidao.a12teachingagent.ai.connection.ResolvedModelConnection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleTransportTest {
    private final OpenAiCompatibleTransport transport = new OpenAiCompatibleTransport(new ObjectMapper());

    @Test
    void rejectsNonHttpsAndKnownInternalTargetsBeforeAnyRequest() {
        assertThatThrownBy(() -> OpenAiCompatibleTransport.validateBaseUrl("http://api.example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenAiCompatibleTransport.validateBaseUrl("https://localhost/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenAiCompatibleTransport.validateBaseUrl("https://127.0.0.1/v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesRepeatedV1AndCompletionSuffixWithoutDuplicatingPath() {
        assertThat(OpenAiCompatibleTransport.normalizeBaseUrl("https://api.example.com/v1///"))
                .isEqualTo("https://api.example.com/v1");
        assertThat(OpenAiCompatibleTransport.normalizeBaseUrl("https://api.example.com/v1/chat/completions"))
                .isEqualTo("https://api.example.com/v1");
        assertThat(OpenAiCompatibleTransport.normalizeBaseUrl("https://api.example.com/v1/v1/chat/completions/chat/completions///"))
                .isEqualTo("https://api.example.com/v1");
    }

    @Test
    void storedInternalEndpointFailsWithExplicitSsrfCode() {
        ResolvedModelConnection connection = new ResolvedModelConnection(7L, 42L,
                ModelConnectionProtocol.OPENAI_COMPATIBLE, "https://127.0.0.1/v1", "model", "test-key");

        assertThatThrownBy(() -> transport.complete(connection, request()))
                .isInstanceOfSatisfying(TransportFailureException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(ModelFailureKind.CONFIGURATION);
                    assertThat(failure.safeCode()).isEqualTo("SSRF_BLOCKED");
                    assertThat(failure.statusCode()).isEqualTo(400);
                });
    }

    @Test
    void sameAuthorityRelativeRedirectUsesOnlyTheBoundAuthorityAndCanonicalUri() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient client = recordingClient(requests,
                response(302, "/v1/chat/completions", ""),
                response(200, null, success("ok")));
        OpenAiCompatibleTransport transport = new OpenAiCompatibleTransport(new ObjectMapper(), null, client);

        OpenAiCompatibleTransport.TransportResponse result = transport.complete(
                connection("https://example.com/v1/v1/chat/completions/chat/completions"), request());

        assertThat(result.content()).isEqualTo("ok");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).uri().toString()).isEqualTo("https://example.com/v1/chat/completions");
        assertThat(requests.get(1).uri().toString()).isEqualTo("https://example.com/v1/chat/completions");
        assertThat(requests).allMatch(item -> item.headers().firstValue("Authorization").isPresent());
    }

    @Test
    void crossHostRedirectFailsBeforeASecondAuthorizationRequest() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient client = recordingClient(requests, response(302, "https://example.org/v1/chat/completions", ""));
        OpenAiCompatibleTransport transport = new OpenAiCompatibleTransport(new ObjectMapper(), null, client);

        assertThatThrownBy(() -> transport.complete(connection("https://example.com/v1"), request()))
                .isInstanceOfSatisfying(TransportFailureException.class, failure -> {
                    assertThat(failure.safeCode()).isEqualTo("REDIRECT_AUTHORITY_NOT_ALLOWED");
                    assertThat(failure.statusCode()).isEqualTo(400);
                    assertThat(failure).hasMessageNotContaining("redacted-test-key");
                });
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).uri().getHost()).isEqualTo("example.com");
        assertThat(requests.get(0).headers().firstValue("Authorization").isPresent()).isTrue();
    }

    @Test
    void crossPortRedirectFailsClosedEvenWhenHostIsUnchanged() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient client = recordingClient(requests, response(302, "https://example.com:8443/v1/chat/completions", ""));
        OpenAiCompatibleTransport transport = new OpenAiCompatibleTransport(new ObjectMapper(), null, client);

        assertThatThrownBy(() -> transport.complete(connection("https://example.com/v1"), request()))
                .isInstanceOfSatisfying(TransportFailureException.class, failure -> {
                    assertThat(failure.safeCode()).isEqualTo("REDIRECT_AUTHORITY_NOT_ALLOWED");
                    assertThat(failure.statusCode()).isEqualTo(400);
                });
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).headers().firstValue("Authorization").isPresent()).isTrue();
    }

    @Test
    void redirectLimitRemainsEnforcedForSameAuthority() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient client = recordingClient(requests,
                response(302, "/v1/chat/completions", ""),
                response(302, "/v1/chat/completions", ""),
                response(302, "/v1/chat/completions", ""),
                response(302, "/v1/chat/completions", ""));
        OpenAiCompatibleTransport transport = new OpenAiCompatibleTransport(new ObjectMapper(), null, client);

        assertThatThrownBy(() -> transport.complete(connection("https://example.com/v1"), request()))
                .isInstanceOfSatisfying(TransportFailureException.class, failure ->
                        assertThat(failure.safeCode()).isEqualTo("UPSTREAM_REDIRECT_LIMIT"));
        assertThat(requests).hasSize(OpenAiCompatibleTransport.MAX_REDIRECTS + 1);
        assertThat(requests).allMatch(item -> item.headers().firstValue("Authorization").isPresent());
    }

    private static HttpClient recordingClient(List<HttpRequest> requests, HttpResponse<InputStream>... responses) throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicInteger index = new AtomicInteger();
        doAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return responses[index.getAndIncrement()];
        }).when(client).send(any(HttpRequest.class), any());
        return client;
    }

    private static HttpResponse<InputStream> response(int status, String location, String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        Map<String, List<String>> headers = location == null ? Map.of() : Map.of("Location", List.of(location));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private static ResolvedModelConnection connection(String baseUrl) {
        return new ResolvedModelConnection(7L, 42L, ModelConnectionProtocol.OPENAI_COMPATIBLE,
                baseUrl, "model-v1", "redacted-test-key");
    }

    private static ModelRequest request() {
        return new ModelRequest(null, List.of(ModelMessage.user("hello")), "model-v1", 5, 0, 1000,
                Map.of("purpose", "TEST"), List.of());
    }

    private static String success(String content) {
        return "{\"id\":\"stub\",\"choices\":[{\"message\":{\"content\":\"" + content + "\"},\"finish_reason\":\"stop\"}]}";
    }
}
