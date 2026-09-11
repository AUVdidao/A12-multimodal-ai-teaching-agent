package com.auvdidao.a12.pptengine.web;

import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.service.ComposePlanService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.COMPOSE_CONTRACT_V2;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.COMPOSITION_FEEDBACK_V2;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.COMPOSITION_PLAN_V2;

@RestController
@RequestMapping("/internal/v1")
public class ComposePlanController {

    private final ContractGate contractGate;
    private final ComposePlanService composePlanService;

    public ComposePlanController(ContractGate contractGate, ComposePlanService composePlanService) {
        this.contractGate = contractGate;
        this.composePlanService = composePlanService;
    }

    @PostMapping(value = "/compose-plan", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> composePlan(@RequestBody JsonNode rawRequest) {
        CompositionModels.EngineComposePlanRequest request = contractGate.parseCompose(rawRequest);
        ComposePlanService.ComposeResult result = composePlanService.execute(request);
        if (result.httpStatus() == 422) {
            return ResponseEntity.unprocessableEntity().body(
                    ComposeErrorResponseFactory.fromFeedback(result.feedback()));
        }
        return ResponseEntity.ok(new CompositionModels.EngineComposePlanResponse(
                COMPOSE_CONTRACT_V2,
                COMPOSITION_PLAN_V2,
                COMPOSITION_FEEDBACK_V2,
                request.requestId(),
                result.plan(),
                result.feedback()));
    }
}
