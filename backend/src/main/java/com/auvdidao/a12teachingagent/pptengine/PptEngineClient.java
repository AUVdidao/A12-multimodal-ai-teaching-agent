package com.auvdidao.a12teachingagent.pptengine;

import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ExecutionResponse;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.GenerationRequest;

public interface PptEngineClient {
    ExecutionResponse execute(GenerationRequest request);
}
