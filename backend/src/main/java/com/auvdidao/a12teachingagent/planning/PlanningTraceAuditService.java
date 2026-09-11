package com.auvdidao.a12teachingagent.planning;

import com.auvdidao.a12teachingagent.domain.planning.PlanningAgentTrace;
import com.auvdidao.a12teachingagent.domain.planning.repository.PlanningAgentTraceRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Independent audit boundary: trace state must survive proposal transaction rollback. */
@Service
public class PlanningTraceAuditService {
    private final PlanningAgentTraceRepository repository;
    private final ProjectAccessService projectAccessService;

    public PlanningTraceAuditService(PlanningAgentTraceRepository repository,
                                     ProjectAccessService projectAccessService) {
        this.repository = repository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlanningAgentTrace start(Long projectId, String runId, String traceId,
                                    PlanningDtos.ProposalOperation operation, String requestedProvider,
                                    String inputChecksum) {
        projectAccessService.requireAuthenticatedTeacherAccess(projectId);
        PlanningAgentTrace trace = new PlanningAgentTrace();
        trace.setProjectId(projectId);
        trace.setRunId(runId);
        trace.setTraceId(traceId);
        trace.setOperation(operation);
        trace.setStatus(PlanningTraceStatus.RUNNING);
        trace.setRequestedProvider(requestedProvider);
        trace.setInputChecksum(inputChecksum);
        return repository.saveAndFlush(trace);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setCapabilityChecksum(Long projectId, String traceId, String checksum) {
        projectAccessService.requireAuthenticatedTeacherAccess(projectId);
        PlanningAgentTrace trace = repository.findByTraceId(traceId).orElseThrow();
        if (!projectId.equals(trace.getProjectId())) throw new IllegalStateException("trace project mismatch");
        trace.setCapabilityViewChecksum(checksum);
        repository.saveAndFlush(trace);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long projectId, String traceId, PlanningTraceStatus status,
                         String provider, String model, String outputChecksum, String rejectionReason) {
        projectAccessService.requireAuthenticatedTeacherAccess(projectId);
        PlanningAgentTrace trace = repository.findByTraceId(traceId).orElseThrow();
        if (!projectId.equals(trace.getProjectId())) throw new IllegalStateException("trace project mismatch");
        trace.setStatus(status);
        trace.setUsedProvider(provider);
        trace.setUsedModel(model);
        trace.setOutputChecksum(outputChecksum);
        trace.setRejectionReason(rejectionReason);
        trace.setCompletedAt(java.time.LocalDateTime.now());
        repository.saveAndFlush(trace);
    }
}
