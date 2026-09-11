package com.auvdidao.a12teachingagent.planning;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.planning.repository.PlanningAgentTraceRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.ProposalOperation;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class PlanningTraceAuditServiceIntegrationTest {
    @Autowired private PlanningTraceAuditService auditService;
    @Autowired private PlanningAgentTraceRepository traceRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private CurrentUserService currentUserService;

    @BeforeEach
    void authenticateOwner() {
        when(currentUserService.currentUser()).thenReturn(Optional.of(
                new AuthenticatedUser(1L, 100L, "fixture-teacher", "Fixture Teacher", UserRole.TEACHER)));
    }

    @Test
    void runningTraceSurvivesOuterProposalRollback() {
        Project project = new Project();
        project.setProjectName("trace-fixture");
        project.setCourseName("数学");
        project.setChapterTopic("函数");
        project.setTargetAudience("高中");
        project.setOwnerUserId(100L);
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.CREATED);
        project = projectRepository.saveAndFlush(project);
        String traceId = "trace-rollback-test";

        Project finalProject = project;
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            auditService.start(finalProject.getId(), "run-rollback-test", traceId,
                    ProposalOperation.INITIAL_PROPOSAL, "MOCK", "a".repeat(64));
            throw new IllegalStateException("simulate proposal rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(traceRepository.findByTraceId(traceId)).isPresent()
                .get().satisfies(trace -> assertThat(trace.getStatus()).isEqualTo(PlanningTraceStatus.RUNNING));
    }
}
