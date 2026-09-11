package com.auvdidao.a12teachingagent.agent.model;

import java.net.URI;

/** Controlled remote image reference; raw data URLs and inline base64 are intentionally unsupported in V1. */
public record ModelImageRef(String uri, String mediaType) {
    public static final int MAX_URI_CHARS = 2_048;
    public static final int MAX_MEDIA_TYPE_CHARS = 64;

    public ModelImageRef {
        if (uri == null || uri.isBlank() || uri.length() > MAX_URI_CHARS) {
            throw new IllegalArgumentException("image uri must be nonblank and bounded");
        }
        if (uri.startsWith("data:") || uri.matches("(?i).*?(api[_-]?key|authorization|bearer|password|secret|token).*")) {
            throw new IllegalArgumentException("image uri is not an allowed reference");
        }
        URI parsed;
        try {
            parsed = URI.create(uri);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("image uri is invalid", exception);
        }
        if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                || parsed.getHost() == null) {
            throw new IllegalArgumentException("image uri must be an http(s) URL");
        }
        if (mediaType != null && mediaType.length() > MAX_MEDIA_TYPE_CHARS) {
            throw new IllegalArgumentException("image mediaType is too long");
        }
    }
}


