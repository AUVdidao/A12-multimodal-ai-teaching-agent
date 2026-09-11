package com.auvdidao.a12.pptengine.web;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import com.auvdidao.a12.pptengine.contract.ResourceLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_ENDPOINTS = Set.of(
            "/internal/v1/preflight",
            "/internal/v1/compose-plan",
            "/internal/v1/execute",
            "/internal/v2/execute");

    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!LIMITED_ENDPOINTS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > ResourceLimits.MAX_REQUEST_BYTES) {
            writeTooLarge(request, response);
            return;
        }

        try {
            // A bounded pre-read makes both known-length and chunked overflow a
            // filter decision before Spring's JSON parser can translate it to 400.
            byte[] body = readBoundedBody(request);
            filterChain.doFilter(new CachedBodyRequest(request, body), response);
        } catch (RequestTooLargeException exception) {
            if (!response.isCommitted()) {
                writeTooLarge(request, response);
            }
        }
    }

    private byte[] readBoundedBody(HttpServletRequest request) throws IOException {
        long declaredLength = request.getContentLengthLong();
        int initialCapacity = declaredLength >= 0
                ? (int) Math.min(declaredLength, ResourceLimits.MAX_REQUEST_BYTES)
                : 8_192;
        ByteArrayOutputStream body = new ByteArrayOutputStream(initialCapacity);
        ServletInputStream input = request.getInputStream();
        byte[] buffer = new byte[8_192];
        long total = 0;

        while (true) {
            int allowedRead = (int) Math.min(
                    buffer.length, ResourceLimits.MAX_REQUEST_BYTES - total + 1);
            int read = input.read(buffer, 0, allowedRead);
            if (read == -1) {
                return body.toByteArray();
            }
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > ResourceLimits.MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException();
            }
            body.write(buffer, 0, read);
        }
    }

    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ContractModels.Diagnostic diagnostic = DiagnosticFactory.gateError(
                "CONTRACT_INVALID", "request.tooLarge", null, null, null, null,
                null, null, Map.of("limitBytes", Long.toString(ResourceLimits.MAX_REQUEST_BYTES)));
        if (ExecuteErrorResponseFactory.isExecutePath(request.getRequestURI())) {
            objectMapper.writeValue(response.getWriter(),
                    ExecuteErrorResponseFactory.fromDiagnostic("unknown", diagnostic));
        } else if (ComposeErrorResponseFactory.isComposePath(request.getRequestURI())) {
            objectMapper.writeValue(response.getWriter(),
                    ComposeErrorResponseFactory.fromDiagnostic("unknown", diagnostic));
        } else {
            objectMapper.writeValue(response.getWriter(), new ContractModels.GenerationFeedback(
                    com.auvdidao.a12.pptengine.contract.ContractTypes.V1,
                    "unknown", null, null, null, null, null,
                    com.auvdidao.a12.pptengine.contract.ContractTypes.FeedbackOutcome.REJECTED,
                    List.of(diagnostic), OffsetDateTime.now(ZoneOffset.UTC)));
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8.name() : getCharacterEncoding();
            try {
                return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
            } catch (java.io.UnsupportedEncodingException exception) {
                throw new IllegalStateException("Unsupported request encoding", exception);
            }
        }
    }

    private static final class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        private CachedServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("ReadListener is required");
            }
            try {
                if (!isFinished()) {
                    listener.onDataAvailable();
                }
                if (isFinished()) {
                    listener.onAllDataRead();
                }
            } catch (IOException exception) {
                listener.onError(exception);
            }
        }
    }

    private static final class RequestTooLargeException extends IOException {
    }
}
