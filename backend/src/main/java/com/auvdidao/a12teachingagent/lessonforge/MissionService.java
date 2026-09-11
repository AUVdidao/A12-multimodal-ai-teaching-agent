package com.auvdidao.a12teachingagent.lessonforge;

import com.auvdidao.a12teachingagent.ai.connection.ModelConnectionService;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.mission.*;
import com.auvdidao.a12teachingagent.domain.mission.repository.*;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDateTime;
import java.util.*;

import static com.auvdidao.a12teachingagent.lessonforge.MissionDtos.*;

@Service
public class MissionService {
    private static final Set<String> CONTEXT_EXTENSIONS = Set.of("pdf", "doc", "docx", "ppt", "pptx", "png", "jpg", "jpeg");
    private final MissionRepository missions;
    private final MissionConversationRepository conversations;
    private final MissionMessageRepository messages;
    private final MissionContextFileRepository files;
    private final MissionSubmissionRepository submissions;
    private final AppUserRepository users;
    private final UserRoleAssignmentRepository roles;
    private final CurrentUserService current;
    private final ModelConnectionService connections;
    private final FileStorageService storage;

    public MissionService(MissionRepository missions, MissionConversationRepository conversations, MissionMessageRepository messages,
                          MissionContextFileRepository files, MissionSubmissionRepository submissions, AppUserRepository users,
                          UserRoleAssignmentRepository roles, CurrentUserService current, ModelConnectionService connections,
                          FileStorageService storage) {
        this.missions = missions; this.conversations = conversations; this.messages = messages; this.files = files;
        this.submissions = submissions; this.users = users; this.roles = roles; this.current = current;
        this.connections = connections; this.storage = storage;
    }

    @Transactional(readOnly = true)
    public List<MissionView> list() {
        AuthenticatedUser user = current.requireRole(UserRole.TEACHER, UserRole.LEADER);
        List<Mission> rows = user.activeRole() == UserRole.TEACHER
                ? missions.findByAssignedTeacherIdOrderByUpdatedAtDesc(user.userId())
                : missions.findByCreatedByLeaderIdOrderByUpdatedAtDesc(user.userId());
        return rows.stream().map(row -> view(row, user.activeRole() == UserRole.TEACHER)).toList();
    }

    @Transactional(readOnly = true)
    public MissionView get(Long id) {
        AuthenticatedUser user = current.requireRole(UserRole.TEACHER, UserRole.LEADER);
        Mission mission = accessible(id, user);
        return view(mission, user.activeRole() == UserRole.TEACHER);
    }

    @Transactional
    public MissionView create(CreateRequest request) {
        AuthenticatedUser leader = current.requireRole(UserRole.LEADER);
        if (!roles.existsByUserIdAndRole(request.assignedTeacherId(), UserRole.TEACHER)) {
            throw new BadRequestException("ASSIGNED_TEACHER_INVALID");
        }
        Mission mission = new Mission(); mission.setTitle(request.title().trim()); mission.setDescription(request.description().trim());
        mission.setAssignedTeacherId(request.assignedTeacherId()); mission.setCreatedByLeaderId(leader.userId());
        mission.setDeadline(request.deadline()); mission.setStatus(MissionStatus.ASSIGNED);
        mission = missions.save(mission); ensureConversation(mission.getId());
        return view(mission, false);
    }

    @Transactional(readOnly = true)
    public List<TeacherView> teachers() {
        current.requireRole(UserRole.LEADER);
        return roles.findByRoleOrderByUserIdAsc(UserRole.TEACHER).stream()
                .map(role -> users.findById(role.getUserId()).map(u -> new TeacherView(u.getId(), u.getUsername(), u.getDisplayName())).orElse(null))
                .filter(Objects::nonNull).toList();
    }

    @Transactional
    public MissionView accept(Long id) {
        AuthenticatedUser teacher = current.requireRole(UserRole.TEACHER);
        Mission mission = accessible(id, teacher);
        if (mission.getStatus() != MissionStatus.ASSIGNED) throw new ConflictException("MISSION_NOT_ASSIGNED");
        mission.setStatus(MissionStatus.ACCEPTED); return view(missions.save(mission), true);
    }

