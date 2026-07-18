package com.auvdidao.a12teachingagent.auth;

import com.auvdidao.a12teachingagent.domain.approval.repository.ApprovalRequestRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.publication.repository.PublicationRepository;
import com.auvdidao.a12teachingagent.domain.qa.repository.QuestionRepository;
import com.auvdidao.a12teachingagent.domain.teachingtask.repository.TeachingTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "a12.security.demo-seed-enabled=true",
        "a12.security.demo.leader-password=leader123",
        "a12.security.demo.teacher-password=teacher123",
        "a12.security.demo.student-password=student123",
        "a12.security.demo.multi-password=multi123"
})
@ActiveProfiles("test")
@DirtiesContext
class DemoShowcaseSeederTest {

    @Autowired
    private DemoShowcaseSeeder seeder;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UploadedMaterialRepository materialRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private TeachingIntentRepository teachingIntentRepository;

    @Autowired
    private GeneratedArtifactRepository artifactRepository;

    @Autowired
    private TeachingTaskRepository taskRepository;

    @Autowired
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private PublicationRepository publicationRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Test
    void seedsCompleteCrossRoleDemoDataAndRemainsIdempotent() {
        AppUser teacher = userRepository.findByUsernameIgnoreCase("teacher").orElseThrow();
        Set<String> names = projectRepository
                .findByOwnerUserIdOrderByUpdatedAtDescCreatedAtDesc(teacher.getId())
                .stream()
                .map(Project::getProjectName)
                .collect(Collectors.toSet());

        assertThat(names).contains(
                "人工智能基础概念与应用",
                "高中物理：电磁感应专题",
                "初中生物：细胞的结构",
                "高中数学：立体几何",
                "初中化学：氧化还原反应",
                "小学英语：日常交际用语"
        );
        assertThat(materialRepository.count()).isGreaterThanOrEqualTo(8);
        assertThat(knowledgeChunkRepository.count()).isGreaterThanOrEqualTo(24);
        assertThat(teachingIntentRepository.count()).isGreaterThanOrEqualTo(3);
        assertThat(artifactRepository.count()).isGreaterThanOrEqualTo(6);
        assertThat(taskRepository.count()).isGreaterThanOrEqualTo(5);
        assertThat(approvalRepository.count()).isEqualTo(2);
        assertThat(publicationRepository.count()).isEqualTo(1);
        assertThat(questionRepository.count()).isEqualTo(3);

        Counts before = counts();
        seeder.run(null);

        assertThat(counts()).isEqualTo(before);
    }

    private Counts counts() {
        return new Counts(
                projectRepository.count(),
                materialRepository.count(),
                knowledgeChunkRepository.count(),
                teachingIntentRepository.count(),
                artifactRepository.count(),
                taskRepository.count(),
                approvalRepository.count(),
                publicationRepository.count(),
                questionRepository.count()
        );
    }

    private record Counts(
            long projects,
            long materials,
            long chunks,
            long intents,
            long artifacts,
            long tasks,
            long approvals,
            long publications,
            long questions
    ) {
    }
}
