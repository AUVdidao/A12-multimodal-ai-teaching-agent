package com.auvdidao.a12teachingagent.domain;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ProjectRepositoryTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void savesAndFindsProjectWithCoreFields() {
        Project project = new Project();
        project.setProjectName("五年级科学课件");
        project.setCourseName("科学");
        project.setChapterTopic("水循环");
        project.setTargetAudience("五年级学生");
        project.setLessonDurationMinutes(40);
        project.setGenerationMode(GenerationMode.MOCK);
        project.setStatus(ProjectStatus.CREATED);

        Project saved = projectRepository.save(project);

        Optional<Project> found = projectRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getCourseName()).isEqualTo("科学");
        assertThat(found.get().getChapterTopic()).isEqualTo("水循环");
        assertThat(found.get().getGenerationMode()).isEqualTo(GenerationMode.MOCK);
        assertThat(found.get().getStatus()).isEqualTo(ProjectStatus.CREATED);
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();

        List<Project> createdProjects = projectRepository.findByStatus(ProjectStatus.CREATED);
        assertThat(createdProjects).extracting(Project::getProjectName).contains("五年级科学课件");
    }
}
