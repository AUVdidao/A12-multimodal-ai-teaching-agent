package com.auvdidao.a12teachingagent.planning;

import com.auvdidao.a12teachingagent.agent.model.MockModelGateway;
import com.auvdidao.a12teachingagent.agent.model.ModelGateway;
import com.auvdidao.a12teachingagent.agent.model.ModelCredentialResolver;
import com.auvdidao.a12teachingagent.agent.model.ModelProvider;
import com.auvdidao.a12teachingagent.agent.model.ModelUsage;
import com.auvdidao.a12teachingagent.agent.model.KimiModelGateway;
import com.auvdidao.a12teachingagent.agent.model.StructuredModelResult;
import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.auvdidao.a12teachingagent.ai.connection.ModelConnectionProtocol;
import com.auvdidao.a12teachingagent.ai.connection.ResolvedModelConnection;
import com.auvdidao.a12teachingagent.ai.config.AiProvider;
import com.auvdidao.a12teachingagent.ai.config.AiWorkflowProperties;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.planning.PlanningAgentTrace;
import com.auvdidao.a12teachingagent.domain.planning.repository.PlanningAgentTraceRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileVersion;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningRequest;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedContentBlock;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedLayout;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedRegion;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedSlide;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningProposalDocument;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.ProposalOperation;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningResponse;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.SourceEvidence;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.TeachingContext;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.auvdidao.a12teachingagent.specification.PptSpecificationService;
import com.auvdidao.a12teachingagent.template.TemplateProfileContractGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlanningAgentIntegrationGateTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TemplateProfileVersionRepository profiles;
    private PptSpecificationService specifications;
    private PlanningAgentTraceRepository traces;
    private ProjectAccessService access;
    private CurrentUserService currentUser;
    private TemplateProfileContractGate profileGate;
    private ConfirmedTeachingContextService contextService;
    private PlanningTraceAuditService traceAudit;
    private ModelCredentialResolver credentialResolver;
    private KimiModelGateway connectionGateway;

    @BeforeEach
    void setUp() {
        profiles = mock(TemplateProfileVersionRepository.class);
        specifications = mock(PptSpecificationService.class);
        traces = mock(PlanningAgentTraceRepository.class);
        access = mock(ProjectAccessService.class);
        currentUser = mock(CurrentUserService.class);
        profileGate = mock(TemplateProfileContractGate.class);
        contextService = mock(ConfirmedTeachingContextService.class);
        traceAudit = mock(PlanningTraceAuditService.class);
        credentialResolver = mock(ModelCredentialResolver.class);
        connectionGateway = mock(KimiModelGateway.class);
        when(credentialResolver.resolveConnection(any())).thenThrow(new AiWorkflowUnavailableException("not configured"));
        when(traceAudit.start(anyLong(), anyString(), anyString(), any(), anyString(), anyString())).thenAnswer(invocation -> {
            PlanningAgentTrace trace = new PlanningAgentTrace();
            trace.setProjectId(invocation.getArgument(0));
            trace.setRunId(invocation.getArgument(1));
            trace.setTraceId(invocation.getArgument(2));
            trace.setOperation(invocation.getArgument(3));
            trace.setStatus(PlanningTraceStatus.RUNNING);
            trace.setCreatedAt(java.time.LocalDateTime.now());
            return trace;
        });
        when(traces.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(traces.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentUser.currentUser()).thenReturn(Optional.empty());
    }

    @Test
    void serverSnapshotReplacesClientConfirmedFlagAndWritesMockDraftOnlyInExplicitMockMode() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.MOCK);
        TemplateProfileVersion profile = profile();
        when(profiles.findByIdAndProjectId(9L, 9L)).thenReturn(Optional.of(profile));
        when(contextService.load(eq(9L), eq("intent:1:" + "a".repeat(64)))).thenReturn(snapshot());
        when(specifications.createPlanningProposal(eq(9L), any())).thenReturn(null);
        PlanningAgentService service = service(properties);

        PlanningRequest request = request(ProposalOperation.INITIAL_PROPOSAL, true);
        var response = service.createProposal(9L, request);

        assertThat(response.usedProvider()).isEqualTo("MOCK");
        assertThat(response.proposal().slides()).hasSize(2);
        verify(specifications).createPlanningProposal(eq(9L), any());
    }

    @Test
    void kimiWithoutAnEnabledStructuredRuntimeFailsClosedBeforeSpecificationWrite() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.KIMI);
        when(contextService.load(eq(9L), eq("intent:1:" + "a".repeat(64)))).thenReturn(snapshot());
        when(profiles.findByIdAndProjectId(9L, 9L)).thenReturn(Optional.of(profile()));
        PlanningAgentService service = service(properties);

        assertThatThrownBy(() -> service.createProposal(9L, request(ProposalOperation.INITIAL_PROPOSAL, true)))
                .isInstanceOf(com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException.class)
                .hasMessageContaining("not configured");
        verifyNoInteractions(specifications);
    }

    @Test
    void connectionGateFailuresReturnSafeFourXxWithoutTraceProposalOrGatewaySideEffects() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.KIMI);
        PlanningAgentService service = service(properties);
        List<RuntimeException> failures = List.of(
                new ResourceNotFoundException("MODEL_CONNECTION_NOT_FOUND"),
                new ResourceNotFoundException("MODEL_CONNECTION_NOT_FOUND"),
                new ConflictException("MODEL_CONNECTION_DISABLED"),
                new ConflictException("MODEL_CONNECTION_NOT_VERIFIED"),
                new ConflictException("MODEL_CONNECTION_CREDENTIAL_UNAVAILABLE")
        );

        for (RuntimeException failure : failures) {
            reset(credentialResolver, traceAudit, specifications, connectionGateway);
            when(credentialResolver.resolveConnection(any())).thenThrow(failure);
            assertThatThrownBy(() -> service.createProposal(9L, requestWithConnection(77L)))
                    .isSameAs(failure)
                    .hasMessageNotContaining("redacted");
            assertThat(failure.getMessage()).doesNotContain("Bearer", "Authorization");
            verifyNoInteractions(traceAudit, specifications, connectionGateway);
            verifyNoInteractions(traces);
        }
    }

    @Test
    void verifiedConnectionIsResolvedBeforeTraceAndThenGatewayMayRun() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.KIMI);
        when(currentUser.currentUser()).thenReturn(Optional.of(
                new com.auvdidao.a12teachingagent.security.AuthenticatedUser(1L, 100L, "teacher", "Teacher",
                        com.auvdidao.a12teachingagent.domain.common.UserRole.TEACHER)));
        when(contextService.load(eq(9L), anyString())).thenReturn(snapshot());
        when(profiles.findByIdAndProjectId(eq(9L), eq(9L))).thenReturn(Optional.of(profile()));
        when(specifications.createPlanningProposal(eq(9L), any())).thenReturn(null);
        ResolvedModelConnection connection = new ResolvedModelConnection(77L, 100L,
                ModelConnectionProtocol.OPENAI_COMPATIBLE, "https://example.com/v1", "teacher-model", "redacted-key");
        when(credentialResolver.resolveConnection(any())).thenReturn(connection);
        when(connectionGateway.completeStructuredWithConnection(any(), any(), any())).thenReturn(new StructuredModelResult<>(
                proposal(new PlannedContentBlock("block-1", "BODY", "阶段一", "TEACHER", "confirmed-source", true)),
                ModelProvider.OPENAI_COMPATIBLE, "teacher-model", "stub", ModelUsage.empty(), 0, false));

        PlanningResponse response = service(properties).createProposal(9L, requestWithConnection(77L));

        assertThat(response.usedProvider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(response.usedModel()).isEqualTo("teacher-model");
        org.mockito.InOrder order = inOrder(credentialResolver, traceAudit, connectionGateway);
        order.verify(credentialResolver).resolveConnection(any());
        order.verify(traceAudit).start(anyLong(), anyString(), anyString(), any(), anyString(), anyString());
        order.verify(connectionGateway).completeStructuredWithConnection(any(), any(), any());
    }

    @Test
    void patchIsExplicitlyUnsupportedAndDoesNotReadOrWriteAProposal() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.MOCK);
        PlanningAgentService service = service(properties);

        assertThatThrownBy(() -> service.createProposal(9L, request(ProposalOperation.PATCH, true)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("PATCH_NOT_SUPPORTED");
        verifyNoInteractions(contextService, specifications);
    }

    @Test
    void unauthenticatedDirectServiceCallFailsClosedBeforeContextOrTrace() {
        var projectRepository = mock(com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository.class);
        var strictAccess = new ProjectAccessService(currentUser, projectRepository);
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.MOCK);
        PlanningAgentService service = service(properties, new MockModelGateway(objectMapper), strictAccess);

        assertThatThrownBy(() -> service.createProposal(9L, request(ProposalOperation.INITIAL_PROPOSAL, true)))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.UnauthorizedException.class);
        assertThatThrownBy(() -> service.traces(9L, 20))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.UnauthorizedException.class);
        verifyNoInteractions(contextService, traces, traceAudit, specifications);
    }

    @Test
    void crossOwnerDirectServiceCallFailsClosedBeforeContextOrTrace() {
        var projectRepository = mock(com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository.class);
        when(currentUser.currentUser()).thenReturn(Optional.of(
                new com.auvdidao.a12teachingagent.security.AuthenticatedUser(1L, 200L, "other", "Other", com.auvdidao.a12teachingagent.domain.common.UserRole.TEACHER)));
        Project project = new Project();
        project.setId(9L);
        project.setOwnerUserId(100L);
        project.setStatus(ProjectStatus.CREATED);
        when(projectRepository.findById(9L)).thenReturn(Optional.of(project));
        var strictAccess = new ProjectAccessService(currentUser, projectRepository);
        PlanningAgentService service = service(new AiWorkflowProperties(), new MockModelGateway(objectMapper), strictAccess);

        assertThatThrownBy(() -> service.createProposal(9L, request(ProposalOperation.INITIAL_PROPOSAL, true)))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ForbiddenException.class);
        verifyNoInteractions(contextService, traces, traceAudit, specifications);
    }

    @Test
    void nonTeacherDirectServiceCallFailsClosedBeforeProjectLookup() {
        var projectRepository = mock(com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository.class);
        when(currentUser.currentUser()).thenReturn(Optional.of(
                new com.auvdidao.a12teachingagent.security.AuthenticatedUser(1L, 100L, "leader", "Leader", com.auvdidao.a12teachingagent.domain.common.UserRole.LEADER)));
        var strictAccess = new ProjectAccessService(currentUser, projectRepository);
        PlanningAgentService service = service(new AiWorkflowProperties(), new MockModelGateway(objectMapper), strictAccess);

        assertThatThrownBy(() -> service.createProposal(9L, request(ProposalOperation.INITIAL_PROPOSAL, true)))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ForbiddenException.class);
        verifyNoInteractions(projectRepository, contextService, traces, traceAudit, specifications);
    }

    @Test
    void nonTeacherSourceMustMatchConfirmedEvidenceAndProvenance() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.MOCK);
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.completeStructured(any(), any(), any())).thenReturn(new StructuredModelResult<>(
                malformedSourceProposal(), ModelProvider.MOCK, "mock-v1", "mock", ModelUsage.empty(), 0, false));
        when(contextService.load(eq(9L), anyString())).thenReturn(snapshot());
        when(profiles.findByIdAndProjectId(9L, 9L)).thenReturn(Optional.of(profile()));
        PlanningAgentService service = service(properties, gateway, access);

        assertThatThrownBy(() -> service.createProposal(9L, request(ProposalOperation.INITIAL_PROPOSAL, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("CONTENT_SOURCE_MISMATCH");
        verifyNoInteractions(specifications);
        verify(traceAudit).complete(eq(9L), anyString(), eq(PlanningTraceStatus.REJECTED), any(), any(), isNull(), anyString());
    }

    @Test
    void assetSourceMustMatchConfirmedEvidence() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.MOCK);
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.completeStructured(any(), any(), any())).thenReturn(new StructuredModelResult<>(
                malformedAssetProposal(), ModelProvider.MOCK, "mock-v1", "mock", ModelUsage.empty(), 0, false));
        when(contextService.load(eq(9L), anyString())).thenReturn(snapshot());
        when(profiles.findByIdAndProjectId(9L, 9L)).thenReturn(Optional.of(profile()));
        PlanningAgentService service = service(properties, gateway, access);

        assertThatThrownBy(() -> service.createProposal(9L, request(ProposalOperation.INITIAL_PROPOSAL, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ASSET_SOURCE_MISMATCH");
        verifyNoInteractions(specifications);
    }

    @Test
    void nestedMalformedStructuredOutputIsRejectedBeforeSpecificationWrite() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.MOCK);
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.completeStructured(any(), any(), any())).thenReturn(new StructuredModelResult<>(
                malformedNestedProposal(), ModelProvider.MOCK, "mock-v1", "mock", ModelUsage.empty(), 0, false));
        when(contextService.load(eq(9L), anyString())).thenReturn(snapshot());
        when(profiles.findByIdAndProjectId(9L, 9L)).thenReturn(Optional.of(profile()));
        PlanningAgentService service = service(properties, gateway, access);

        assertThatThrownBy(() -> service.createProposal(9L, request(ProposalOperation.INITIAL_PROPOSAL, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("slide nested fields");
        verifyNoInteractions(specifications);
    }

    @Test
    void planningSchemaRejectsMissingNestedFields() throws Exception {
        var schemaMethod = PlanningAgentService.class.getDeclaredMethod("planningProposalSchema");
        schemaMethod.setAccessible(true);
        var schema = (com.fasterxml.jackson.databind.JsonNode) schemaMethod.invoke(service(new AiWorkflowProperties()));
        var malformed = objectMapper.readTree("""
                {"contractVersion":"1.0.0","templateProfileId":"9","templateProfileVersion":1,
                 "templateCapabilityViewVersion":1,"templateCapabilityViewChecksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "targetSlideCount":1,"slideCountTolerance":0,"locale":"zh-CN","provider":"MOCK","model":"mock-v1",
                 "aiSupplementPolicy":"DISABLED","slides":[{}]}
                """);
        var errors = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(schema).validate(malformed);
        assertThat(errors).isNotEmpty();
    }

    @Test
    void runtimeProviderAndModelAreBoundFromGatewayResultNotModelDocument() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.MOCK);
        ModelGateway gateway = mock(ModelGateway.class);
        PlanningProposalDocument fixture = proposal(new PlannedContentBlock("block-1", "BODY", "阶段一", "TEACHER", "confirmed-source", true));
        PlanningProposalDocument spoofedDocument = new PlanningProposalDocument(fixture.contractVersion(), fixture.templateProfileId(),
                fixture.templateProfileVersion(), fixture.templateCapabilityViewVersion(), fixture.templateCapabilityViewChecksum(),
                fixture.targetSlideCount(), fixture.slideCountTolerance(), fixture.locale(), "KIMI", "forged-model",
                fixture.aiSupplementPolicy(), fixture.slides());
        when(gateway.completeStructured(any(), any(), any())).thenReturn(new StructuredModelResult<>(
                spoofedDocument, ModelProvider.MOCK, "mock-v1", "mock", ModelUsage.empty(), 0, false));
        when(contextService.load(eq(9L), anyString())).thenReturn(snapshot());
        when(profiles.findByIdAndProjectId(9L, 9L)).thenReturn(Optional.of(profile()));
        when(specifications.createPlanningProposal(eq(9L), any())).thenReturn(null);

        PlanningResponse response = service(properties, gateway, access)
                .createProposal(9L, request(ProposalOperation.INITIAL_PROPOSAL, true));

        assertThat(response.executionStatus()).isEqualTo("MOCK_FIXTURE");
        assertThat(response.usedProvider()).isEqualTo("MOCK");
        assertThat(response.usedModel()).isEqualTo("mock-v1");
        assertThat(response.proposal().provider()).isEqualTo("MOCK");
        assertThat(response.proposal().model()).isEqualTo("mock-v1");
        ArgumentCaptor<com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ProposalRequest> saved =
                ArgumentCaptor.forClass(com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ProposalRequest.class);
        verify(specifications).createPlanningProposal(eq(9L), saved.capture());
        assertThat(saved.getValue().specification().provider()).isEqualTo("MOCK");
        assertThat(saved.getValue().specification().model()).isEqualTo("mock-v1");
        verify(traceAudit).complete(eq(9L), anyString(), eq(PlanningTraceStatus.COMPLETED), eq("MOCK"), eq("mock-v1"), anyString(), isNull());
    }

    @Test
    void gatewayProviderMismatchFailsClosedBeforeSpecificationWrite() {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setProvider(AiProvider.MOCK);
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.completeStructured(any(), any(), any())).thenReturn(new StructuredModelResult<>(
                proposal(new PlannedContentBlock("block-1", "BODY", "阶段一", "TEACHER", "confirmed-source", true)),
                ModelProvider.KIMI, "kimi-k2", "kimi", ModelUsage.empty(), 0, false));
        when(contextService.load(eq(9L), anyString())).thenReturn(snapshot());
        when(profiles.findByIdAndProjectId(9L, 9L)).thenReturn(Optional.of(profile()));
        PlanningAgentService service = service(properties, gateway, access);

        assertThatThrownBy(() -> service.createProposal(9L, request(ProposalOperation.INITIAL_PROPOSAL, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("MODEL_OUTPUT_PROVIDER_MISMATCH");
        verifyNoInteractions(specifications);
        verify(traceAudit).complete(eq(9L), anyString(), eq(PlanningTraceStatus.REJECTED), any(), any(), isNull(), anyString());
    }

    @Test
    void planningSchemaRejectsUnknownTransformAndSemanticRole() throws Exception {
        var schemaMethod = PlanningAgentService.class.getDeclaredMethod("planningProposalSchema");
        schemaMethod.setAccessible(true);
        var schema = (com.fasterxml.jackson.databind.JsonNode) schemaMethod.invoke(service(new AiWorkflowProperties()));
        var transformSchema = schema.path("properties").path("slides").path("items").path("properties")
                .path("semanticLayout").path("properties").path("requestedTransform");
        assertThat(transformSchema.path("enum").toString()).contains("FIXED", "null");
        var roleSchema = schema.path("properties").path("slides").path("items").path("properties")
                .path("semanticLayout").path("properties").path("regions").path("items").path("properties")
                .path("semanticRole");
        assertThat(roleSchema.path("enum").toString()).contains("BODY");

        var malformed = objectMapper.valueToTree(proposal(new PlannedContentBlock("block-1", "BODY", "阶段一", "TEACHER", "confirmed-source", true)));
        var slide = (com.fasterxml.jackson.databind.node.ObjectNode) malformed.path("slides").get(0);
        var layout = (com.fasterxml.jackson.databind.node.ObjectNode) slide.path("semanticLayout");
        layout.put("requestedTransform", "FREEFORM");
        var region = (com.fasterxml.jackson.databind.node.ObjectNode) layout.path("regions").get(0);
        region.put("semanticRole", "UNTRUSTED_ROLE");
        var errors = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(schema).validate(malformed);
        assertThat(errors).isNotEmpty();
    }

    private PlanningAgentService service(AiWorkflowProperties properties) {
        return service(properties, new MockModelGateway(objectMapper), access);
    }

    private PlanningAgentService service(AiWorkflowProperties properties, ModelGateway gateway,
                                         ProjectAccessService accessService) {
        return new PlanningAgentService(profiles, specifications, traces, accessService, currentUser, objectMapper,
                gateway, credentialResolver, properties, new KimiAssistantProperties(), profileGate, contextService, traceAudit, connectionGateway);
    }

    private PlanningProposalDocument malformedSourceProposal() {
        return proposal(new PlannedContentBlock("block-1", "BODY", "未确认的新事实", "MATERIAL", "forged-source", true));
    }

    private PlanningProposalDocument malformedNestedProposal() {
        return new PlanningProposalDocument("1.0.0", "9", 1, 1, profile().getCapabilityViewChecksum(), 2, 0,
                "zh-CN", "MOCK", "mock-v1", "DISABLED", List.of(
                new PlannedSlide("slide-1", 1, "光合作用", "理解过程", null, null, null, null, ""),
                new PlannedSlide("slide-2", 2, "阶段一", "说明条件", new PlannedLayout("正文", List.of(new PlannedRegion("region-2", "BODY", "CENTER", 2)), null),
                        List.of(), List.of(), List.of(), "")));
    }

    private PlanningProposalDocument malformedAssetProposal() {
        PlanningProposalDocument base = proposal(new PlannedContentBlock("block-1", "BODY", "阶段一", "TEACHER", "confirmed-source", true));
        PlannedSlide content = base.slides().get(1);
        PlannedSlide withAsset = new PlannedSlide(content.slideId(), content.pageNumber(), content.title(), content.teachingGoal(),
                content.semanticLayout(), content.contentBlocks(),
                List.of(new PlanningDtos.PlannedAssetRequirement("asset-1", "OTHER", "forged-asset", "PENDING", true, "OTHER")),
                content.provenance(), content.notes());
        return new PlanningProposalDocument(base.contractVersion(), base.templateProfileId(), base.templateProfileVersion(),
                base.templateCapabilityViewVersion(), base.templateCapabilityViewChecksum(), base.targetSlideCount(), base.slideCountTolerance(),
                base.locale(), base.provider(), base.model(), base.aiSupplementPolicy(), List.of(base.slides().get(0), withAsset));
    }

    private PlanningProposalDocument proposal(PlannedContentBlock block) {
        PlannedSlide cover = new PlannedSlide("slide-1", 1, "光合作用", "理解过程",
                new PlannedLayout("正文", List.of(new PlannedRegion("region-1", "BODY", "CENTER", 2)), null),
                List.of(new PlannedContentBlock("cover", "TITLE", "光合作用", "TEACHER", "confirmed-context:" + "a".repeat(64), true)),
                List.of(), List.of(new com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedProvenance("TEACHER", "confirmed-context:" + "a".repeat(64))), "");
        PlannedSlide content = new PlannedSlide("slide-2", 2, "阶段一", "说明条件",
                new PlannedLayout("正文", List.of(new PlannedRegion("region-2", "BODY", "CENTER", 2)), null),
                List.of(block), List.of(), List.of(new com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedProvenance(block.sourceType(), block.sourceReference())), "");
        return new PlanningProposalDocument("1.0.0", "9", 1, 1, profile().getCapabilityViewChecksum(), 2, 0,
                "zh-CN", "MOCK", "mock-v1", "DISABLED", List.of(cover, content));
    }

    private TemplateProfileVersion profile() {
        TemplateProfileVersion profile = new TemplateProfileVersion();
        profile.setId(9L); profile.setProjectId(9L); profile.setTemplateId(7L); profile.setVersionNumber(1);
        profile.setCapabilityViewVersion(1); profile.setStatus(TemplateProfileStatus.READY);
        String view = "{\"displayName\":\"课堂模板\",\"pageRoles\":[\"封面\",\"正文\"],\"semanticLayouts\":[{\"name\":\"正文\",\"description\":\"两项\",\"minCapacity\":1,\"maxCapacity\":2}],\"imageCapability\":{\"supported\":false,\"minCount\":0,\"maxCount\":0},\"tableCapability\":{\"supported\":false,\"minCount\":0,\"maxCount\":0},\"chartCapability\":{\"supported\":false,\"minCount\":0,\"maxCount\":0},\"fixedBrandAreas\":[],\"limitations\":[]}";
        profile.setCapabilityViewJson(view); profile.setCapabilityViewChecksum(sha256(view));
        profile.setProfileJson("{}"); profile.setChecksum(sha256("{}")); profile.setConfirmedChecksum(profile.getChecksum());
        return profile;
    }

    private ConfirmedTeachingContextService.Snapshot snapshot() {
        TeachingContext context = new TeachingContext("生物", "光合作用", List.of("理解过程", "说明条件"), List.of("阶段一"), List.of(), true);
        return new ConfirmedTeachingContextService.Snapshot("intent:1:" + "a".repeat(64), "a".repeat(64), "{\"context\":true}", context, List.of(new SourceEvidence("confirmed-source", "CONFIRMED_TEACHING_INTENT", "阶段一")));
    }

    private PlanningRequest request(ProposalOperation operation, boolean trigger) {
        return new PlanningRequest(7L, 9L, operation, null, null, "intent:1:" + "a".repeat(64), 2, 0, "zh-CN",
                new TeachingContext("client", "client", List.of("client"), List.of("client"), List.of(), false), List.of(), null, trigger);
    }

    private PlanningRequest requestWithConnection(Long connectionId) {
        return new PlanningRequest(7L, 9L, ProposalOperation.INITIAL_PROPOSAL, null, null,
                "intent:1:" + "a".repeat(64), 2, 0, "zh-CN",
                new TeachingContext("client", "client", List.of("client"), List.of("client"), List.of(), false),
                List.of(), null, true, connectionId);
    }

    private static String sha256(String value) {
        try { byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte item : digest) result.append(String.format("%02x", item)); return result.toString(); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }
}
