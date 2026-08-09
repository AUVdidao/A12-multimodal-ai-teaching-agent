package com.auvdidao.a12teachingagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeContractsTest {
    private static final String RUN_ID = "11111111-1111-1111-1111-111111111111";
    private static final String TRACE_ID = "trace-001";

    @Test
    void createsValidBoundedContext() {
        AgentContext context = validContext(List.of(evidence(1)), AgentConstraints.defaults());

        assertEquals(1L, context.projectId());
        assertEquals(AgentName.COURSE_OUTLINE, context.agentName());
        assertEquals(AgentStage.COURSE_OUTLINE, context.stage());
        assertEquals(1, context.attempt());
        assertEquals(RetrievalMode.DENSE, context.evidence().get(0).retrievalMode());
    }

    @Test
    void rejectsInvalidProjectAndAttempt() {
        assertThrows(IllegalArgumentException.class, () -> validContext(List.of(), AgentConstraints.defaults(), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> validContext(List.of(), AgentConstraints.defaults(), 1, 0));
    }

    @Test
    void rejectsEvidenceBeyondContextMaximum() {
        List<GroundedEvidence> evidence = IntStream.rangeClosed(1, AgentContext.MAX_EVIDENCE_ITEMS + 1)
                .mapToObj(this::evidence)
                .toList();

        assertThrows(IllegalArgumentException.class, () -> validContext(evidence, AgentConstraints.defaults()));
    }

    @Test
    void rejectsExcerptBeyondLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundedEvidence(1, 1, "lesson.pdf", "Title", "x".repeat(4_097), 0.9, RetrievalMode.DENSE)
        );
    }

    @Test
    void rejectsNonFiniteEvidenceScore() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundedEvidence(1, 1, "lesson.pdf", "Title", "excerpt", Double.NaN, RetrievalMode.DENSE)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundedEvidence(1, 1, "lesson.pdf", "Title", "excerpt", Double.POSITIVE_INFINITY, RetrievalMode.DENSE)
        );
    }

    @Test
    void enforcesConstraintAndMetadataBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentConstraints(null, List.of(), null, 51, 4096, 300_000L, 20)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentConstraints(null, List.of(), null, 50, 4096, 300_000L, 101)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new RuntimeMetadata(
                        "mock",
                        "model",
                        IntStream.range(0, 21).mapToObj(index -> "warning-" + index).toList(),
                        List.of(),
                        List.of()
                )
        );
    }

    @Test
    void copiesMutableInputsAndExposesImmutableLists() {
        List<GroundedEvidence> mutableEvidence = new ArrayList<>(List.of(evidence(1)));
        List<OutputRef> mutableOutputs = new ArrayList<>(List.of(new OutputRef("outline", "output-1", "sha256:abc")));
        AgentContext context = validContext(mutableEvidence, AgentConstraints.defaults(), 1, 1, mutableOutputs);

        mutableEvidence.add(evidence(2));
        mutableOutputs.clear();

        assertEquals(1, context.evidence().size());
        assertEquals(1, context.previousOutputs().size());
        assertThrows(UnsupportedOperationException.class, () -> context.evidence().add(evidence(3)));
        assertThrows(UnsupportedOperationException.class, () -> context.previousOutputs().clear());
    }

    @Test
    void enforcesToolResultSuccessAndFailureContracts() {
        ToolResult<String> success = ToolResult.success("tool-1", TRACE_ID, "data", 25L);
        assertTrue(success.success());
        assertFalse(success.retryable());
        assertEquals(null, success.errorKind());

        ToolResult<Void> failure = ToolResult.failure("tool-2", TRACE_ID, AgentFailureKind.TOOL_TIMEOUT, 100L);
        assertFalse(failure.success());
        assertTrue(failure.retryable());
        assertEquals(AgentFailureKind.TOOL_TIMEOUT, failure.errorKind());

        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolResult<>("tool-3", TRACE_ID, true, "data", AgentFailureKind.INTERNAL_FAILURE, false, 0, List.of(), List.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolResult<>("tool-4", TRACE_ID, false, null, null, false, 0, List.of(), List.of())
        );
    }

    @Test
    void exposesFailureRetryPolicyWithoutImplementingRetryLoop() {
        assertTrue(AgentFailureKind.TOOL_UNAVAILABLE.retryableDefault());
        assertTrue(AgentFailureKind.MODEL_INVALID_OUTPUT.retryableDefault());
        assertFalse(AgentFailureKind.RETRIEVAL_NOT_READY.retryableDefault());
        assertFalse(AgentFailureKind.HUMAN_REJECTED.retryableDefault());
        assertFalse(AgentFailureKind.INTERNAL_FAILURE.retryableDefault());
    }

    @Test
    void keepsAgentRunStatusConsistent() {
        AgentRunSnapshot waitingForHuman = new AgentRunSnapshot(
                RUN_ID,
                TRACE_ID,
                null,
                1,
                AgentName.REQUIREMENT_CLARIFICATION,
                AgentStage.REQUIREMENT_CLARIFICATION,
                AgentRunStatus.WAITING_FOR_HUMAN,
                1,
                null,
                null,
                Instant.parse("2026-08-09T00:00:00Z"),
                null,
                null,
                null
        );
        AgentRunSnapshot succeeded = new AgentRunSnapshot(
                RUN_ID,
                TRACE_ID,
                null,
                1,
                AgentName.REQUIREMENT_CLARIFICATION,
                AgentStage.REQUIREMENT_CLARIFICATION,
                AgentRunStatus.SUCCEEDED,
                1,
                "sha256:in",
                "sha256:out",
                Instant.parse("2026-08-09T00:00:00Z"),
                Instant.parse("2026-08-09T00:00:01Z"),
                null,
                null
        );

        assertEquals(AgentRunStatus.WAITING_FOR_HUMAN, waitingForHuman.status());
        assertEquals(Instant.parse("2026-08-09T00:00:01Z"), succeeded.endedAt());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRunSnapshot(
                        RUN_ID, TRACE_ID, null, 1, AgentName.LESSON_PLAN, AgentStage.LESSON_PLAN,
                        AgentRunStatus.FAILED, 1, null, null, null, null, null, "safe failure"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRunSnapshot(
                        RUN_ID, TRACE_ID, null, 1, AgentName.LESSON_PLAN, AgentStage.LESSON_PLAN,
                        AgentRunStatus.SUCCEEDED, 1, null, null, null, null, null, null
                )
        );
    }

    @Test
    void rejectsSecretLikeFieldNamesInContractRecords() {
        List<String> forbiddenFragments = List.of("key", "secret", "token", "password", "credential");
        for (Class<?> type : List.of(AgentContext.class, AgentConstraints.class, RuntimeMetadata.class)) {
            for (var field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String fieldName = field.getName().toLowerCase();
                assertTrue(
                        forbiddenFragments.stream().noneMatch(fieldName::contains),
                        () -> type.getSimpleName() + " contains secret-like field " + field.getName()
                );
            }
        }
    }

    @Test
    void validatesBusinessReferences() {
        BusinessRef logicalRef = new BusinessRef(BusinessRefType.TEACHING_INTENT, "42", null, null);
        BusinessRef versionRef = new BusinessRef(BusinessRefType.ARTIFACT_VERSION, "43", "2", "sha256:abc");

        assertEquals("42", logicalRef.id());
        assertEquals("sha256:abc", versionRef.hash());
        assertThrows(IllegalArgumentException.class, () -> new BusinessRef(BusinessRefType.ARTIFACT_VERSION, "43", "2", null));
        assertThrows(IllegalArgumentException.class, () -> new BusinessRef(BusinessRefType.ARTIFACT, " ", null, null));
    }

    @Test
    void roundTripsContextThroughJackson() throws Exception {
        AgentContext original = validContext(List.of(evidence(1)), AgentConstraints.defaults());
        ObjectMapper objectMapper = new ObjectMapper();

        AgentContext restored = objectMapper.readValue(objectMapper.writeValueAsBytes(original), AgentContext.class);

        assertEquals(original, restored);
    }

    private AgentContext validContext(List<GroundedEvidence> evidence, AgentConstraints constraints) {
        return validContext(evidence, constraints, 1, 1, List.of());
    }

    private AgentContext validContext(
            List<GroundedEvidence> evidence,
            AgentConstraints constraints,
            long projectId,
            int attempt
    ) {
        return validContext(evidence, constraints, projectId, attempt, List.of());
    }

    private AgentContext validContext(
            List<GroundedEvidence> evidence,
            AgentConstraints constraints,
            long projectId,
            int attempt,
            List<OutputRef> previousOutputs
    ) {
        return new AgentContext(
                TRACE_ID,
                RUN_ID,
                null,
                projectId,
                "teacher-1",
                "TEACHER",
                AgentName.COURSE_OUTLINE,
                AgentStage.COURSE_OUTLINE,
                attempt,
                new BusinessRef(BusinessRefType.REQUIREMENT_SUMMARY, "summary-1", null, null),
                null,
                null,
                evidence,
                previousOutputs,
                constraints,
                new RuntimeMetadata("mock", "test-model", List.of("bounded"), List.of("tool-1"), List.of("1"))
        );
    }

    private GroundedEvidence evidence(long id) {
        return new GroundedEvidence(id, 1, "lesson.pdf", "Lesson", "bounded excerpt", 0.9, RetrievalMode.DENSE);
    }
}
