package com.auvdidao.a12teachingagent.qa;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassMembershipRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.publication.Publication;
import com.auvdidao.a12teachingagent.domain.publication.PublicationStatus;
import com.auvdidao.a12teachingagent.domain.publication.repository.PublicationRepository;
import com.auvdidao.a12teachingagent.domain.qa.Question;
import com.auvdidao.a12teachingagent.domain.qa.QuestionAnswer;
import com.auvdidao.a12teachingagent.domain.qa.QuestionStatus;
import com.auvdidao.a12teachingagent.domain.qa.repository.QuestionAnswerRepository;
import com.auvdidao.a12teachingagent.domain.qa.repository.QuestionRepository;
import com.auvdidao.a12teachingagent.qa.dto.QuestionDtos.CreateAnswerRequest;
import com.auvdidao.a12teachingagent.qa.dto.QuestionDtos.CreateQuestionRequest;
import com.auvdidao.a12teachingagent.qa.dto.QuestionDtos.QuestionAnswerResponse;
import com.auvdidao.a12teachingagent.qa.dto.QuestionDtos.QuestionResponse;
import com.auvdidao.a12teachingagent.qa.dto.QuestionDtos.UpdateQuestionStatusRequest;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class QuestionService {

    private final QuestionRepository questionRepository;
    private final QuestionAnswerRepository questionAnswerRepository;
    private final PublicationRepository publicationRepository;
    private final ProjectRepository projectRepository;
    private final ClassMembershipRepository classMembershipRepository;
    private final AppUserRepository userRepository;
    private final CurrentUserService currentUserService;

    public QuestionService(
            QuestionRepository questionRepository,
            QuestionAnswerRepository questionAnswerRepository,
            PublicationRepository publicationRepository,
            ProjectRepository projectRepository,
            ClassMembershipRepository classMembershipRepository,
            AppUserRepository userRepository,
            CurrentUserService currentUserService
    ) {
        this.questionRepository = questionRepository;
        this.questionAnswerRepository = questionAnswerRepository;
        this.publicationRepository = publicationRepository;
        this.projectRepository = projectRepository;
        this.classMembershipRepository = classMembershipRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public QuestionResponse create(CreateQuestionRequest request) {
        AuthenticatedUser student = currentUserService.requireRole(UserRole.STUDENT);
        Publication publication = requirePublication(request.publicationId());
        if (publication.getStatus() != PublicationStatus.PUBLISHED) {
            throw new ConflictException("Questions can only be asked on a published learning task");
        }
        requireActiveProject(publication.getProjectId());
        if (!classMembershipRepository.existsByClassIdAndStudentId(publication.getClassId(), student.userId())) {
            throw new ForbiddenException("The learning task belongs to a class in which the student is not enrolled");
        }

        Question question = new Question();
        question.setPublicationId(publication.getId());
        question.setProjectId(publication.getProjectId());
        question.setStudentId(student.userId());
        question.setTitle(request.title().trim());
        question.setContent(request.content().trim());
        question.setStatus(QuestionStatus.OPEN);
        return toResponse(questionRepository.save(question));
    }

    @Transactional(readOnly = true)
    public List<QuestionResponse> list(Long publicationId, String statusValue) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.STUDENT, UserRole.TEACHER, UserRole.LEADER);
        QuestionStatus status = parseStatus(statusValue);
        List<Question> questions = switch (user.activeRole()) {
            case STUDENT -> questionRepository.findByStudentIdOrderByUpdatedAtDesc(user.userId()).stream()
                    .filter(question -> isActiveProject(question.getProjectId()))
                    .toList();
            case TEACHER -> questionsForTeacher(user.userId());
            case LEADER -> questionsForLeader(user.userId());
        };
        return toResponses(questions.stream()
                .filter(question -> publicationId == null || publicationId.equals(question.getPublicationId()))
                .filter(question -> status == null || status == question.getStatus())
                .toList());
    }

    @Transactional(readOnly = true)
    public QuestionResponse get(Long questionId) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.STUDENT, UserRole.TEACHER, UserRole.LEADER);
        Question question = requireQuestion(questionId);
        requireReadAccess(user, question);
        return toResponse(question);
    }

    @Transactional
    public QuestionResponse answer(Long questionId, CreateAnswerRequest request) {
        AuthenticatedUser teacher = currentUserService.requireRole(UserRole.TEACHER);
        Question question = requireQuestionForUpdate(questionId);
        requireTeacherOwnsProject(teacher, question);
        if (question.getStatus() == QuestionStatus.CLOSED) {
            throw new ConflictException("Closed questions cannot be answered");
        }

        QuestionAnswer answer = new QuestionAnswer();
        answer.setQuestionId(question.getId());
        answer.setTeacherId(teacher.userId());
        answer.setContent(request.content().trim());
        questionAnswerRepository.save(answer);

        question.setStatus(QuestionStatus.ANSWERED);
        question.setAnsweredAt(LocalDateTime.now());
        return toResponse(questionRepository.save(question));
    }

    @Transactional
    public QuestionResponse updateStatus(Long questionId, UpdateQuestionStatusRequest request) {
        AuthenticatedUser user = currentUserService.requireRole(UserRole.STUDENT, UserRole.TEACHER);
        Question question = requireQuestionForUpdate(questionId);
        requireActiveProject(question.getProjectId());
        if (user.activeRole() == UserRole.STUDENT) {
            requireStudentOwnsQuestion(user, question);
        } else {
            requireTeacherOwnsProject(user, question);
        }

        QuestionStatus target = parseRequiredStatus(request.status());
        applyStatusTransition(question, target);
        return toResponse(questionRepository.save(question));
    }

    private List<Question> questionsForTeacher(Long teacherId) {
        List<Long> projectIds = projectRepository.findByOwnerUserIdAndDeletedAtIsNullOrderByUpdatedAtDescCreatedAtDesc(teacherId)
                .stream()
                .map(Project::getId)
                .toList();
        return projectIds.isEmpty()
                ? List.of()
                : questionRepository.findByProjectIdInOrderByUpdatedAtDesc(projectIds);
    }

    private List<Question> questionsForLeader(Long leaderId) {
        List<Long> publicationIds = publicationRepository.findByPublishedByOrderByPublishedAtDesc(leaderId)
                .stream()
                .filter(publication -> isActiveProject(publication.getProjectId()))
                .map(Publication::getId)
                .toList();
        if (publicationIds.isEmpty()) {
            return List.of();
        }
        return questionRepository.findByPublicationIdInOrderByUpdatedAtDesc(publicationIds).stream()
                .filter(question -> isActiveProject(question.getProjectId()))
                .toList();
    }

    private void requireReadAccess(AuthenticatedUser user, Question question) {
        requireActiveProject(question.getProjectId());
        if (user.activeRole() == UserRole.STUDENT) {
            requireStudentOwnsQuestion(user, question);
            return;
        }
        if (user.activeRole() == UserRole.TEACHER) {
            requireTeacherOwnsProject(user, question);
            return;
        }
        Publication publication = requirePublication(question.getPublicationId());
        if (!user.userId().equals(publication.getPublishedBy())) {
            throw new ForbiddenException("This question belongs to another leader's publication scope");
        }
    }

    private void requireStudentOwnsQuestion(AuthenticatedUser student, Question question) {
        if (!student.userId().equals(question.getStudentId())) {
            throw new ForbiddenException("Students can access only their own questions");
        }
    }

    private void requireTeacherOwnsProject(AuthenticatedUser teacher, Question question) {
        Project project = requireActiveProject(question.getProjectId());
        if (!teacher.userId().equals(project.getOwnerUserId())) {
            throw new ForbiddenException("This question belongs to another teacher's project");
        }
    }

    private void applyStatusTransition(Question question, QuestionStatus target) {
        QuestionStatus current = question.getStatus();
        if (current == target) {
            throw new BadRequestException("Question status is already " + target);
        }
        if (current == QuestionStatus.CLOSED) {
            throw new BadRequestException("Closed questions cannot be reopened");
        }
        if (current == QuestionStatus.OPEN && target != QuestionStatus.CLOSED) {
            throw invalidTransition(current, target);
        }
        if (current == QuestionStatus.ANSWERED
                && target != QuestionStatus.OPEN
                && target != QuestionStatus.CLOSED) {
            throw invalidTransition(current, target);
        }

        question.setStatus(target);
        question.setClosedAt(target == QuestionStatus.CLOSED ? LocalDateTime.now() : null);
    }

    private QuestionStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseRequiredStatus(value);
    }

    private QuestionStatus parseRequiredStatus(String value) {
        try {
            return QuestionStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Unsupported question status: " + value);
        }
    }

    private Question requireQuestion(Long questionId) {
        return questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question not found: " + questionId));
    }

    private Question requireQuestionForUpdate(Long questionId) {
        return questionRepository.findByIdForUpdate(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question not found: " + questionId));
    }

    private Publication requirePublication(Long publicationId) {
        return publicationRepository.findById(publicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Publication not found: " + publicationId));
    }

    private Project requireActiveProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        if (project.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
        return project;
    }

    private boolean isActiveProject(Long projectId) {
        return projectRepository.findById(projectId)
                .map(project -> project.getDeletedAt() == null)
                .orElse(false);
    }

    private BadRequestException invalidTransition(QuestionStatus from, QuestionStatus to) {
        return new BadRequestException("Invalid question status transition: " + from + " -> " + to);
    }

    private List<QuestionResponse> toResponses(List<Question> questions) {
        if (questions.isEmpty()) {
            return List.of();
        }
        Map<Long, List<QuestionAnswer>> answersByQuestionId = questionAnswerRepository
                .findByQuestionIdInOrderByCreatedAtAsc(questions.stream().map(Question::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(QuestionAnswer::getQuestionId));
        Map<Long, AppUser> usersById = usersById(questions, answersByQuestionId.values().stream().flatMap(Collection::stream).toList());
        return questions.stream()
                .map(question -> toResponse(question, answersByQuestionId.getOrDefault(question.getId(), List.of()), usersById))
                .toList();
    }

    private QuestionResponse toResponse(Question question) {
        return toResponses(List.of(question)).get(0);
    }

    private QuestionResponse toResponse(
            Question question,
            List<QuestionAnswer> answers,
            Map<Long, AppUser> usersById
    ) {
        return new QuestionResponse(
                question.getId(),
                question.getPublicationId(),
                question.getProjectId(),
                question.getStudentId(),
                displayName(usersById.get(question.getStudentId()), "Unknown student"),
                question.getTitle(),
                question.getContent(),
                question.getStatus(),
                question.getAnsweredAt(),
                question.getClosedAt(),
                question.getCreatedAt(),
                question.getUpdatedAt(),
                answers.stream()
                        .map(answer -> new QuestionAnswerResponse(
                                answer.getId(),
                                answer.getQuestionId(),
                                answer.getTeacherId(),
                                displayName(usersById.get(answer.getTeacherId()), "Unknown teacher"),
                                answer.getContent(),
                                answer.getCreatedAt()
                        ))
                        .toList()
        );
    }

    private Map<Long, AppUser> usersById(List<Question> questions, List<QuestionAnswer> answers) {
        List<Long> userIds = new java.util.ArrayList<>();
        questions.forEach(question -> userIds.add(question.getStudentId()));
        answers.forEach(answer -> userIds.add(answer.getTeacherId()));
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private String displayName(AppUser user, String fallback) {
        return user == null ? fallback : user.getDisplayName();
    }
}
