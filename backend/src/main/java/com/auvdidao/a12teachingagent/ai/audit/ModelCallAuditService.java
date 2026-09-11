package com.auvdidao.a12teachingagent.ai.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ModelCallAuditService {
    private final ModelCallAuditRepository repository;
    public ModelCallAuditService(ModelCallAuditRepository repository) { this.repository = repository; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String requestId, Long actorUserId, Long connectionId, String protocol, String host,
                       String modelId, String purpose, String credentialSource, int httpStatus, long latencyMs) {
        ModelCallAuditEntity audit = new ModelCallAuditEntity();
        audit.setRequestId(requestId);
        audit.setActorUserId(actorUserId);
        audit.setModelConnectionId(connectionId);
        audit.setProtocol(protocol);
        audit.setBaseUrlHost(host);
        audit.setModelId(modelId);
        audit.setPurpose(purpose);
        audit.setCredentialSource(credentialSource);
        audit.setHttpStatus(Math.max(0, httpStatus));
        audit.setLatencyMs(Math.max(0, latencyMs));
        audit.setOccurredAt(LocalDateTime.now());
        repository.save(audit);
    }
}
