package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.domain.lessonforge.LessonForgeMaterialBinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TemplateProcessingServiceBindingTest {
    private static final String SOURCE_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SOURCE_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    void selectsOnlyTheOwnerMissionFileBoundToTheCurrentSource() {
        LessonForgeMaterialBinding unrelated = binding(11L, SOURCE_B);
        LessonForgeMaterialBinding matching = binding(22L, SOURCE_A);

        assertEquals(22L, TemplateProcessingService.resolveUniqueMissionFileId(
                List.of(unrelated, matching), 7L, SOURCE_A));
    }

    @Test
    void failsClosedWhenTheCurrentSourceHasConflictingMissionFiles() {
        assertNull(TemplateProcessingService.resolveUniqueMissionFileId(
                List.of(binding(11L, SOURCE_A), binding(22L, SOURCE_A)), 7L, SOURCE_A));
    }

    private static LessonForgeMaterialBinding binding(Long missionFileId, String sourceSha256) {
        LessonForgeMaterialBinding binding = new LessonForgeMaterialBinding();
        binding.setMissionId(7L);
        binding.setMissionFileId(missionFileId);
        binding.setSourceSha256(sourceSha256);
        binding.setBindingStatus("BOUND");
        return binding;
    }
}