    @Transactional
    public MissionView reject(Long id, DecisionRequest request) {
        AuthenticatedUser teacher = current.requireRole(UserRole.TEACHER);
        Mission mission = accessible(id, teacher);
        if (mission.getStatus() != MissionStatus.ASSIGNED) throw new ConflictException("MISSION_NOT_ASSIGNED");
        if (request == null || !StringUtils.hasText(request.reason())) throw new BadRequestException("REJECTION_REASON_REQUIRED");
        mission.setStatus(MissionStatus.REJECTED); mission.setRejectionReason(request.reason().trim()); mission.setRejectedAt(LocalDateTime.now());
        return view(missions.save(mission), true);
    }

    @Transactional
    public MissionView selectConnection(Long id, ConnectionSelectionRequest request) {
        AuthenticatedUser teacher = current.requireRole(UserRole.TEACHER);
        Mission mission = requireWorkspace(accessible(id, teacher));
        connections.resolveForExecution(teacher.userId(), request.connectionId());
        mission.setSelectedModelConnectionId(request.connectionId());
        return view(missions.save(mission), true);
    }

    @Transactional(readOnly = true)
    public List<MessageView> conversation(Long id) { requireWorkspace(accessibleTeacher(id)); return messages.findByMissionIdOrderByCreatedAtAscIdAsc(id).stream().map(this::messageView).toList(); }

    @Transactional
    public MessageView sendMessage(Long id, MessageRequest request) {
        AuthenticatedUser teacher = current.requireRole(UserRole.TEACHER); Mission mission = requireWorkspace(accessible(id, teacher));
        if (request == null || !StringUtils.hasText(request.content())) throw new BadRequestException("MESSAGE_REQUIRED");
        MissionConversation conversation = ensureConversation(mission.getId()); MissionMessage message = new MissionMessage();
        message.setMissionId(id); message.setConversationId(conversation.getId()); message.setSenderId(teacher.userId());
        message.setSenderRole(UserRole.TEACHER.name()); message.setContent(request.content().trim());
        return messageView(messages.save(message));
    }

    @Transactional(readOnly = true)
    public List<ContextFileView> listFiles(Long id) { requireWorkspace(accessibleTeacher(id)); return files.findByMissionIdOrderByCreatedAtAscIdAsc(id).stream().map(this::fileView).toList(); }

    @Transactional
    public ContextFileView uploadFile(Long id, MultipartFile file, MissionFileKind kind) {
        AuthenticatedUser teacher = current.requireRole(UserRole.TEACHER); requireWorkspace(accessible(id, teacher));
        Validated upload = validate(file, CONTEXT_EXTENSIONS);
        MissionContextFile entity = new MissionContextFile(); entity.setMissionId(id); entity.setUploadedBy(teacher.userId());
        entity.setOriginalFileName(upload.name()); entity.setContentType(upload.contentType()); entity.setFileExtension(upload.extension());
        entity.setFileSize(file.getSize()); entity.setKind(kind == null ? ("pptx".equals(upload.extension()) ? MissionFileKind.TEMPLATE : MissionFileKind.MATERIAL) : kind);
        FileStorageService.StoredFile stored = storage.storeForMission(id, upload.extension(), file);
        entity.setStorageKey(stored.storageKey()); entity.setSha256(stored.sha256());
        try { return fileView(files.save(entity)); } catch (RuntimeException e) { storage.deleteQuietly(stored.storageKey()); throw e; }
    }

    @Transactional
    public void deleteFile(Long id, Long fileId) {
        AuthenticatedUser teacher = current.requireRole(UserRole.TEACHER); requireWorkspace(accessible(id, teacher));
        MissionContextFile file = files.findByIdAndMissionId(fileId, id).orElseThrow(() -> new ResourceNotFoundException("MISSION_FILE_NOT_FOUND"));
        files.delete(file); storage.deleteQuietly(file.getStorageKey());
    }

    @Transactional
    public SubmissionView submit(Long id, MultipartFile file, Long reviewerId) {
        AuthenticatedUser teacher = current.requireRole(UserRole.TEACHER); Mission mission = requireWorkspace(accessible(id, teacher));
        if (reviewerId != null && !reviewerId.equals(mission.getCreatedByLeaderId())) throw new ForbiddenException("REVIEWER_NOT_ALLOWED");
        Validated upload = validate(file, Set.of("pptx"));
        MissionSubmission entity = new MissionSubmission(); entity.setMissionId(id); entity.setUploadedBy(teacher.userId());
        entity.setReviewerId(mission.getCreatedByLeaderId()); entity.setOriginalFileName(upload.name()); entity.setContentType(upload.contentType()); entity.setFileSize(file.getSize());
        FileStorageService.StoredFile stored = storage.storeForMission(id, "pptx", file); entity.setStorageKey(stored.storageKey()); entity.setSha256(stored.sha256()); entity.setStatus(MissionSubmissionStatus.SUBMITTED);
        try { MissionSubmission saved = submissions.save(entity); mission.setStatus(MissionStatus.SUBMITTED); missions.save(mission); return submissionView(saved); }
        catch (RuntimeException e) { storage.deleteQuietly(stored.storageKey()); throw e; }
    }

