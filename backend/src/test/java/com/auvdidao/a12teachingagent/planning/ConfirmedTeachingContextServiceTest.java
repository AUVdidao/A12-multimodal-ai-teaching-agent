package com.auvdidao.a12teachingagent.planning;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntentEvidence;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningRequest;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.ProposalOperation;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.SourceEvidence;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.TeachingContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfirmedTeachingContextServiceTest {

    @Test
    void currentReferenceIsDerivedFromConfirmedServerRecordsAndCanBeLoaded() {
        Fixture fixture = fixture();
        ConfirmedTeachingContextService service = fixture.service();

        ConfirmedTeachingContextService.ConfirmedContextReference reference = service.currentReference(9L);
        ConfirmedTeachingContextService.Snapshot loaded = service.load(9L, reference.revision());

        assertThat(reference.checksum()).hasSize(64).isEqualTo(loaded.checksum());
        assertThat(reference.revision()).startsWith("intent:12:");
        assertThat(reference.confirmedPageCount()).isEqualTo(3);
        assertThat(reference.confirmedPageOutline()).extracting(ConfirmedTeachingContextService.ConfirmedPageOutlineItem::pageNumber)
                .containsExactly(2, 3);
        assertThat(loaded.canonicalJson()).contains("\"outlineSource\":\"SERVER_CONFIRMED_PAGE_OUTLINE_V1\"");
        assertThat(loaded.canonicalJson()).contains("\"confirmedPageCount\":3");
        assertThat(loaded.context().courseName()).isEqualTo("生物");
        assertThat(loaded.evidence()).containsExactly(
                new SourceEvidence("material:88:chunk:99", "CONFIRMED_TEACHING_INTENT", "光合作用证据"));
    }

    @Test
    void forgedLegacyContextIsRejectedBeforePlanningCanCreateTrace() {
        Fixture fixture = fixture();
        ConfirmedTeachingContextService service = fixture.service();
        ConfirmedTeachingContextService.Snapshot snapshot = service.load(9L, service.currentReference(9L).revision());
        PlanningRequest forged = new PlanningRequest(
                7L, 8L, ProposalOperation.INITIAL_PROPOSAL, null, null, snapshot.revision(),
                2, 0, "zh-CN",
                new TeachingContext("伪造课程", snapshot.context().topic(), snapshot.context().teachingObjectives(),
                        snapshot.context().outline(), snapshot.context().lessonPlan(), true),
                snapshot.evidence(), null, true);

        assertThatThrownBy(() -> service.validateRequestBinding(forged, snapshot))
                .isInstanceOf(ConflictException.class)
                .hasMessage("CONFIRMED_CONTEXT_REQUEST_MISMATCH");
    }

    @Test
    void serverSnapshotAcceptsOnlyTheEquivalentLegacyWireFields() {
        Fixture fixture = fixture();
        ConfirmedTeachingContextService service = fixture.service();
        ConfirmedTeachingContextService.Snapshot snapshot = service.load(9L, service.currentReference(9L).revision());
        PlanningRequest bound = new PlanningRequest(
                7L, 8L, ProposalOperation.INITIAL_PROPOSAL, null, null, snapshot.revision(),
                2, 0, "zh-CN", snapshot.context(), snapshot.evidence(), null, true);

        service.validateRequestBinding(bound, snapshot);
    }

    private static Fixture fixture() {
        ProjectRepository projects = mock(ProjectRepository.class);
        RequirementSummaryRepository summaries = mock(RequirementSummaryRepository.class);
        TeachingIntentRepository intents = mock(TeachingIntentRepository.class);

        Project project = new Project();
        project.setId(9L);
        project.setOwnerUserId(100L);
        project.setCourseName("生物");

        RequirementSummary summary = new RequirementSummary();
        summary.setId(22L);
        summary.setProjectId(9L);
        summary.setTopic("光合作用");
        summary.setStatus(RequirementSummaryStatus.CONFIRMED);
        summary.setUpdatedAt(LocalDateTime.of(2026, 8, 29, 10, 0));

        TeachingIntent intent = new TeachingIntent();
        intent.setId(12L);
        intent.setProjectId(9L);
        intent.setRequirementSummaryId(22L);
        intent.setStatus(TeachingIntentStatus.CONFIRMED);
        intent.setUpdatedAt(LocalDateTime.of(2026, 8, 29, 10, 1));
        intent.setConfirmedAt(LocalDateTime.of(2026, 8, 29, 10, 2));
        intent.setGenerationGoals(List.of("理解过程", "说明条件"));
        intent.setTeachingApproach("实验观察");
        TeachingIntentEvidence evidence = new TeachingIntentEvidence();
        evidence.setMaterialId(88L);
        evidence.setKnowledgeChunkId(99L);
        evidence.setContentExcerpt("光合作用证据");
        intent.setEvidenceItems(List.of(evidence));

        when(projects.findById(9L)).thenReturn(Optional.of(project));
        when(intents.findById(12L)).thenReturn(Optional.of(intent));
        when(intents.findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(9L, TeachingIntentStatus.CONFIRMED))
                .thenReturn(Optional.of(intent));
        when(summaries.findById(22L)).thenReturn(Optional.of(summary));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new Fixture(new ConfirmedTeachingContextService(projects, summaries, intents, mapper));
    }

    private record Fixture(ConfirmedTeachingContextService service) { }
}
