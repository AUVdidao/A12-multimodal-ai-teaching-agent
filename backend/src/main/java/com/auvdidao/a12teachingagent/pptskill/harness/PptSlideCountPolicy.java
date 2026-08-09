package com.auvdidao.a12teachingagent.pptskill.harness;

/** Deterministic V1 page-count policy for PPT Harness jobs. */
public final class PptSlideCountPolicy {
    public static final int MIN_SLIDES = 6;
    public static final int MAX_SLIDES = 30;

    public int calculate(
            Integer explicitTargetSlideCount,
            int outlineSectionCount,
            Integer lessonDurationMinutes,
            int teachingGoalCount,
            int outlinePointCount
    ) {
        if (explicitTargetSlideCount != null) {
            return clamp(explicitTargetSlideCount);
        }

        int meaningfulSections = Math.max(0, outlineSectionCount);
        int base = Math.max(MIN_SLIDES, meaningfulSections);
        int duration = lessonDurationMinutes == null || lessonDurationMinutes <= 0
                ? 45
                : lessonDurationMinutes;
        int durationAdjustment = duration <= 20 ? 0 : duration >= 90 ? 4 : 2;
        int complexityAdjustment = Math.min(4, Math.max(0, teachingGoalCount + outlinePointCount - 2) / 4);
        return clamp(Math.max(meaningfulSections, base + durationAdjustment + complexityAdjustment));
    }

    private int clamp(int value) {
        return Math.max(MIN_SLIDES, Math.min(MAX_SLIDES, value));
    }
}
