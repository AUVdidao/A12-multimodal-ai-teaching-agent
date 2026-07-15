package com.auvdidao.a12teachingagent.dialog;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.dialog.dto.DialogDtos.DialogClearResponse;
import com.auvdidao.a12teachingagent.dialog.dto.DialogDtos.DialogMessageRequest;
import com.auvdidao.a12teachingagent.dialog.dto.DialogDtos.DialogMessageResponse;
import com.auvdidao.a12teachingagent.domain.common.DialogRole;
import com.auvdidao.a12teachingagent.domain.dialog.DialogMessage;
import com.auvdidao.a12teachingagent.domain.dialog.repository.DialogMessageRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class DialogService {

    private final DialogMessageRepository dialogMessageRepository;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;

    public DialogService(
            DialogMessageRepository dialogMessageRepository,
            ProjectRepository projectRepository,
            ProjectAccessService projectAccessService
    ) {
        this.dialogMessageRepository = dialogMessageRepository;
        this.projectRepository = projectRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional
    public DialogMessageResponse saveMessage(Long projectId, DialogMessageRequest request) {
        ensureProjectExists(projectId);

        DialogMessage message = new DialogMessage();
        message.setProjectId(projectId);
        message.setSessionId(trimToRequired(request.sessionId(), "sessionId"));
        message.setRole(parseSender(request.sender()));
        message.setContent(trimToRequired(request.content(), "content"));
        message.setRoundNo(request.roundNo());

        return toResponse(dialogMessageRepository.save(message));
    }

    @Transactional(readOnly = true)
    public List<DialogMessageResponse> listProjectMessages(Long projectId) {
        ensureProjectExists(projectId);

        return dialogMessageRepository.findByProjectIdOrderByCreatedAtAscIdAsc(projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DialogMessageResponse> listSessionMessages(String sessionId) {
        String normalizedSessionId = trimToRequired(sessionId, "sessionId");
        List<DialogMessage> messages = dialogMessageRepository
                .findBySessionIdOrderByCreatedAtAscIdAsc(normalizedSessionId);
        messages.stream()
                .map(DialogMessage::getProjectId)
                .distinct()
                .forEach(projectAccessService::requireAccess);
        return messages.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public DialogClearResponse clearProjectMessages(Long projectId) {
        ensureProjectExists(projectId);
        return new DialogClearResponse(projectId, dialogMessageRepository.deleteByProjectId(projectId));
    }

    private void ensureProjectExists(Long projectId) {
        if (projectId == null || !projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
        projectAccessService.requireAccess(projectId);
    }

    private DialogRole parseSender(String sender) {
        String normalized = trimToRequired(sender, "sender").toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "TEACHER" -> DialogRole.TEACHER;
            case "AI", "ASSISTANT" -> DialogRole.ASSISTANT;
            case "SYSTEM" -> DialogRole.SYSTEM;
            default -> throw new BadRequestException("Unsupported dialogue sender: " + sender);
        };
    }

    private DialogMessageResponse toResponse(DialogMessage message) {
        return new DialogMessageResponse(
                message.getId(),
                message.getProjectId(),
                message.getSessionId(),
                toSender(message.getRole()),
                message.getContent(),
                message.getRoundNo(),
                message.getCreatedAt()
        );
    }

    private String toSender(DialogRole role) {
        if (role == DialogRole.ASSISTANT) {
            return "AI";
        }
        return role.name();
    }

    private String trimToRequired(String value, String fieldName) {
        if (value == null) {
            throw new BadRequestException(fieldName + " is required");
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new BadRequestException(fieldName + " is required");
        }
        return trimmed;
    }
}
