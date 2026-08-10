package com.auvdidao.a12teachingagent.agent.model;

public interface ModelGateway {
    ModelResult complete(ModelExecutionContext context, ModelRequest request);

    <T> StructuredModelResult<T> completeStructured(
            ModelExecutionContext context,
            ModelRequest request,
            StructuredOutputContract<T> contract
    );

    MultimodalModelResult completeMultimodal(ModelExecutionContext context, ModelRequest request);
}
