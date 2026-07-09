package com.auvdidao.a12teachingagent.project.dto;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProjectCreateRequest(
        @NotBlank(message = "项目名称不能为空")
        String projectName,
        String courseName,
        String chapterTopic,
        String targetAudience,
        Integer lessonDurationMinutes,
        @NotNull(message = "生成模式不能为空")
        GenerationMode generationMode
) {
}
