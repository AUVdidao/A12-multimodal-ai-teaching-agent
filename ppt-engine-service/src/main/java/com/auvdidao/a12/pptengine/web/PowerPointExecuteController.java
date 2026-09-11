package com.auvdidao.a12.pptengine.web;

import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.executor.ExecutorModels;
import com.auvdidao.a12.pptengine.executor.SamePackagePowerPointExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.JsonNode;


@RestController
@RequestMapping("/internal")
public class PowerPointExecuteController {

    private final ContractGate contractGate;
    private final ChecksumService checksumService;
    private final SamePackagePowerPointExecutor executor;

    public PowerPointExecuteController(ContractGate contractGate, ChecksumService checksumService,
                                       SamePackagePowerPointExecutor executor) {
        this.contractGate = contractGate;
        this.checksumService = checksumService;
        this.executor = executor;
    }

    @PostMapping(value = "/v1/execute", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ExecutorModels.ExecuteResponse> execute(@RequestBody JsonNode rawRequest) {
        return executeParsed(contractGate.parseExecute(rawRequest));
    }

    @PostMapping(value = "/v2/execute", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ExecutorModels.ExecuteResponse> executeV2(@RequestBody JsonNode rawRequest) {
        return executeParsed(contractGate.parseExecuteV2(rawRequest));
    }

    private ResponseEntity<ExecutorModels.ExecuteResponse> executeParsed(ExecutorModels.ExecuteRequest request) {
        SamePackagePowerPointExecutor.ExecutionResult result = executor.execute(request);
        var artifact = result.artifact();
        ExecutorModels.ExecuteResponse response = new ExecutorModels.ExecuteResponse(
                request.contractVersion(),
                request.requestId(),
                request.generationJob().executionAttemptId(),
                request.generationJob().engineBuildVersion(),
                result.status(),
                request.specification().checksum(),
                checksumService.computeProfile(request.templateProfile()),
                request.approvedAssetManifest().manifestChecksum(),
                artifact == null ? java.util.List.of() : java.util.List.of(new ExecutorModels.ArtifactReceipt(
                        request.generationJob().executionAttemptId() + "-pptx",
                        "PPTX",
                        request.generationJob().executionAttemptId() + "/generation-result.pptx",
                        artifact.sha256(),
                        artifact.size(),
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation")),
                result.diagnostics(),
                result.buildValidation(),
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        return result.status() == com.auvdidao.a12.pptengine.contract.ContractTypes.GenerationJobStatus.FAILED
                ? ResponseEntity.unprocessableEntity().body(response)
                : ResponseEntity.ok(response);
    }
}
