package com.auvdidao.a12teachingagent.agent;

import java.util.List;

/** Contract for requirement clarification. Questions are field-bound, never positional. */
public interface RequirementClarificationAgent {
    ClarificationPlan clarify(ClarificationContext context);

    record ClarificationContext(Long projectId, String rawRequirement, List<String> knownFields, List<String> missingFields) {
        public ClarificationContext {
            knownFields = knownFields == null ? List.of() : List.copyOf(knownFields);
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }
    }

    record ClarificationPlan(List<Question> questions, String nextAction) {
        public ClarificationPlan {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    record Question(String targetField, String question) {
    }
}
