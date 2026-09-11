package com.auvdidao.a12teachingagent.ai.transport;

import com.auvdidao.a12teachingagent.agent.model.ModelFailureKind;
import com.auvdidao.a12teachingagent.agent.model.ModelMessage;
import com.auvdidao.a12teachingagent.agent.model.ModelRequest;
import com.auvdidao.a12teachingagent.agent.model.ModelUsage;
import com.auvdidao.a12teachingagent.ai.audit.ModelCallAuditService;
import com.auvdidao.a12teachingagent.ai.connection.ModelConnectionProtocol;
import com.auvdidao.a12teachingagent.ai.connection.ResolvedModelConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class OpenAiCompatibleTransport {
    public static final long MAX_TIMEOUT_MS = 120_000L;
    public static final int MAX_REDIRECTS = 3;
    public static final int MAX_RESPONSE_BYTES = 2_000_000;

    private final ObjectMapper objectMapper;
    private final ModelCallAuditService auditService;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public OpenAiCompatibleTransport(ObjectMapper objectMapper, ModelCallAuditService auditService) {
        this(objectMapper, auditService, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** Package-visible injection seam for deterministic transport tests; production wiring uses the constructor above. */
    OpenAiCompatibleTransport(ObjectMapper objectMapper, ModelCallAuditService auditService, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public OpenAiCompatibleTransport(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public TransportResponse complete(ResolvedModelConnection connection, ModelRequest request) {
        if (connection == null || request == null || connection.protocol() != ModelConnectionProtocol.OPENAI_COMPATIBLE) {
            throw new TransportFailureException(ModelFailureKind.CONFIGURATION, "TRANSPORT_CONFIGURATION_INVALID", 0, null,
                    "OpenAI-compatible transport configuration is invalid");
        }
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        int httpStatus = 0;
        String host = safeHost(connection.baseUrl());
        try {
            URI endpoint;
            try { endpoint = endpoint(connection.baseUrl()); }
            catch (RuntimeException exception) {
                throw failure(ModelFailureKind.CONFIGURATION, "SSRF_BLOCKED", 400, requestId,
                        "Model endpoint is not allowed");
            }
            enforceSafeEndpoint(endpoint, requestId);
            Authority initialAuthority = authority(endpoint);
            Map<String, Object> body = requestBody(connection.modelId(), request);
            URI current = endpoint;
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                validateBaseUrl(current.toString().replaceFirst("/chat/completions$", ""));
                enforceSafeEndpoint(current, requestId);
                HttpRequest httpRequest = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofMillis(clampTimeout(request.timeoutMs())))
                        .header("Authorization", "Bearer " + connection.apiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build();
                HttpResponse<InputStream> response;
                try {
                    response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                } catch (java.net.http.HttpTimeoutException exception) {
                    throw failure(ModelFailureKind.TIMEOUT, "UPSTREAM_TIMEOUT", 504, requestId, "Model request timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw failure(ModelFailureKind.INTERRUPTED, "UPSTREAM_INTERRUPTED", 503, requestId, "Model request was interrupted");
                } catch (IOException exception) {
                    throw failure(ModelFailureKind.UPSTREAM, "UPSTREAM_NETWORK_FAILURE", 502, requestId, "Model endpoint could not be reached");
                }
                httpStatus = response.statusCode();
                byte[] bytes;
                try (InputStream input = response.body()) {
                    if (response.headers().firstValueAsLong("Content-Length").orElse(0L) > MAX_RESPONSE_BYTES) {
                        throw failure(ModelFailureKind.INVALID_OUTPUT, "UPSTREAM_RESPONSE_TOO_LARGE", 502, requestId, "Model response is too large");
                    }
                    bytes = readLimited(input);
                } catch (IOException exception) {
                    throw failure(ModelFailureKind.UPSTREAM, "UPSTREAM_RESPONSE_READ_FAILED", 502, requestId, "Model response could not be read");
                }
                if (isRedirect(response.statusCode())) {
                    if (redirect == MAX_REDIRECTS) throw failure(ModelFailureKind.UPSTREAM, "UPSTREAM_REDIRECT_LIMIT", 502, requestId, "Model endpoint redirect limit exceeded");
                    String location = response.headers().firstValue("Location").orElse("");
                    URI redirected;
                    try { redirected = current.resolve(location); }
                    catch (IllegalArgumentException exception) { throw failure(ModelFailureKind.UPSTREAM, "UPSTREAM_REDIRECT_INVALID", 502, requestId, "Model endpoint redirect is invalid"); }
                    if (!initialAuthority.equals(authority(redirected))) {
                        throw failure(ModelFailureKind.CONFIGURATION, "REDIRECT_AUTHORITY_NOT_ALLOWED", 400, requestId,
                                "Model endpoint redirect authority is not allowed");
                    }
                    current = redirected;
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw httpFailure(response.statusCode(), requestId);
                }
                return parseResponse(bytes, response.statusCode(), requestId);
            }
            throw failure(ModelFailureKind.UPSTREAM, "UPSTREAM_REDIRECT_LIMIT", 502, requestId, "Model endpoint redirect limit exceeded");
        } catch (TransportFailureException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(ModelFailureKind.INVALID_OUTPUT, "UPSTREAM_RESPONSE_INVALID", 502, requestId, "Model response format is invalid");
        } finally {
            recordAudit(requestId, connection, request, host, httpStatus, started);
        }
    }

    public TransportResponse verify(ResolvedModelConnection connection) {
        ModelRequest request = new ModelRequest(null, List.of(ModelMessage.user("Reply with OK")),
                connection.modelId(), 1, 0, 15_000L, Map.of("purpose", "CONNECTION_VERIFICATION"), List.of("connection-verification"));
        return complete(connection, request);
    }

    public static String normalizeBaseUrl(String raw) {
        validateBaseUrl(raw);
        String value = raw.strip().replaceAll("/+\\z", "");
        while (value.endsWith("/chat/completions")) {
            value = value.substring(0, value.length() - "/chat/completions".length()).replaceAll("/+\\z", "");
        }
        while (value.endsWith("/v1/v1")) {
            value = value.substring(0, value.length() - "/v1".length());
        }
        return value;
    }

    public static void validateBaseUrl(String raw) {
        if (!StringUtils.hasText(raw)) throw new IllegalArgumentException("base URL is required");
        URI uri;
        try { uri = URI.create(raw.strip()); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("base URL is invalid"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPath().contains("..")) {
            throw new IllegalArgumentException("only external HTTPS base URLs are allowed");
        }
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (SetOfBlockedHosts.contains(host) || isLiteralDisallowedAddress(host)) {
            throw new IllegalArgumentException("base URL resolves to a blocked address");
        }
    }

    private static final java.util.Set<String> SetOfBlockedHosts = java.util.Set.of(
            "localhost", "localhost.localdomain", "ip6-localhost", "metadata", "metadata.google.internal",
            "instance-data.ec2.internal", "host.docker.internal", "kubernetes.default.svc"
    );

    private static boolean isLiteralDisallowedAddress(String host) {
        try { return disallowedAddress(InetAddress.getByName(host)); }
        catch (Exception exception) { return false; }
    }

    private static URI endpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        return URI.create(normalized + "/chat/completions");
    }

    private static Authority authority(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        int port = uri.getPort() < 0 && "https".equals(scheme) ? 443 : uri.getPort();
        return new Authority(scheme, host, port);
    }

    private static void validateResolvedHost(URI uri) {
        validateBaseUrl(uri.toString().replaceFirst("/chat/completions$", ""));
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (disallowedAddress(address)) throw new IllegalArgumentException("base URL resolves to a blocked address");
            }
        } catch (IOException exception) { throw new IllegalArgumentException("base URL DNS resolution failed"); }
    }

    private static void enforceSafeEndpoint(URI uri, String requestId) {
        try { validateResolvedHost(uri); }
        catch (RuntimeException exception) {
            throw failure(ModelFailureKind.CONFIGURATION, "SSRF_BLOCKED", 400, requestId,
                    "Model endpoint is not allowed");
        }
    }

    private static boolean disallowedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        if (bytes.length == 16 && address instanceof Inet6Address && ((bytes[0] & 0xff) & 0xfe) == 0xfc) return true;
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff, b = bytes[1] & 0xff;
            if (a == 169 && b == 254) return true;
            if (a == 100 && b == 64) return (bytes[2] & 0xff) == 100 && (bytes[3] & 0xff) == 200;
        }
        return false;
    }

    private Map<String, Object> requestBody(String model, ModelRequest request) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (StringUtils.hasText(request.systemInstruction())) messages.add(Map.of("role", "system", "content", request.systemInstruction()));
        for (ModelMessage message : request.messages()) messages.add(Map.of("role", message.role().name().toLowerCase(), "content", message.content()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", request.maxCompletionTokens());
        body.put("temperature", request.temperature());
        return body;
    }

    private TransportResponse parseResponse(byte[] bytes, int status, String requestId) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            JsonNode choice = root.path("choices").path(0);
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual() || !StringUtils.hasText(content.asText())) throw failure(ModelFailureKind.INVALID_OUTPUT, "UPSTREAM_RESPONSE_FORMAT_INVALID", 502, requestId, "Model response content is missing");
            JsonNode usage = root.path("usage");
            ModelUsage modelUsage = new ModelUsage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), usage.path("total_tokens").asInt(0));
            return new TransportResponse(content.asText().strip(), root.path("id").asText(requestId), choice.path("finish_reason").asText("stop"), modelUsage, status, requestId);
        } catch (IOException exception) { throw failure(ModelFailureKind.INVALID_OUTPUT, "UPSTREAM_RESPONSE_FORMAT_INVALID", 502, requestId, "Model response is not valid JSON"); }
    }

    private TransportFailureException httpFailure(int status, String requestId) {
        if (status == 401 || status == 403) return failure(ModelFailureKind.AUTHENTICATION, "UPSTREAM_AUTHENTICATION_FAILED", status, requestId, "Model authentication failed");
        if (status == 402) return failure(ModelFailureKind.QUOTA, "UPSTREAM_QUOTA_EXCEEDED", status, requestId, "Model account quota is unavailable");
        if (status == 408) return failure(ModelFailureKind.TIMEOUT, "UPSTREAM_TIMEOUT", status, requestId, "Model request timed out");
        if (status == 429) return failure(ModelFailureKind.RATE_LIMIT, "UPSTREAM_RATE_LIMITED", status, requestId, "Model request was rate limited");
        if (status >= 500) return failure(ModelFailureKind.UPSTREAM, "UPSTREAM_SERVER_FAILURE", status, requestId, "Model server failed");
        return failure(ModelFailureKind.INVALID_REQUEST, status == 404 ? "UPSTREAM_MODEL_OR_ENDPOINT_NOT_FOUND" : "UPSTREAM_REQUEST_REJECTED", status, requestId, "Model request was rejected");
    }

    private void recordAudit(String requestId, ResolvedModelConnection connection, ModelRequest request, String host, int status, long started) {
        if (auditService == null) return;
        try { auditService.record(requestId, connection.ownerUserId(), connection.id(), connection.protocol().name(), host,
                connection.modelId(), request.metadata().getOrDefault("purpose", "MODEL_COMPLETION"), "USER_BYOK", status,
                Math.max(0, (System.nanoTime() - started) / 1_000_000)); }
        catch (RuntimeException ignored) { /* audit persistence must not leak secrets or mask provider result */ }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > MAX_RESPONSE_BYTES) throw new IOException("response too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
    private static boolean isRedirect(int status) { return status == 301 || status == 302 || status == 303 || status == 307 || status == 308; }
    private static long clampTimeout(long requested) { return Math.max(1_000L, Math.min(MAX_TIMEOUT_MS, requested)); }
    private static String safeHost(String baseUrl) { try { return URI.create(baseUrl).getHost(); } catch (RuntimeException exception) { return "invalid"; } }
    private static TransportFailureException failure(ModelFailureKind kind, String code, int status, String requestId, String message) { return new TransportFailureException(kind, code, status, requestId, message); }

    private record Authority(String scheme, String host, int port) { }

    public record TransportResponse(String content, String upstreamResponseId, String finishReason, ModelUsage usage,
                                    int httpStatus, String requestId) { }
}
