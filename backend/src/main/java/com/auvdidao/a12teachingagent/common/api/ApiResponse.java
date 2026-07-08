package com.auvdidao.a12teachingagent.common.api;

import java.time.Instant;

public record ApiResponse<T>(
        int code,
        String message,
        T data,
        Instant timestamp
) {

    private static final int SUCCESS_CODE = 0;
    private static final int FAILURE_CODE = 500;
    private static final String SUCCESS_MESSAGE = "success";

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, null, Instant.now());
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, Instant.now());
    }

    public static ApiResponse<Void> failure(String message) {
        return failure(FAILURE_CODE, message);
    }

    public static ApiResponse<Void> failure(int code, String message) {
        return new ApiResponse<>(code, message, null, Instant.now());
    }
}
