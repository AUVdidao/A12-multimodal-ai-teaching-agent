package com.auvdidao.a12teachingagent.course;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.course.dto.CourseDtos.ClassGroupRequest;
import com.auvdidao.a12teachingagent.course.dto.CourseDtos.ClassGroupResponse;
import com.auvdidao.a12teachingagent.course.dto.CourseDtos.ClassMembershipResponse;
import com.auvdidao.a12teachingagent.course.dto.CourseDtos.CollaborationReferenceData;
import com.auvdidao.a12teachingagent.course.dto.CourseDtos.CourseRequest;
import com.auvdidao.a12teachingagent.course.dto.CourseDtos.CourseResponse;
import com.auvdidao.a12teachingagent.course.dto.CourseDtos.TeacherOption;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.course.ClassGroup;
import com.auvdidao.a12teachingagent.domain.course.ClassMembership;
import com.auvdidao.a12teachingagent.domain.course.Course;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassGroupRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassMembershipRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.CourseRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.UserRoleAssignment;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.identity.repository.UserRoleAssignmentRepository;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CourseService {

    private final CourseRepository courseRepository;
    private final ClassGroupRepository classGroupRepository;
    private final ClassMembershipRepository classMembershipRepository;
    private final AppUserRepository userRepository;
    private final UserRoleAssignmentRepository roleRepository;
    private final CurrentUserService currentUserService;

    public CourseService(
            CourseRepository courseRepository,
            ClassGroupRepository classGroupRepository,
            ClassMembershipRepository classMembershipRepository,
            AppUserRepository userRepository,
            UserRoleAssignmentRepository roleRepository,
            CurrentUserService currentUserService
    ) {
        this.courseRepository = courseRepository;
        this.classGroupRepository = classGroupRepository;
        this.classMembershipRepository = classMembershipRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public CourseResponse createCourse(CourseRequest request) {
        AuthenticatedUser leader = currentUserService.requireRole(UserRole.LEADER);
        String code = request.courseCode().trim();
        if (courseRepository.findByCourseCodeIgnoreCase(code).isPresent()) {
            throw new ConflictException("Course code already exists: " + code);
        }
        Course course = new Course();
        course.setCourseCode(code);
        course.setCourseName(request.courseName().trim());
        course.setDescription(trimToNull(request.description()));
        course.setCreatedBy(leader.userId());
        return toResponse(courseRepository.save(course));
    }

    @Transactional(readOnly = true)
    public List<CourseResponse> listCourses() {
        currentUserService.requireRole(UserRole.LEADER, UserRole.TEACHER, UserRole.STUDENT);
        return courseRepository.findAllByOrderByCourseNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public ClassGroupResponse createClassGroup(Long courseId, ClassGroupRequest request) {
        currentUserService.requireRole(UserRole.LEADER);
        Course course = requireCourse(courseId);
        String className = request.className().trim();
        if (classGroupRepository.findByCourseIdAndClassNameIgnoreCase(courseId, className).isPresent()) {
            throw new ConflictException("Class already exists in this course: " + className);
        }
        ClassGroup classGroup = new ClassGroup();
        classGroup.setCourseId(courseId);
        classGroup.setClassName(className);
        classGroup.setCohort(trimToNull(request.cohort()));
        classGroup.setStudentCount(request.studentCount() == null ? 0 : request.studentCount());
        return toClassResponse(classGroupRepository.save(classGroup), course.getCourseName());
    }

    @Transactional(readOnly = true)
    public List<ClassGroupResponse> listClasses(Long courseId) {
        currentUserService.requireRole(UserRole.LEADER, UserRole.TEACHER, UserRole.STUDENT);
        Map<Long, Course> courses = courseMap();
        List<ClassGroup> classes = courseId == null
                ? classGroupRepository.findAllByOrderByClassNameAsc()
                : classGroupRepository.findByCourseIdOrderByClassNameAsc(courseId);
        return classes.stream()
                .map(item -> toClassResponse(item, courseName(courses, item.getCourseId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CollaborationReferenceData referenceData() {
        currentUserService.requireRole(UserRole.LEADER, UserRole.TEACHER);
        List<TeacherOption> teachers = userOptions(UserRole.TEACHER);
        List<TeacherOption> leaders = userOptions(UserRole.LEADER);
        List<TeacherOption> students = userOptions(UserRole.STUDENT);
        return new CollaborationReferenceData(teachers, leaders, students, listCourseResponses(), listClassResponses());
    }

    @Transactional(readOnly = true)
    public List<ClassMembershipResponse> listClassMembers(Long classId) {
        currentUserService.requireRole(UserRole.LEADER);
        requireClass(classId);
        Map<Long, AppUser> users = new LinkedHashMap<>();
        List<ClassMembership> memberships = classMembershipRepository.findByClassId(classId);
        userRepository.findAllById(memberships.stream().map(ClassMembership::getStudentId).toList())
                .forEach(user -> users.put(user.getId(), user));
        return memberships.stream()
                .map(membership -> toMembershipResponse(membership, users.get(membership.getStudentId())))
                .toList();
    }

    @Transactional
    public ClassMembershipResponse addClassMember(Long classId, Long studentId) {
        currentUserService.requireRole(UserRole.LEADER);
        requireClass(classId);
        AppUser student = userRepository.findById(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found: " + studentId));
        if (!Boolean.TRUE.equals(student.getEnabled()) || !roleRepository.existsByUserIdAndRole(studentId, UserRole.STUDENT)) {
            throw new ConflictException("The selected user is not an enabled student");
        }
        if (classMembershipRepository.existsByClassIdAndStudentId(classId, studentId)) {
            throw new ConflictException("The student is already a member of this class");
        }
        ClassMembership membership = new ClassMembership();
        membership.setClassId(classId);
        membership.setStudentId(studentId);
        return toMembershipResponse(classMembershipRepository.save(membership), student);
    }

    @Transactional
    public void removeClassMember(Long classId, Long studentId) {
        currentUserService.requireRole(UserRole.LEADER);
        requireClass(classId);
        ClassMembership membership = classMembershipRepository.findByClassIdAndStudentId(classId, studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Class membership not found"));
        classMembershipRepository.delete(membership);
    }

    private List<TeacherOption> userOptions(UserRole role) {
        List<Long> userIds = roleRepository.findByRoleOrderByUserIdAsc(role)
                .stream()
                .map(UserRoleAssignment::getUserId)
                .distinct()
                .toList();
        Map<Long, AppUser> users = new LinkedHashMap<>();
        userRepository.findAllById(userIds).stream()
                .filter(user -> Boolean.TRUE.equals(user.getEnabled()))
                .forEach(user -> users.put(user.getId(), user));
        return userIds.stream()
                .map(users::get)
                .filter(java.util.Objects::nonNull)
                .map(user -> new TeacherOption(user.getId(), user.getUsername(), user.getDisplayName()))
                .toList();
    }

    Course requireCourse(Long courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course not found: " + courseId));
    }

    ClassGroup requireClass(Long classId) {
        return classGroupRepository.findById(classId)
                .orElseThrow(() -> new ResourceNotFoundException("Class not found: " + classId));
    }

    private List<CourseResponse> listCourseResponses() {
        return courseRepository.findAllByOrderByCourseNameAsc().stream().map(this::toResponse).toList();
    }

    private List<ClassGroupResponse> listClassResponses() {
        Map<Long, Course> courses = courseMap();
        return classGroupRepository.findAllByOrderByClassNameAsc().stream()
                .map(item -> toClassResponse(item, courseName(courses, item.getCourseId())))
                .toList();
    }

    private Map<Long, Course> courseMap() {
        Map<Long, Course> courses = new LinkedHashMap<>();
        courseRepository.findAll().forEach(course -> courses.put(course.getId(), course));
        return courses;
    }

    private String courseName(Map<Long, Course> courses, Long courseId) {
        Course course = courses.get(courseId);
        return course == null ? "Unknown course" : course.getCourseName();
    }

    private CourseResponse toResponse(Course course) {
        return new CourseResponse(
                course.getId(),
                course.getCourseCode(),
                course.getCourseName(),
                course.getDescription(),
                course.getCreatedBy(),
                course.getCreatedAt(),
                course.getUpdatedAt()
        );
    }

    private ClassGroupResponse toClassResponse(ClassGroup classGroup, String courseName) {
        return new ClassGroupResponse(
                classGroup.getId(),
                classGroup.getCourseId(),
                courseName,
                classGroup.getClassName(),
                classGroup.getCohort(),
                classGroup.getStudentCount(),
                classGroup.getCreatedAt(),
                classGroup.getUpdatedAt()
        );
    }

    private ClassMembershipResponse toMembershipResponse(ClassMembership membership, AppUser student) {
        if (student == null) {
            throw new ResourceNotFoundException("Student not found: " + membership.getStudentId());
        }
        return new ClassMembershipResponse(
                membership.getId(),
                membership.getClassId(),
                membership.getStudentId(),
                student.getUsername(),
                student.getDisplayName(),
                membership.getCreatedAt()
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
