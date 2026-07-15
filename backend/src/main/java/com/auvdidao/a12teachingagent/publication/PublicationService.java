package com.auvdidao.a12teachingagent.publication;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.approval.ApprovalRequest;
import com.auvdidao.a12teachingagent.domain.approval.ApprovalStatus;
import com.auvdidao.a12teachingagent.domain.approval.repository.ApprovalRequestRepository;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.course.ClassGroup;
import com.auvdidao.a12teachingagent.domain.course.Course;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassGroupRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassMembershipRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.CourseRepository;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.publication.Publication;
import com.auvdidao.a12teachingagent.domain.publication.PublicationStatus;
import com.auvdidao.a12teachingagent.domain.publication.repository.PublicationRepository;
import com.auvdidao.a12teachingagent.publication.dto.PublicationDtos.ArtifactVersionMetadata;
import com.auvdidao.a12teachingagent.publication.dto.PublicationDtos.CreatePublicationRequest;
import com.auvdidao.a12teachingagent.publication.dto.PublicationDtos.LearningTaskDetail;
import com.auvdidao.a12teachingagent.publication.dto.PublicationDtos.LearningTaskSummary;
import com.auvdidao.a12teachingagent.publication.dto.PublicationDtos.PublicationResponse;
import com.auvdidao.a12teachingagent.publication.dto.PublicationDtos.PublishedArtifact;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class PublicationService {

    private final PublicationRepository publicationRepository;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final ArtifactVersionRepository artifactVersionRepository;
    private final GeneratedArtifactRepository generatedArtifactRepository;
    private final ProjectRepository projectRepository;
    private final ClassGroupRepository classGroupRepository;
    private final ClassMembershipRepository classMembershipRepository;
    private final CourseRepository courseRepository;
    private final AppUserRepository appUserRepository;
    private final CurrentUserService currentUserService;

    public PublicationService(
            PublicationRepository publicationRepository,
            ApprovalRequestRepository approvalRequestRepository,
            ArtifactVersionRepository artifactVersionRepository,
            GeneratedArtifactRepository generatedArtifactRepository,
            ProjectRepository projectRepository,
            ClassGroupRepository classGroupRepository,
            ClassMembershipRepository classMembershipRepository,
            CourseRepository courseRepository,
            AppUserRepository appUserRepository,
            CurrentUserService currentUserService
    ) {
        this.publicationRepository = publicationRepository;
        this.approvalRequestRepository = approvalRequestRepository;
        this.artifactVersionRepository = artifactVersionRepository;
        this.generatedArtifactRepository = generatedArtifactRepository;
        this.projectRepository = projectRepository;
        this.classGroupRepository = classGroupRepository;
        this.classMembershipRepository = classMembershipRepository;
        this.courseRepository = courseRepository;
        this.appUserRepository = appUserRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public PublicationResponse publish(CreatePublicationRequest request) {
        AuthenticatedUser leader = currentUserService.requireRole(UserRole.LEADER);
        ApprovalRequest approvalRequest = approvalRequestRepository.findByIdForUpdate(request.approvalRequestId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Approval request not found: " + request.approvalRequestId()
                ));
        if (!leader.userId().equals(approvalRequest.getReviewerId())) {
            throw new ForbiddenException("This approval request is assigned to another leader");
        }
        if (approvalRequest.getStatus() != ApprovalStatus.APPROVED) {
            throw new ConflictException("Only an approved request can be published");
        }

        ArtifactVersion artifactVersion = artifactVersionRepository.findById(approvalRequest.getArtifactVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Artifact version not found: " + approvalRequest.getArtifactVersionId()
                ));
        Project project = projectRepository.findById(approvalRequest.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + approvalRequest.getProjectId()
                ));
        requireActiveProject(project);
        requireVersionProjectMatch(approvalRequest, artifactVersion, project);

        ClassGroup classGroup = classGroupRepository.findById(request.classId())
                .orElseThrow(() -> new ResourceNotFoundException("Class not found: " + request.classId()));
        Course course = courseRepository.findById(classGroup.getCourseId())
                .orElseThrow(() -> new ResourceNotFoundException("Course not found: " + classGroup.getCourseId()));
        if (!sameCourseName(project.getCourseName(), course.getCourseName())) {
            throw new BadRequestException("The class course does not match the approved project course");
        }
        if (publicationRepository.existsByApprovalRequestIdAndClassId(
                approvalRequest.getId(),
                classGroup.getId()
        )) {
            throw new ConflictException("A publication already exists for this approval request and class");
        }

        LocalDateTime now = LocalDateTime.now();
        Publication publication = new Publication();
        publication.setApprovalRequestId(approvalRequest.getId());
        publication.setArtifactVersionId(artifactVersion.getId());
        publication.setProjectId(project.getId());
        publication.setCourseId(course.getId());
        publication.setClassId(classGroup.getId());
        publication.setTitle(request.title().trim());
        publication.setSummary(trimToNull(request.summary()));
        publication.setPublishedBy(leader.userId());
        publication.setStatus(PublicationStatus.PUBLISHED);
        publication.setPublishedAt(now);
        return toPublicationResponse(publicationRepository.save(publication));
    }

    @Transactional(readOnly = true)
    public List<PublicationResponse> listPublications(PublicationStatus status) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.LEADER, UserRole.TEACHER);
        List<Publication> publications;
        if (user.activeRole() == UserRole.LEADER) {
            publications = status == null
                    ? publicationRepository.findByPublishedByOrderByPublishedAtDesc(user.userId())
                    : publicationRepository.findByPublishedByAndStatusOrderByPublishedAtDesc(user.userId(), status);
        } else {
            List<Long> projectIds = projectRepository
                    .findByOwnerUserIdOrderByUpdatedAtDescCreatedAtDesc(user.userId())
                    .stream()
                    .map(Project::getId)
                    .toList();
            if (projectIds.isEmpty()) {
                return List.of();
            }
            publications = status == null
                    ? publicationRepository.findByProjectIdInOrderByPublishedAtDesc(projectIds)
                    : publicationRepository.findByProjectIdInAndStatusOrderByPublishedAtDesc(projectIds, status);
        }
        return publications.stream()
                .filter(publication -> isActiveProject(publication.getProjectId()))
                .map(this::toPublicationResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PublicationResponse getPublication(Long publicationId) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.LEADER, UserRole.TEACHER);
        Publication publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Publication not found: " + publicationId));
        requireCanReadPublication(user, publication);
        return toPublicationResponse(publication);
    }

    @Transactional
    public PublicationResponse withdraw(Long publicationId) {
        AuthenticatedUser leader = currentUserService.requireRole(UserRole.LEADER);
        Publication publication = publicationRepository.findByIdForUpdate(publicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Publication not found: " + publicationId));
        requireActiveProjectById(publication.getProjectId());
        if (!leader.userId().equals(publication.getPublishedBy())) {
            throw new ForbiddenException("This publication belongs to another leader");
        }
        if (publication.getStatus() != PublicationStatus.PUBLISHED) {
            throw new ConflictException("Only a published item can be withdrawn");
        }
        publication.setStatus(PublicationStatus.WITHDRAWN);
        publication.setWithdrawnAt(LocalDateTime.now());
        return toPublicationResponse(publicationRepository.save(publication));
    }

    @Transactional(readOnly = true)
    public List<LearningTaskSummary> listLearningTasks() {
        AuthenticatedUser student = currentUserService.requireRole(UserRole.STUDENT);
        List<Long> classIds = classMembershipRepository.findByStudentId(student.userId())
                .stream()
                .map(membership -> membership.getClassId())
                .distinct()
                .toList();
        if (classIds.isEmpty()) {
            return List.of();
        }
        return publicationRepository
                .findByClassIdInAndStatusOrderByPublishedAtDesc(classIds, PublicationStatus.PUBLISHED)
                .stream()
                .filter(publication -> isActiveProject(publication.getProjectId()))
                .map(this::toLearningTaskSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public LearningTaskDetail getLearningTask(Long publicationId) {
        AuthenticatedUser student = currentUserService.requireRole(UserRole.STUDENT);
        Publication publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> learningTaskNotFound(publicationId));
        if (!classMembershipRepository.existsByClassIdAndStudentId(publication.getClassId(), student.userId())) {
            throw new ForbiddenException("The learning task belongs to a class in which the student is not enrolled");
        }
        if (publication.getStatus() != PublicationStatus.PUBLISHED) {
            throw learningTaskNotFound(publicationId);
        }

        Project project = requireActiveProjectById(publication.getProjectId());
        ClassGroup classGroup = classGroupRepository.findById(publication.getClassId())
                .orElseThrow(() -> new ResourceNotFoundException("Class not found: " + publication.getClassId()));
        Course course = courseRepository.findById(publication.getCourseId())
                .orElseThrow(() -> new ResourceNotFoundException("Course not found: " + publication.getCourseId()));
        ArtifactVersion artifactVersion = artifactVersionRepository.findById(publication.getArtifactVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Artifact version not found: " + publication.getArtifactVersionId()
                ));
        if (!publication.getProjectId().equals(artifactVersion.getProjectId())) {
            throw new ResourceNotFoundException("Published artifact version does not belong to the publication project");
        }

        List<PublishedArtifact> artifacts = generatedArtifactRepository
                .findByProjectIdOrderByCreatedAtAsc(publication.getProjectId())
                .stream()
                .filter(artifact -> publication.getArtifactVersionId().equals(artifact.getVersionId()))
                .sorted(Comparator
                        .comparing(GeneratedArtifact::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(GeneratedArtifact::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toPublishedArtifact)
                .toList();

        return new LearningTaskDetail(
                publication.getId(),
                publication.getApprovalRequestId(),
                publication.getArtifactVersionId(),
                publication.getProjectId(),
                project.getProjectName(),
                publication.getCourseId(),
                course.getCourseName(),
                publication.getClassId(),
                classGroup.getClassName(),
                publication.getTitle(),
                publication.getSummary(),
                publication.getPublishedAt(),
                new ArtifactVersionMetadata(
                        artifactVersion.getId(),
                        artifactVersion.getVersionNumber(),
                        artifactVersion.getDescription(),
                        artifactVersion.getFinalVersion(),
                        artifactVersion.getCreatedAt()
                ),
                artifacts
        );
    }

    private void requireVersionProjectMatch(
            ApprovalRequest approvalRequest,
            ArtifactVersion artifactVersion,
            Project project
    ) {
        if (!approvalRequest.getProjectId().equals(artifactVersion.getProjectId())
                || !approvalRequest.getProjectId().equals(project.getId())) {
            throw new BadRequestException("Approval request, artifact version, and project do not match");
        }
    }

    private void requireCanReadPublication(AuthenticatedUser user, Publication publication) {
        Project project = requireActiveProjectById(publication.getProjectId());
        if (user.activeRole() == UserRole.LEADER) {
            if (!user.userId().equals(publication.getPublishedBy())) {
                throw new ForbiddenException("This publication belongs to another leader");
            }
            return;
        }

        if (project.getOwnerUserId() == null || !user.userId().equals(project.getOwnerUserId())) {
            throw new ForbiddenException("This publication belongs to another teacher's project");
        }
    }

    private Project requireActiveProjectById(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        return requireActiveProject(project);
    }

    private Project requireActiveProject(Project project) {
        if (project.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Project not found: " + project.getId());
        }
        return project;
    }

    private boolean isActiveProject(Long projectId) {
        return projectRepository.findById(projectId)
                .map(project -> project.getDeletedAt() == null)
                .orElse(false);
    }

    private PublicationResponse toPublicationResponse(Publication publication) {
        Project project = projectRepository.findById(publication.getProjectId()).orElse(null);
        Course course = courseRepository.findById(publication.getCourseId()).orElse(null);
        ClassGroup classGroup = classGroupRepository.findById(publication.getClassId()).orElse(null);
        AppUser publisher = appUserRepository.findById(publication.getPublishedBy()).orElse(null);
        return new PublicationResponse(
                publication.getId(),
                publication.getApprovalRequestId(),
                publication.getArtifactVersionId(),
                publication.getProjectId(),
                project == null ? "Unknown project" : project.getProjectName(),
                publication.getCourseId(),
                course == null ? "Unknown course" : course.getCourseName(),
                publication.getClassId(),
                classGroup == null ? "Unknown class" : classGroup.getClassName(),
                publication.getTitle(),
                publication.getSummary(),
                publication.getPublishedBy(),
                publisher == null ? "Unknown leader" : publisher.getDisplayName(),
                publication.getStatus(),
                publication.getPublishedAt(),
                publication.getWithdrawnAt()
        );
    }

    private LearningTaskSummary toLearningTaskSummary(Publication publication) {
        Project project = projectRepository.findById(publication.getProjectId()).orElse(null);
        Course course = courseRepository.findById(publication.getCourseId()).orElse(null);
        ClassGroup classGroup = classGroupRepository.findById(publication.getClassId()).orElse(null);
        return new LearningTaskSummary(
                publication.getId(),
                publication.getApprovalRequestId(),
                publication.getArtifactVersionId(),
                publication.getProjectId(),
                project == null ? "Unknown project" : project.getProjectName(),
                publication.getCourseId(),
                course == null ? "Unknown course" : course.getCourseName(),
                publication.getClassId(),
                classGroup == null ? "Unknown class" : classGroup.getClassName(),
                publication.getTitle(),
                publication.getSummary(),
                publication.getPublishedAt()
        );
    }

    private PublishedArtifact toPublishedArtifact(GeneratedArtifact artifact) {
        return new PublishedArtifact(
                artifact.getArtifactType(),
                artifact.getTitle(),
                artifact.getContentJson(),
                artifact.getSchemaVersion()
        );
    }

    private ResourceNotFoundException learningTaskNotFound(Long publicationId) {
        return new ResourceNotFoundException("Learning task not found: " + publicationId);
    }

    private boolean sameCourseName(String projectCourseName, String courseName) {
        if (projectCourseName == null || courseName == null) {
            return false;
        }
        return projectCourseName.trim().toLowerCase(Locale.ROOT)
                .equals(courseName.trim().toLowerCase(Locale.ROOT));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
