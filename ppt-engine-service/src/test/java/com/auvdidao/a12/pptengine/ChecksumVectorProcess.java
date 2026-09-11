package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class ChecksumVectorProcess {

    private ChecksumVectorProcess() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var root = mapper.readTree(ChecksumVectorProcess.class.getResourceAsStream(
                "/contracts/v1/examples/valid/preflight-request.json"));
        ContractModels.LockedPptSpecification specification = mapper.treeToValue(
                root.get("specification"), ContractModels.LockedPptSpecification.class);
        System.out.print(new ChecksumService(mapper).compute(specification));
    }
}
