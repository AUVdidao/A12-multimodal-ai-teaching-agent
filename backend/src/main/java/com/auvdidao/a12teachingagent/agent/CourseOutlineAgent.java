package com.auvdidao.a12teachingagent.agent;

import java.util.List;

/** Contract for turning confirmed intent and retrieved evidence into a course outline. */
public interface CourseOutlineAgent {
    OutlinePlan plan(TeachingContext context);

    record TeachingContext(Long projectId, String subject, String topic, String audience, List<String> evidence) {
        public TeachingContext {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    record OutlinePlan(List<Section> sections) {
        public OutlinePlan {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    record Section(String title, String objective, List<String> keyPoints) {
        public Section {
            keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        }
    }
}
