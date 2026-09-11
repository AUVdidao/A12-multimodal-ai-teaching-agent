package com.auvdidao.a12teachingagent.pptengine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "a12.ppt-engine.mode", havingValue = "unavailable", matchIfMissing = true)
final class UnavailablePptEngineClient implements PptEngineClient {
    @Override
    public PptEngineContracts.ExecutionResponse execute(PptEngineContracts.GenerationRequest request) {
        throw new PptEngineException("PPT_ENGINE_NOT_CONFIGURED", 503,
                "The new PPT Engine is not configured; generation is fail-closed");
    }
}
