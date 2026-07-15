package com.auvdidao.a12teachingagent.teachingtask;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.TeachingTaskStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.course.ClassGroup;
import com.auvdidao.a12teachingagent.domain.course.Course;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassGroupRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.CourseRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.teachingtask.TeachingTask;
import com.auvdidao.a12teachingagent.domain.teachingtask.repository.TeachingTaskRepository;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.teachingtask.dto.TeachingTaskDtos.TaskStatusRequest;
import com.auvdidao.a12teachingagent.teachingtask.dto.TeachingTaskDtos.TaskSubmissionRequest;
import com.auvdidao.a12teachingagent.teachingtask.dto.TeachingTaskDtos.TeachingTaskRequest;
import com.auvdidao.a12teachingagent.teachingtask.dto.TeachingTaskDtos.TeachingTaskResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class TeachingTaskService {

    private static final Set<TeachingTaskStatus> TERMINAL_STATUSES = Set.of(
            TeachingTaskStatus.COMPLETED,
            TeachingTaskStatus.CANCELLED
    );

    private final TeachingTaskRepository taskRepository;
    private final CourseRepository courseRepository;
    private final ClassGroupRepository classGroupRepository;
    private final AppUserRepository userRepository;
    private final UserRoleAssignmentRepository roleRepository;
    private final ProjectRepository projectRepository;
    private final CurrentUserService currentUserService;

    public TeachingTaskService(
            TeachingTaskRepository taskRepository,
            CourseRepository courseRepository,
            ClassGroupRepository classGroupRepository,
            AppUserRepository userRepository,
            UserRoleAssignmentRepository roleRepository,
            ProjectRepository projectRepository,
            CurrentUserService currentUserService
    ) {
        this.taskRepository = taskRepository;
        this.courseRepository = courseRepository;
        this.classGroupRepository = classGroupRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.projectRepository = projectRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public TeachingTaskResponse create(TeachingTaskRequest request) {
        AuthenticatedUser leader = currentUserService.requireRole(UserRole.LEADER);
        validateReferences(request);
        TeachingTask task = new TeachingTask();
        apply(task, request);
        task.setCreatedBy(leader.userId());
        task.setTaskStatus(TeachingTaskStatus.ASSIGNED);
        return toResponse(taskRepository.save(task));
    }

    @Transactional(readOnly = true)
    public List<TeachingTaskResponse> list(TeachingTaskStatus status) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.LEADER, UserRole.TEACHER);
        List<TeachingTask> tasks;
        if (user.activeRole() == UserRole.LEADER) {
            tasks = status == null
                    ? taskRepository.findByCreatedByOrderByUpdatedAtDesc(user.userId())
                    : taskRepository.findByCreatedByAndTaskStatusOrderByUpdatedAtDesc(user.userId(), status);
        } else {
            tasks = status == null
                    ? taskRepository.findByAssigneeIdOrderByUpdatedAtDesc(user.userId())
                    : taskRepository.findByAssigneeIdAndTaskStatusOrderByUpdatedAtDesc(user.userId(), status);
        }
        return tasks.stream()
                .filter(this::hasActiveLinkedProject)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TeachingTaskResponse get(Long taskId) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.LEADER, UserRole.TEACHER);
        TeachingTask task = requireTask(taskId);
        requireActiveLinkedProject(task);
        requireCanRead(user, task);
        return toResponse(task);
    }

    @Transactional
    public TeachingTaskResponse update(Long taskId, TeachingTaskRequest request) {
        AuthenticatedUser leader = currentUserService.requireRole(UserRole.LEADER);
        TeachingTask task = requireTask(taskId);
        requireCreator(leader, task);
        if (task.getTaskStatus() == TeachingTaskStatus.SUBMITTED || TERMINAL_STATUSES.contains(task.getTaskStatus())) {
            throw new BadRequestException("Submitted or closed tasks cannot be edited");
        }
        validateReferences(request);
        apply(task, request);
        if (task.getTaskStatus() == TeachingTaskStatus.REVISION_REQUIRED) {
            task.setTaskStatus(TeachingTaskStatus.ASSIGNED);
        }
        return toResponse(taskRepository.save(task));
    }

    @Transactional
    public TeachingTaskResponse updateStatus(Long taskId, TaskStatusRequest request) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.LEADER, UserRole.TEACHER);
        TeachingTask task = requireTask(taskId);
        requireActiveLinkedProject(task);
        if (user.activeRole() == UserRole.TEACHER) {
            requireAssignee(user, task);
            if (request.status() != TeachingTaskStatus.IN_PROGRESS) {
                throw new ForbiddenException("Teachers can only move an assigned task into progress");
            }
            if (task.getTaskStatus() != TeachingTaskStatus.ASSIGNED
                    && task.getTaskStatus() != TeachingTaskStatus.REVISION_REQUIRED) {
                throw invalidTransition(task.getTaskStatus(), request.status());
            }
            task.setTaskStatus(TeachingTaskStatus.IN_PROGRESS);
            return toResponse(taskRepository.save(task));
        }

        requireCreator(user, task);
        applyLeaderStatus(task, request);
        return toResponse(taskRepository.save(task));
    }

    @Transactional
    public TeachingTaskResponse submit(Long taskId, TaskSubmissionRequest request) {
        AuthenticatedUser teacher = currentUserService.requireRole(UserRole.TEACHER);
        TeachingTask task = requireTask(taskId);
        requireAssignee(teacher, task);
        requireActiveLinkedProject(task);
        if (task.getTaskStatus() != TeachingTaskStatus.ASSIGNED
                && task.getTaskStatus() != TeachingTaskStatus.IN_PROGRESS
                && task.getTaskStatus() != TeachingTaskStatus.REVISION_REQUIRED) {
            throw invalidTransition(task.getTaskStatus(), TeachingTaskStatus.SUBMITTED);
        }
        if (request.linkedProjectId() != null) {
            Project project = projectRepository.findById(request.linkedProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + request.linkedProjectId()));
            if (project.getDeletedAt() != null) {
                throw new ResourceNotFoundException("Project not found: " + request.linkedProjectId());
            }
            if (project.getOwnerUserId() == null || !teacher.userId().equals(project.getOwnerUserId())) {
                throw new ForbiddenException("The linked project belongs to another teacher");
            }
        }
        task.setLinkedProjectId(request.linkedProjectId() == null ? task.getLinkedProjectId() : request.linkedProjectId());
        task.setSubmissionNote(request.note().trim());
        task.setReviewNote(null);
        task.setSubmittedAt(LocalDateTime.now());
        task.setTaskStatus(TeachingTaskStatus.SUBMITTED);
        return toResponse(taskRepository.save(task));
    }

    private void applyLeaderStatus(TeachingTask task, TaskStatusRequest request) {
        TeachingTaskStatus target = request.status();
        if (target == TeachingTaskStatus.CANCELLED && !TERMINAL_STATUSES.contains(task.getTaskStatus())) {
            task.setTaskStatus(target);
            task.setReviewNote(trimToNull(request.note()));
            return;
        }
        if (task.getTaskStatus() != TeachingTaskStatus.SUBMITTED) {
            throw invalidTransition(task.getTaskStatus(), target);
        }
        if (target == TeachingTaskStatus.REVISION_REQUIRED) {
            if (trimToNull(request.note()) == null) {
                throw new BadRequestException("A revision note is required");
            }
            task.setTaskStatus(target);
            task.setReviewNote(request.note().trim());
            return;
        }
        if (target == TeachingTaskStatus.COMPLETED) {
            task.setTaskStatus(target);
            task.setReviewNote(trimToNull(request.note()));
            task.setCompletedAt(LocalDateTime.now());
            return;
        }
        throw invalidTransition(task.getTaskStatus(), target);
    }

    private void validateReferences(TeachingTaskRequest request) {
        courseRepository.findById(request.courseId())
                .orElseThrow(() -> new ResourceNotFoundException("Course not found: " + request.courseId()));
        if (request.classId() != null) {
            ClassGroup classGroup = classGroupRepository.findById(request.classId())
                    .orElseThrow(() -> new ResourceNotFoundException("Class not found: " + request.classId()));
            if (!request.courseId().equals(classGroup.getCourseId())) {
                throw new BadRequestException("The selected class does not belong to the selected course");
            }
        }
        AppUser assignee = userRepository.findById(request.assigneeId())
                .orElseThrow(() -> new ResourceNotFoundException("Assignee not found: " + request.assigneeId()));
        if (!Boolean.TRUE.equals(assignee.getEnabled())
                || !roleRepository.existsByUserIdAndRole(assignee.getId(), UserRole.TEACHER)) {
            throw new BadRequestException("The assignee must be an enabled teacher");
        }
        if (request.linkedProjectId() != null) {
            Project project = projectRepository.findById(request.linkedProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + request.linkedProjectId()));
            if (project.getDeletedAt() != null) {
                throw new ResourceNotFoundException("Project not found: " + request.linkedProjectId());
            }
            if (project.getOwnerUserId() == null || !request.assigneeId().equals(project.getOwnerUserId())) {
                throw new BadRequestException("The linked project must belong to the assigned teacher");
            }
        }
    }

    private void apply(TeachingTask task, TeachingTaskRequest request) {
        task.setTaskName(request.taskName().trim());
        task.setCourseId(request.courseId());
        task.setClassId(request.classId());
        task.setChapterTitle(request.chapterTitle().trim());
        task.setAssigneeId(request.assigneeId());
        task.setRequirements(request.requirements().trim());
        task.setPriority(request.priority());
        task.setDueAt(request.dueAt());
        task.setLinkedProjectId(request.linkedProjectId());
    }

    private TeachingTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Teaching task not found: " + taskId));
    }

    private boolean hasActiveLinkedProject(TeachingTask task) {
        if (task.getLinkedProjectId() == null) {
            return true;
        }
        return projectRepository.findById(task.getLinkedProjectId())
                .map(project -> project.getDeletedAt() == null)
                .orElse(false);
    }

    private void requireActiveLinkedProject(TeachingTask task) {
        if (task.getLinkedProjectId() == null) {
            return;
        }
        Project project = projectRepository.findById(task.getLinkedProjectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + task.getLinkedProjectId()
                ));
        if (project.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Project not found: " + task.getLinkedProjectId());
        }
    }

    private void requireCanRead(AuthenticatedUser user, TeachingTask task) {
        if (user.activeRole() == UserRole.LEADER) {
            requireCreator(user, task);
        } else {
            requireAssignee(user, task);
        }
    }

    private void requireCreator(AuthenticatedUser user, TeachingTask task) {
        if (!user.userId().equals(task.getCreatedBy())) {
            throw new ForbiddenException("This task belongs to another leader");
        }
    }

    private void requireAssignee(AuthenticatedUser user, TeachingTask task) {
        if (!user.userId().equals(task.getAssigneeId())) {
            throw new ForbiddenException("This task is assigned to another teacher");
        }
    }

    private BadRequestException invalidTransition(TeachingTaskStatus from, TeachingTaskStatus to) {
        return new BadRequestException("Invalid teaching task transition: " + from + " -> " + to);
    }

    private TeachingTaskResponse toResponse(TeachingTask task) {
        Course course = courseRepository.findById(task.getCourseId()).orElse(null);
        ClassGroup classGroup = task.getClassId() == null ? null : classGroupRepository.findById(task.getClassId()).orElse(null);
        AppUser assignee = userRepository.findById(task.getAssigneeId()).orElse(null);
        AppUser creator = userRepository.findById(task.getCreatedBy()).orElse(null);
        return new TeachingTaskResponse(
                task.getId(),
                task.getTaskName(),
                task.getCourseId(),
                course == null ? "Unknown course" : course.getCourseName(),
                task.getClassId(),
                classGroup == null ? null : classGroup.getClassName(),
                task.getChapterTitle(),
                task.getAssigneeId(),
                assignee == null ? "Unknown teacher" : assignee.getDisplayName(),
                task.getRequirements(),
                task.getPriority(),
                task.getDueAt(),
                task.getCreatedBy(),
                creator == null ? "Unknown leader" : creator.getDisplayName(),
                task.getLinkedProjectId(),
                task.getTaskStatus(),
                isOverdue(task),
                task.getSubmissionNote(),
                task.getReviewNote(),
                task.getSubmittedAt(),
                task.getCompletedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private boolean isOverdue(TeachingTask task) {
        return task.getDueAt() != null
                && task.getDueAt().isBefore(LocalDateTime.now())
                && !TERMINAL_STATUSES.contains(task.getTaskStatus());
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
