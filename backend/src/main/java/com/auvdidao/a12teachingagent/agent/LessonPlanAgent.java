package com.auvdidao.a12teachingagent.agent;

import java.util.List;

/** Contract for producing a classroom-ready lesson plan from an approved outline. */
public interface LessonPlanAgent {
    LessonPlanDraft draft(CourseOutlineAgent.TeachingContext context, CourseOutlineAgent.OutlinePlan outline);

    record LessonPlanDraft(String objective, List<LessonStep> steps, List<String> assessment, List<String> homework) {
        public LessonPlanDraft {
            steps = steps == null ? List.of() : List.copyOf(steps);
            assessment = assessment == null ? List.of() : List.copyOf(assessment);
            homework = homework == null ? List.of() : List.copyOf(homework);
        }
    }

    record LessonStep(String phase, String teacherAction, String learnerAction, int minutes) {
    }
}