    @Transactional(readOnly = true)
    public List<SubmissionView> leaderSubmissions(Long id) { AuthenticatedUser leader = current.requireRole(UserRole.LEADER); Mission mission = accessible(id, leader); if (!leader.userId().equals(mission.getCreatedByLeaderId())) throw new ForbiddenException("MISSION_NOT_FOUND"); return submissions.findByMissionIdOrderByCreatedAtDesc(id).stream().map(this::submissionView).toList(); }

    @Transactional
    public SubmissionView review(Long id, Long submissionId, ReviewRequest request) {
        AuthenticatedUser leader = current.requireRole(UserRole.LEADER); Mission mission = accessible(id, leader);
        if (!leader.userId().equals(mission.getCreatedByLeaderId())) throw new ForbiddenException("MISSION_NOT_FOUND");
        if (request == null || request.rating() < 1 || request.rating() > 5) throw new BadRequestException("RATING_INVALID");
        if (request.rating() <= 2 && !StringUtils.hasText(request.note())) throw new BadRequestException("REVIEW_NOTE_REQUIRED");
        MissionSubmission submission = submissions.findById(submissionId).filter(s -> id.equals(s.getMissionId())).orElseThrow(() -> new ResourceNotFoundException("SUBMISSION_NOT_FOUND"));
        submission.setRating(request.rating()); submission.setReviewNote(StringUtils.hasText(request.note()) ? request.note().trim() : null); submission.setReviewedAt(LocalDateTime.now()); submission.setStatus(MissionSubmissionStatus.REVIEWED);
        return submissionView(submissions.save(submission));
    }

    @Transactional(readOnly = true)
    public List<SubmissionView> myFeedback(Long id) { AuthenticatedUser teacher = current.requireRole(UserRole.TEACHER); requireWorkspace(accessible(id, teacher)); return submissions.findByMissionIdAndUploadedByOrderByCreatedAtDesc(id, teacher.userId()).stream().map(this::submissionView).toList(); }

    public FileStorageService.StoredFileIdentity verifyIdentity(String key) { return storage.identity(key); }
    public org.springframework.core.io.Resource loadFile(String key, String checksum) { return storage.loadAndVerify(key, checksum); }
    @Transactional(readOnly = true)
    public Download downloadContext(Long id, Long fileId) {
        requireWorkspace(accessibleTeacher(id));
        MissionContextFile file = files.findByIdAndMissionId(fileId, id).orElseThrow(() -> new ResourceNotFoundException("MISSION_FILE_NOT_FOUND"));
        return new Download(storage.loadAndVerify(file.getStorageKey(), file.getSha256()), file.getOriginalFileName(), file.getContentType(), file.getFileSize());
    }
    @Transactional(readOnly = true)
    public Download downloadSubmission(Long id, Long submissionId) {
        AuthenticatedUser user = current.requireRole(UserRole.TEACHER, UserRole.LEADER); Mission mission = accessible(id, user);
        if (user.activeRole() == UserRole.TEACHER) requireWorkspace(mission);
        MissionSubmission submission = submissions.findById(submissionId).filter(s -> id.equals(s.getMissionId())).orElseThrow(() -> new ResourceNotFoundException("SUBMISSION_NOT_FOUND"));
        if (user.activeRole() == UserRole.TEACHER) {
            if (!user.userId().equals(submission.getUploadedBy())) throw new ResourceNotFoundException("SUBMISSION_NOT_FOUND");
        }
        return new Download(storage.loadAndVerify(submission.getStorageKey(), submission.getSha256()), submission.getOriginalFileName(), submission.getContentType(), submission.getFileSize());
    }
    public record Download(org.springframework.core.io.Resource resource, String fileName, String contentType, long size) {}

