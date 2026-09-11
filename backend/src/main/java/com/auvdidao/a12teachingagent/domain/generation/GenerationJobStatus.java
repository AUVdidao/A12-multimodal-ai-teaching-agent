package com.auvdidao.a12teachingagent.domain.generation;

public enum GenerationJobStatus {
    REQUESTED,
    SUCCEEDED,
    PARTIAL,
    SUCCEEDED_WITH_FEEDBACK,
    FAILED,
    TRANSPORT_FAILED,
    CONTRACT_FAILED
}
