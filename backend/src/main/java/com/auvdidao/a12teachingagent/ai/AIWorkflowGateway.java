package com.auvdidao.a12teachingagent.ai;

import com.auvdidao.a12teachingagent.clarification.ClarificationCheckRequest;
import com.auvdidao.a12teachingagent.clarification.ClarificationResult;

public interface AIWorkflowGateway {

    ClarificationResult checkRequirementClarification(Long projectId, ClarificationCheckRequest request);
}