    private Mission accessibleTeacher(Long id) { return accessible(id, current.requireRole(UserRole.TEACHER)); }
    private Mission requireWorkspace(Mission mission) {
        if (mission.getStatus() != MissionStatus.ACCEPTED && mission.getStatus() != MissionStatus.SUBMITTED) {
            throw new ConflictException(mission.getStatus() == MissionStatus.REJECTED ? "MISSION_REJECTED" : "MISSION_NOT_ACCEPTED");
        }
        return mission;
    }
    private Mission accessible(Long id, AuthenticatedUser user) {
        Mission mission = missions.findById(id).orElseThrow(() -> new ResourceNotFoundException("MISSION_NOT_FOUND"));
        boolean allowed = user.activeRole() == UserRole.TEACHER ? user.userId().equals(mission.getAssignedTeacherId()) : user.userId().equals(mission.getCreatedByLeaderId());
        if (!allowed) throw new ResourceNotFoundException("MISSION_NOT_FOUND");
        return mission;
    }
    private MissionConversation ensureConversation(Long missionId) { return conversations.findByMissionId(missionId).orElseGet(() -> { MissionConversation c = new MissionConversation(); c.setMissionId(missionId); return conversations.save(c); }); }
    private MissionView view(Mission m, boolean teacher) {
        boolean teacherWorkspace = teacher && canWork(m.getStatus());
        MissionConversation c = conversations.findByMissionId(m.getId()).orElse(null);
        List<MessageView> msg = teacherWorkspace ? messages.findByMissionIdOrderByCreatedAtAscIdAsc(m.getId()).stream().map(this::messageView).toList() : List.of();
        List<ContextFileView> ctx = teacherWorkspace ? files.findByMissionIdOrderByCreatedAtAscIdAsc(m.getId()).stream().map(this::fileView).toList() : List.of();
        List<SubmissionView> subs = teacherWorkspace ? submissions.findByMissionIdAndUploadedByOrderByCreatedAtDesc(m.getId(), m.getAssignedTeacherId()).stream().map(this::submissionView).toList() : teacher ? List.of() : submissions.findByMissionIdOrderByCreatedAtDesc(m.getId()).stream().map(this::submissionView).toList();
        return new MissionView(m.getId(), m.getTitle(), m.getDescription(), m.getAssignedTeacherId(), m.getCreatedByLeaderId(), m.getDeadline(), m.getStatus(), m.getRejectionReason(), teacherWorkspace ? m.getSelectedModelConnectionId() : null, teacherWorkspace && c != null ? c.getId() : null, msg, ctx, subs, userName(m.getAssignedTeacherId()), userName(m.getCreatedByLeaderId()));
    }
    private boolean canWork(MissionStatus status) { return status == MissionStatus.ACCEPTED || status == MissionStatus.SUBMITTED; }
    private String userName(Long id) { return users.findById(id).map(u -> u.getDisplayName()).orElse(null); }
    private MessageView messageView(MissionMessage m) { return new MessageView(m.getId(), m.getSenderRole(), m.getContent(), m.getCreatedAt()); }
    private ContextFileView fileView(MissionContextFile f) { return new ContextFileView(f.getId(), f.getOriginalFileName(), f.getContentType(), f.getFileExtension(), f.getFileSize(), f.getSha256(), f.getKind(), f.getCreatedAt(), "/api/v1/lessonforge/missions/" + f.getMissionId() + "/context-files/" + f.getId() + "/download"); }
    private SubmissionView submissionView(MissionSubmission s) { return new SubmissionView(s.getId(), s.getOriginalFileName(), s.getContentType(), s.getFileSize(), s.getSha256(), s.getStatus(), s.getReviewerId(), s.getRating(), s.getReviewNote(), s.getCreatedAt(), s.getReviewedAt(), "/api/v1/lessonforge/missions/" + s.getMissionId() + "/submissions/" + s.getId() + "/download"); }
    private Validated validate(MultipartFile file, Set<String> allowed) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) throw new BadRequestException("FILE_REQUIRED");
        String raw = Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\', '/').replace("\0", "").replace("\r", "").replace("\n", "");
        String name = raw.substring(raw.lastIndexOf('/') + 1).trim(); int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) throw new BadRequestException("FILE_EXTENSION_INVALID");
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT); if (!allowed.contains(ext)) throw new BadRequestException("FILE_TYPE_NOT_ALLOWED");
        String contentType = Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        if ("pptx".equals(ext) && !Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/octet-stream").contains(contentType)) throw new BadRequestException("FILE_MIME_MISMATCH");
        if (!"pptx".equals(ext) && contentType.isBlank()) throw new BadRequestException("FILE_MIME_REQUIRED");
        return new Validated(name, ext, contentType);
    }
    private record Validated(String name, String extension, String contentType) {}
}
