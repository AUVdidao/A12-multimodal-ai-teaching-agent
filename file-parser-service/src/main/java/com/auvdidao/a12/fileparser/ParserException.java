package com.auvdidao.a12.fileparser;

public final class ParserException extends RuntimeException {

    private final String code;

    public ParserException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
