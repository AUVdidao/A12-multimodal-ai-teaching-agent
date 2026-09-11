package com.auvdidao.a12teachingagent.lessonforge;

import com.auvdidao.a12teachingagent.ai.connection.ModelConnectionService;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.mission.Mission;
import com.auvdidao.a12teachingagent.domain.mission.MissionConversation;
import com.auvdidao.a12teachingagent.domain.mission.MissionMessage;
import com.auvdidao.a12teachingagent.domain.mission.MissionStatus;
import com.auvdidao.a12teachingagent.domain.mission.repository.MissionContextFileRepository;
import com.auvdidao.a12teachingagent.domain.mission.repository.MissionConversationRepository;
import com.auvdidao.a12teachingagent.domain.mission.repository.MissionMessageRepository;
import com.auvdidao.a12teachingagent.domain.mission.repository.MissionRepository;
import com.auvdidao.a12teachingagent.domain.mission.repository.MissionSubmissionRepository;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static com.auvdidao.a12teachingagent.lessonforge.MissionDtos.MessageRequest;
import static com.auvdidao.a12teachingagent.lessonforge.MissionDtos.ConnectionSelectionRequest;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MissionServiceStateGateTest {
    @Mock MissionRepository missions;
    @Mock MissionConversationRepository conversations;
    @Mock MissionMessageRepository messages;
    @Mock MissionContextFileRepository files;
    @Mock MissionSubmissionRepository submissions;
    @Mock AppUserRepository users;
    @Mock UserRoleAssignmentRepository roles;
    @Mock CurrentUserService current;
    @Mock ModelConnectionService connections;
    @Mock FileStorageService storage;
    @Mock MultipartFile upload;

    private MissionService service;
    private Mission mission;
    private final AuthenticatedUser teacher = new AuthenticatedUser(1L, 100L, "teacher", "Teacher", UserRole.TEACHER);

    @BeforeEach
    void setUp() {
        service = new MissionService(missions, conversations, messages, files, submissions, users, roles, current, connections, storage);
        mission = new Mission();
        mission.setId(7L);
        mission.setAssignedTeacherId(100L);
        mission.setCreatedByLeaderId(200L);
        when(current.requireRole(UserRole.TEACHER)).thenReturn(teacher);
        when(current.requireRole(UserRole.TEACHER, UserRole.LEADER)).thenReturn(teacher);
        when(missions.findById(7L)).thenReturn(Optional.of(mission));
    }

    @ParameterizedTest
    @EnumSource(value = MissionStatus.class, names = {"ASSIGNED", "REJECTED"})
    void messageFileAndSubmissionWritesAreRejectedWithoutSideEffects(MissionStatus status) {
        mission.setStatus(status);

        assertThrows(ConflictException.class, () -> service.sendMessage(7L, new MessageRequest("must wait")));
        assertThrows(ConflictException.class, () -> service.conversation(7L));
        assertThrows(ConflictException.class, () -> service.listFiles(7L));
        assertThrows(ConflictException.class, () -> service.uploadFile(7L, upload, null));
        assertThrows(ConflictException.class, () -> service.downloadContext(7L, 21L));
        assertThrows(ConflictException.class, () -> service.submit(7L, upload, null));
        assertThrows(ConflictException.class, () -> service.selectConnection(7L, new ConnectionSelectionRequest(31L)));
        assertThrows(ConflictException.class, () -> service.downloadSubmission(7L, 41L));

        verifyNoInteractions(conversations, messages, files, submissions, storage, connections);
    }

    @Test
    void acceptedMissionStillPersistsConversationMessage() {
        mission.setStatus(MissionStatus.ACCEPTED);
        MissionConversation conversation = new MissionConversation();
        conversation.setId(11L);
        when(conversations.findByMissionId(7L)).thenReturn(Optional.of(conversation));
        MissionMessage saved = new MissionMessage();
        saved.setId(12L);
        when(messages.save(any(MissionMessage.class))).thenReturn(saved);

        assertNotNull(service.sendMessage(7L, new MessageRequest("accepted work")));
        verify(messages).save(any(MissionMessage.class));
    }
}
