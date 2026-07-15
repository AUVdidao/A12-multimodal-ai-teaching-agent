package com.auvdidao.a12.generator;

class GeneratorException extends RuntimeException {
    private final String code;
    GeneratorException(String code, String message) { super(message); this.code = code; }
    String code() { return code; }
}
