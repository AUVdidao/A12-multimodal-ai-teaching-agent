package com.auvdidao.a12.pptengine.contract;

public class ContractRejectedException extends RuntimeException {

    private final String requestId;
    private final ContractModels.Diagnostic diagnostic;

    public ContractRejectedException(String requestId, ContractModels.Diagnostic diagnostic) {
        this.requestId = requestId;
        this.diagnostic = diagnostic;
    }

    public String requestId() {
        return requestId;
    }

    public ContractModels.Diagnostic diagnostic() {
        return diagnostic;
    }
}
