package com.auvdidao.a12teachingagent.approval;

import com.auvdidao.a12teachingagent.approval.dto.ApprovalRequestDtos.ApprovalRequestResponse;
import com.auvdidao.a12teachingagent.approval.dto.ApprovalRequestDtos.ReviewApprovalRequest;
import com.auvdidao.a12teachingagent.approval.dto.ApprovalRequestDtos.SubmitApprovalRequest;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.approval.ApprovalRequest;
import com.auvdidao.a12teachingagent.domain.approval.ApprovalStatus;
import com.auvdidao.a12teachingagent.domain.approval.repository.ApprovalRequestRepository;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApprovalRequestService {

    private final ApprovalRequestRepository approvalRequestRepository;
    private final ArtifactVersionRepository artifactVersionRepository;
    private final ProjectRepository projectRepository;
    private final AppUserRepository appUserRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final CurrentUserService currentUserService;

    public ApprovalRequestService(
            ApprovalRequestRepository approvalRequestRepository,
            ArtifactVersionRepository artifactVersionRepository,
            ProjectRepository projectRepository,
            AppUserRepository appUserRepository,
            UserRoleAssignmentRepository roleAssignmentRepository,
            CurrentUserService currentUserService
    ) {
        this.approvalRequestRepository = approvalRequestRepository;
        this.artifactVersionRepository = artifactVersionRepository;
        this.projectRepository = projectRepository;
        this.appUserRepository = appUserRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public ApprovalRequestResponse submit(SubmitApprovalRequest request) {
        AuthenticatedUser teacher = currentUserService.requireRole(UserRole.TEACHER);
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + request.projectId()));
        if (project.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Project not found: " + request.projectId());
        }
        requireProjectOwner(teacher, project);

        ArtifactVersion artifactVersion = artifactVersionRepository.findById(request.artifactVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Artifact version not found: " + request.artifactVersionId()
                ));
        if (!request.projectId().equals(artifactVersion.getProjectId())) {
            throw new BadRequestException("Artifact version does not belong to project: " + request.projectId());
        }
        if (!Boolean.TRUE.equals(artifactVersion.getFinalVersion())) {
            throw new BadRequestException("Only a final artifact version can be submitted for approval");
        }

        AppUser reviewer = appUserRepository.findById(request.reviewerId())
                .orElseThrow(() -> new ResourceNotFoundException("Reviewer not found: " + request.reviewerId()));
        if (!Boolean.TRUE.equals(reviewer.getEnabled())
                || !roleAssignmentRepository.existsByUserIdAndRole(reviewer.getId(), UserRole.LEADER)) {
            throw new BadRequestException("The reviewer must be an enabled leader");
        }
        if (teacher.userId().equals(reviewer.getId())) {
            throw new BadRequestException("A teacher cannot review their own approval request");
        }
        if (approvalRequestRepository.existsByActiveArtifactVersionId(artifactVersion.getId())) {
            throw duplicateSubmission(artifactVersion.getId());
        }

        ApprovalRequest approvalRequest = new ApprovalRequest();
        approvalRequest.setArtifactVersionId(artifactVersion.getId());
        approvalRequest.setActiveArtifactVersionId(artifactVersion.getId());
        approvalRequest.setProjectId(project.getId());
        approvalRequest.setSubmittedBy(teacher.userId());
        approvalRequest.setReviewerId(reviewer.getId());
        approvalRequest.setStatus(ApprovalStatus.SUBMITTED);
        approvalRequest.setSubmittedAt(LocalDateTime.now());

        try {
            return toResponse(approvalRequestRepository.saveAndFlush(approvalRequest));
        } catch (DataIntegrityViolationException exception) {
            throw duplicateSubmission(artifactVersion.getId());
        }
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestResponse> list(ApprovalStatus status) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.TEACHER, UserRole.LEADER);
        List<ApprovalRequest> requests;
        if (user.activeRole() == UserRole.TEACHER) {
            requests = status == null
                    ? approvalRequestRepository.findBySubmittedByOrderByCreatedAtDesc(user.userId())
                    : approvalRequestRepository.findBySubmittedByAndStatusOrderByCreatedAtDesc(user.userId(), status);
        } else {
            requests = status == null
                    ? approvalRequestRepository.findByReviewerIdOrderByCreatedAtDesc(user.userId())
                    : approvalRequestRepository.findByReviewerIdAndStatusOrderByCreatedAtDesc(user.userId(), status);
        }
        return requests.stream()
                .filter(this::hasActiveProject)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ApprovalRequestResponse get(Long approvalRequestId) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.TEACHER, UserRole.LEADER);
        ApprovalRequest approvalRequest = requireApprovalRequest(approvalRequestId);
        requireActiveProject(approvalRequest);
        requireCanRead(user, approvalRequest);
        return toResponse(approvalRequest);
    }

    @Transactional
    public ApprovalRequestResponse review(Long approvalRequestId, ReviewApprovalRequest request) {
        AuthenticatedUser leader = currentUserService.requireRole(UserRole.LEADER);
        ApprovalRequest approvalRequest = approvalRequestRepository.findByIdForUpdate(approvalRequestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Approval request not found: " + approvalRequestId
                ));
        requireActiveProject(approvalRequest);
        requireReviewer(leader, approvalRequest);
        if (approvalRequest.getStatus() != ApprovalStatus.SUBMITTED) {
            throw new ConflictException("Only a submitted approval request can be reviewed");
        }
        if (request.status() != ApprovalStatus.APPROVED
                && request.status() != ApprovalStatus.REVISION_REQUIRED) {
            throw new BadRequestException("Review status must be APPROVED or REVISION_REQUIRED");
        }

        String note = trimToNull(request.note());
        if (request.status() == ApprovalStatus.REVISION_REQUIRED && note == null) {
            throw new BadRequestException("A revision note is required");
        }
        approvalRequest.setStatus(request.status());
        approvalRequest.setReviewNote(note);
        approvalRequest.setReviewedAt(LocalDateTime.now());
        approvalRequest.setActiveArtifactVersionId(null);
        return toResponse(approvalRequestRepository.save(approvalRequest));
    }

    @Transactional
    public ApprovalRequestResponse cancel(Long approvalRequestId) {
        AuthenticatedUser teacher = currentUserService.requireRole(UserRole.TEACHER);
        ApprovalRequest approvalRequest = approvalRequestRepository.findByIdForUpdate(approvalRequestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Approval request not found: " + approvalRequestId
                ));
        requireActiveProject(approvalRequest);
        if (!teacher.userId().equals(approvalRequest.getSubmittedBy())) {
            throw new ForbiddenException("This approval request belongs to another teacher");
        }
        if (approvalRequest.getStatus() != ApprovalStatus.SUBMITTED) {
            throw new ConflictException("Only a submitted approval request can be cancelled");
        }
        approvalRequest.setStatus(ApprovalStatus.CANCELLED);
        approvalRequest.setActiveArtifactVersionId(null);
        return toResponse(approvalRequestRepository.save(approvalRequest));
    }

    private ApprovalRequest requireApprovalRequest(Long approvalRequestId) {
        return approvalRequestRepository.findById(approvalRequestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Approval request not found: " + approvalRequestId
                ));
    }

    private boolean hasActiveProject(ApprovalRequest approvalRequest) {
        return projectRepository.findById(approvalRequest.getProjectId())
                .map(project -> project.getDeletedAt() == null)
                .orElse(false);
    }

    private Project requireActiveProject(ApprovalRequest approvalRequest) {
        Project project = projectRepository.findById(approvalRequest.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + approvalRequest.getProjectId()
                ));
        if (project.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Project not found: " + approvalRequest.getProjectId());
        }
        return project;
    }

    private void requireProjectOwner(AuthenticatedUser teacher, Project project) {
        if (project.getOwnerUserId() == null || !teacher.userId().equals(project.getOwnerUserId())) {
            throw new ForbiddenException("The project belongs to another teacher");
        }
    }

    private void requireCanRead(AuthenticatedUser user, ApprovalRequest approvalRequest) {
        if (user.activeRole() == UserRole.TEACHER) {
            if (!user.userId().equals(approvalRequest.getSubmittedBy())) {
                throw new ForbiddenException("This approval request belongs to another teacher");
            }
            return;
        }
        requireReviewer(user, approvalRequest);
    }

    private void requireReviewer(AuthenticatedUser leader, ApprovalRequest approvalRequest) {
        if (!leader.userId().equals(approvalRequest.getReviewerId())) {
            throw new ForbiddenException("This approval request is assigned to another leader");
        }
    }

    private ConflictException duplicateSubmission(Long artifactVersionId) {
        return new ConflictException("An active approval request already exists for artifact version: " + artifactVersionId);
    }

    private ApprovalRequestResponse toResponse(ApprovalRequest approvalRequest) {
        ArtifactVersion artifactVersion = artifactVersionRepository.findById(approvalRequest.getArtifactVersionId())
                .orElse(null);
        Project project = requireActiveProject(approvalRequest);
        AppUser submittedBy = appUserRepository.findById(approvalRequest.getSubmittedBy()).orElse(null);
        AppUser reviewer = appUserRepository.findById(approvalRequest.getReviewerId()).orElse(null);
        return new ApprovalRequestResponse(
                approvalRequest.getId(),
                approvalRequest.getArtifactVersionId(),
                artifactVersion == null ? null : artifactVersion.getVersionNumber(),
                approvalRequest.getProjectId(),
                project.getProjectName(),
                approvalRequest.getSubmittedBy(),
                submittedBy == null ? "Unknown teacher" : submittedBy.getDisplayName(),
                approvalRequest.getReviewerId(),
                reviewer == null ? "Unknown leader" : reviewer.getDisplayName(),
                approvalRequest.getStatus(),
                approvalRequest.getReviewNote(),
                approvalRequest.getSubmittedAt(),
                approvalRequest.getReviewedAt(),
                approvalRequest.getCreatedAt(),
                approvalRequest.getUpdatedAt()
        );
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
