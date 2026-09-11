package com.auvdidao.a12teachingagent.lessonforge;

import com.auvdidao.a12teachingagent.domain.mission.MissionFileKind;
import com.auvdidao.a12teachingagent.domain.mission.MissionStatus;
import com.auvdidao.a12teachingagent.domain.mission.MissionSubmissionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class MissionDtos {
    private MissionDtos() {}
    public record CreateRequest(@NotBlank @Size(max = 200) String title, @NotBlank String description,
                                @NotNull @Positive Long assignedTeacherId, LocalDateTime deadline) {}
    public record DecisionRequest(@Size(max = 5000) String reason) {}
    public record MessageRequest(@NotBlank @Size(max = 50000) String content) {}
    public record ConnectionSelectionRequest(@NotNull @Positive Long connectionId) {}
    public record ReviewRequest(@NotNull @Positive Integer rating, @Size(max = 10000) String note) {}
    public record TeacherView(Long id, String username, String displayName) {}
    public record MessageView(Long id, String role, String content, LocalDateTime createdAt) {}
    public record ContextFileView(Long id, String name, String contentType, String extension, long size,
                                  String sha256, MissionFileKind kind, LocalDateTime uploadedAt, String downloadUrl) {}
    public record SubmissionView(Long id, String fileName, String contentType, long size, String sha256,
                                 MissionSubmissionStatus status, Long reviewerId, Integer rating, String reviewNote,
                                 LocalDateTime submittedAt, LocalDateTime reviewedAt, String downloadUrl) {}
    public record MissionView(Long id, String title, String description, Long assignedTeacherId, Long createdByLeaderId,
                              LocalDateTime deadline, MissionStatus status, String rejectionReason,
                              Long selectedModelConnectionId, Long conversationId, List<MessageView> messages,
                              List<ContextFileView> contextFiles, List<SubmissionView> submissions, String teacherName,
                              String leaderName) {}
}
