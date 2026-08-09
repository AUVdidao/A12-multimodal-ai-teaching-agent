package com.auvdidao.a12teachingagent.pptskill.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PptSlideCountPolicyTest {
    private final PptSlideCountPolicy policy = new PptSlideCountPolicy();

    @Test
    void explicitTargetSlideCountHasPriorityAndIsClamped() {
        assertEquals(6, policy.calculate(2, 12, 90, 10, 20));
        assertEquals(30, policy.calculate(99, 1, 20, 0, 0));
    }

    @Test
    void shortAndNormalLessonsUseDifferentDeterministicPolicies() {
        int compact = policy.calculate(null, 2, 20, 1, 1);
        int normal = policy.calculate(null, 2, 45, 1, 1);

        assertEquals(6, compact);
        assertEquals(8, normal);
    }

    @Test
    void confirmedOutlineSectionsAreNeverDroppedByPageCountPolicy() {
        assertEquals(10, policy.calculate(null, 10, 20, 0, 0));
    }
}
