package com.auvdidao.a12teachingagent.agent.model;

import java.util.List;

public record ModelMessage(
        Role role,
        String content,
        List<ModelImageRef> images
) {
    public static final int MAX_CONTENT_CHARS = 60_000;
    public static final int MAX_IMAGES = 4;

    public ModelMessage {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (content == null) {
            content = "";
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("content is too long");
        }
        images = images == null ? List.of() : List.copyOf(images);
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("a message may contain at most " + MAX_IMAGES + " images");
        }
        if (content.isBlank() && images.isEmpty()) {
            throw new IllegalArgumentException("message must contain text or an image");
        }
    }

    public ModelMessage(Role role, String content) {
        this(role, content, List.of());
    }

    public static ModelMessage system(String content) {
        return new ModelMessage(Role.SYSTEM, content);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage(Role.USER, content);
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage(Role.ASSISTANT, content);
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }
}
