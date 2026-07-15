package com.auvdidao.a12teachingagent.auth;

import com.auvdidao.a12teachingagent.domain.common.TaskPriority;
import com.auvdidao.a12teachingagent.domain.common.TeachingTaskStatus;
import com.auvdidao.a12teachingagent.domain.course.ClassGroup;
import com.auvdidao.a12teachingagent.domain.course.ClassMembership;
import com.auvdidao.a12teachingagent.domain.course.Course;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassGroupRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassMembershipRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.CourseRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.teachingtask.TeachingTask;
import com.auvdidao.a12teachingagent.domain.teachingtask.repository.TeachingTaskRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.security.A12SecurityProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@Order(20)
public class DemoCollaborationSeeder implements ApplicationRunner {

    private static final String DEMO_COURSE_CODE = "A12-AI-FOUNDATIONS";
    private static final String DEMO_CLASS_NAME = "Computer Science Class 1";
    private static final String DEMO_TASK_NAME = "Complete AI foundations teaching design";

    private final A12SecurityProperties properties;
    private final AppUserRepository userRepository;
    private final CourseRepository courseRepository;
    private final ClassGroupRepository classGroupRepository;
    private final ClassMembershipRepository membershipRepository;
    private final TeachingTaskRepository taskRepository;
    private final ProjectRepository projectRepository;

    public DemoCollaborationSeeder(
            A12SecurityProperties properties,
            AppUserRepository userRepository,
            CourseRepository courseRepository,
            ClassGroupRepository classGroupRepository,
            ClassMembershipRepository membershipRepository,
            TeachingTaskRepository taskRepository,
            ProjectRepository projectRepository
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.classGroupRepository = classGroupRepository;
        this.membershipRepository = membershipRepository;
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isDemoSeedEnabled()) {
            return;
        }
        AppUser leader = userRepository.findByUsernameIgnoreCase("leader").orElse(null);
        AppUser teacher = userRepository.findByUsernameIgnoreCase("teacher").orElse(null);
        AppUser student = userRepository.findByUsernameIgnoreCase("student").orElse(null);
        if (leader == null || teacher == null || student == null) {
            return;
        }

        projectRepository.findAll().stream()
                .filter(project -> project.getOwnerUserId() == null)
                .forEach(project -> project.setOwnerUserId(teacher.getId()));

        Course course = courseRepository.findByCourseCodeIgnoreCase(DEMO_COURSE_CODE).orElseGet(() -> {
            Course created = new Course();
            created.setCourseCode(DEMO_COURSE_CODE);
            created.setCourseName("Artificial Intelligence Foundations");
            created.setDescription("Demo course for the collaboration workflow");
            created.setCreatedBy(leader.getId());
            return courseRepository.save(created);
        });

        ClassGroup classGroup = classGroupRepository
                .findByCourseIdAndClassNameIgnoreCase(course.getId(), DEMO_CLASS_NAME)
                .orElseGet(() -> {
                    ClassGroup created = new ClassGroup();
                    created.setCourseId(course.getId());
                    created.setClassName(DEMO_CLASS_NAME);
                    created.setCohort("2026");
                    created.setStudentCount(36);
                    return classGroupRepository.save(created);
                });

        if (!membershipRepository.existsByClassIdAndStudentId(classGroup.getId(), student.getId())) {
            ClassMembership membership = new ClassMembership();
            membership.setClassId(classGroup.getId());
            membership.setStudentId(student.getId());
            membershipRepository.save(membership);
        }

        if (!taskRepository.existsByCreatedByAndTaskNameIgnoreCase(leader.getId(), DEMO_TASK_NAME)) {
            TeachingTask task = new TeachingTask();
            task.setTaskName(DEMO_TASK_NAME);
            task.setCourseId(course.getId());
            task.setClassId(classGroup.getId());
            task.setChapterTitle("Core concepts and applications");
            task.setAssigneeId(teacher.getId());
            task.setRequirements("Prepare requirements, references, teaching intent and generated learning materials.");
            task.setPriority(TaskPriority.HIGH);
            task.setDueAt(LocalDateTime.now().plusDays(7));
            task.setCreatedBy(leader.getId());
            task.setTaskStatus(TeachingTaskStatus.ASSIGNED);
            taskRepository.save(task);
        }
    }
}
