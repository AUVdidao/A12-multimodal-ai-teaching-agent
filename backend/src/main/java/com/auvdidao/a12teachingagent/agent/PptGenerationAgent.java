package com.auvdidao.a12teachingagent.agent;

import java.util.List;

/** Contract for producing a validated PPT plan; execution belongs to PPT Harness. */
public interface PptGenerationAgent {
    PptPlan plan(CourseOutlineAgent.TeachingContext context, CourseOutlineAgent.OutlinePlan outline);

    record PptPlan(String templateId, List<Slide> slides) {
        public PptPlan {
            slides = slides == null ? List.of() : List.copyOf(slides);
        }
    }

    record Slide(String layoutId, String title, String purpose, List<String> content) {
        public Slide {
            content = content == null ? List.of() : List.copyOf(content);
        }
    }
}
