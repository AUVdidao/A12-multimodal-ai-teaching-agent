package com.auvdidao.a12.pptengine.web;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractRejectedException;
import com.auvdidao.a12.pptengine.contract.DiagnosticFactory;
import com.auvdidao.a12.pptengine.service.PreflightService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final PreflightService preflightService;

    public ApiExceptionHandler(PreflightService preflightService) {
        this.preflightService = preflightService;
    }

    @ExceptionHandler(ContractRejectedException.class)
    public ResponseEntity<?> contractRejected(
            ContractRejectedException exception, HttpServletRequest request) {
        if (ExecuteErrorResponseFactory.isExecutePath(request.getRequestURI())) {
            return ResponseEntity.badRequest().body(ExecuteErrorResponseFactory.fromDiagnostic(
                    exception.requestId(), exception.diagnostic(),
                    ExecuteErrorResponseFactory.isExecuteV2Path(request.getRequestURI())
                            ? "2.0.0" : "1.0.0"));
        }
        if (ComposeErrorResponseFactory.isComposePath(request.getRequestURI())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ComposeErrorResponseFactory.fromDiagnostic(
                            exception.requestId(), exception.diagnostic()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(preflightService.invalidRequest(exception.requestId(), exception.diagnostic()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedJson(HttpServletRequest request) {
        ContractModels.Diagnostic diagnostic = DiagnosticFactory.gateError(
                "CONTRACT_INVALID", "contract.invalid", null, null, null, null,
                null, null, Map.of("violationCount", "1"));
        if (ExecuteErrorResponseFactory.isExecutePath(request.getRequestURI())) {
            return ResponseEntity.badRequest().body(ExecuteErrorResponseFactory.fromDiagnostic(
                    "unknown", diagnostic,
                    ExecuteErrorResponseFactory.isExecuteV2Path(request.getRequestURI())
                            ? "2.0.0" : "1.0.0"));
        }
        if (ComposeErrorResponseFactory.isComposePath(request.getRequestURI())) {
            return ResponseEntity.badRequest().body(ComposeErrorResponseFactory.fromDiagnostic("unknown", diagnostic));
        }
        return ResponseEntity.badRequest().body(preflightService.invalidRequest("unknown", diagnostic));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(HttpServletRequest request) {
        ContractModels.Diagnostic diagnostic = DiagnosticFactory.gateError(
                "CONTRACT_INVALID", "contract.invalid", null, null, null, null,
                null, null, Map.of("violationCount", "1"));
        if (ExecuteErrorResponseFactory.isExecutePath(request.getRequestURI())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ExecuteErrorResponseFactory.fromDiagnostic(
                            "unknown", diagnostic,
                            ExecuteErrorResponseFactory.isExecuteV2Path(request.getRequestURI())
                                    ? "2.0.0" : "1.0.0"));
        }
        if (ComposeErrorResponseFactory.isComposePath(request.getRequestURI())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ComposeErrorResponseFactory.fromDiagnostic("unknown", diagnostic));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(preflightService.invalidRequest("unknown", diagnostic));
    }
}
