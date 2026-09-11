package com.auvdidao.a12.pptengine.web;

import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class PreflightController {

    private final ContractGate contractGate;
    private final com.auvdidao.a12.pptengine.service.PreflightService preflightService;

    public PreflightController(
            ContractGate contractGate,
            com.auvdidao.a12.pptengine.service.PreflightService preflightService) {
        this.contractGate = contractGate;
        this.preflightService = preflightService;
    }

    @PostMapping(value = "/preflight", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> preflight(@RequestBody JsonNode rawRequest) {
        ContractModels.EnginePreflightRequest request = contractGate.parse(rawRequest);
        com.auvdidao.a12.pptengine.service.PreflightService.PreflightResult result =
                preflightService.execute(request);
        if (result.httpStatus() == 422) {
            return ResponseEntity.unprocessableEntity().body(result.feedback());
        }
        return ResponseEntity.ok(new ContractModels.EnginePreflightResponse(
                com.auvdidao.a12.pptengine.contract.ContractTypes.V1,
                request.requestId(), result.plan(), result.feedback()));
    }
}
