package com.auvdidao.a12.pptengine.executor;

/** Deterministic rejection of a transform outside the declared contract. */
final class TransformConstraintException extends RuntimeException {

    TransformConstraintException(String message) {
        super(message);
    }
}
