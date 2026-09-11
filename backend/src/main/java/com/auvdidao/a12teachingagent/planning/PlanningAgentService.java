package com.auvdidao.a12teachingagent.planning;

import com.auvdidao.a12teachingagent.agent.model.ModelExecutionContext;
import com.auvdidao.a12teachingagent.agent.model.ModelGateway;
import com.auvdidao.a12teachingagent.agent.model.ModelFailureException;
import com.auvdidao.a12teachingagent.agent.model.ModelMessage;
import com.auvdidao.a12teachingagent.agent.model.ModelProvider;
import com.auvdidao.a12teachingagent.agent.model.ModelRequest;
import com.auvdidao.a12teachingagent.agent.model.KimiModelGateway;
import com.auvdidao.a12teachingagent.agent.model.ResolvedModelCredential;
import com.auvdidao.a12teachingagent.ai.connection.ResolvedModelConnection;
import com.auvdidao.a12teachingagent.agent.model.StructuredModelResult;
import com.auvdidao.a12teachingagent.agent.model.StructuredOutputContract;
import com.auvdidao.a12teachingagent.ai.config.AiProvider;
import com.auvdidao.a12teachingagent.ai.config.AiWorkflowProperties;
import com.auvdidao.a12teachingagent.ai.credential.AiApiCredentialService;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.ai.assistant.KimiAssistantProperties;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.planning.PlanningAgentTrace;
import com.auvdidao.a12teachingagent.domain.planning.repository.PlanningAgentTraceRepository;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileVersion;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedContentBlock;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedLayout;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedProvenance;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedRegion;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlannedSlide;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningProposalDocument;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningRequest;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningResponse;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.SourceEvidence;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.TraceResponse;
import com.auvdidao.a12teachingagent.specification.PptSpecificationService;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.AiSupplementPolicyValue;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ContentBlockWrite;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.AssetRequirementWrite;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ProposalRequest;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ProposalOperation;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.ProvenanceWrite;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SemanticLayout;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SemanticRegion;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SlideWrite;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SpecificationResponse;
import com.auvdidao.a12teachingagent.specification.dto.PptSpecificationDtos.SpecificationWriteRequest;
import com.auvdidao.a12teachingagent.template.TemplateDtos;
import com.auvdidao.a12teachingagent.template.TemplateProfileContractGate;
import com.auvdidao.a12teachingagent.template.TemplateDtos.CapabilityViewResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.auvdidao.a12teachingagent.planning.PlanningDtos.ProposalOperation.INITIAL_PROPOSAL;

@Service
public class PlanningAgentService {
    private static final String CONTRACT_VERSION = "1.0.0";
    private static final Set<String> TRANSFORMS = Set.of("FIXED", "TRANSLATE_ONLY", "UNIFORM_SCALE", "STRETCH_X", "STRETCH_Y", "RESPONSIVE");
    private static final Set<String> SEMANTIC_ROLES = Set.of(
            "COVER", "TITLE", "HEADER", "BODY", "CONTENT", "LIST", "QUOTE", "SIDEBAR", "FOOTER",
            "IMAGE", "TABLE", "CHART", "DECORATION", "FULL_BLEED"
    );
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "component", "components", "slot", "slots", "shape", "shapes", "group", "groups",
            "coordinate", "coordinates", "x", "y", "cx", "cy", "oxml", "xml", "nativeobjectref"
    );

    private final TemplateProfileVersionRepository profileRepository;
    private final PptSpecificationService specificationService;
    private final PlanningAgentTraceRepository traceRepository;
    private final com.auvdidao.a12teachingagent.security.ProjectAccessService projectAccessService;
    private final com.auvdidao.a12teachingagent.security.CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final ModelGateway modelGateway;
    private final com.auvdidao.a12teachingagent.agent.model.ModelCredentialResolver credentialResolver;
    private final AiWorkflowProperties aiProperties;
    private final KimiAssistantProperties kimiProperties;
    private final TemplateProfileContractGate profileContractGate;
    private final ConfirmedTeachingContextService confirmedContextService;
    private final PlanningTraceAuditService traceAuditService;
    private final KimiModelGateway kimiModelGateway;

    public PlanningAgentService(
            TemplateProfileVersionRepository profileRepository,
            PptSpecificationService specificationService,
            PlanningAgentTraceRepository traceRepository,
            com.auvdidao.a12teachingagent.security.ProjectAccessService projectAccessService,
            com.auvdidao.a12teachingagent.security.CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            ModelGateway modelGateway,
            com.auvdidao.a12teachingagent.agent.model.ModelCredentialResolver credentialResolver,
            AiWorkflowProperties aiProperties,
            KimiAssistantProperties kimiProperties,
            TemplateProfileContractGate profileContractGate,
            ConfirmedTeachingContextService confirmedContextService,
            PlanningTraceAuditService traceAuditService,
            KimiModelGateway kimiModelGateway
    ) {
        this.profileRepository = profileRepository;
        this.specificationService = specificationService;
        this.traceRepository = traceRepository;
        this.projectAccessService = projectAccessService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.modelGateway = modelGateway;
        this.credentialResolver = credentialResolver;
        this.aiProperties = aiProperties;
        this.kimiProperties = kimiProperties;
        this.profileContractGate = profileContractGate;
        this.confirmedContextService = confirmedContextService;
        this.traceAuditService = traceAuditService;
        this.kimiModelGateway = kimiModelGateway;
    }

    @Transactional(readOnly = true)
    public CapabilityViewResponse capabilityView(Long projectId, Long templateId, Long profileVersionId) {
        projectAccessService.requireAuthenticatedTeacherAccess(projectId);
        TemplateProfileVersion profile = findReadyProfile(projectId, templateId, profileVersionId);
        return safeCapabilityView(profile);
    }

    @Transactional(readOnly = true)
    public ConfirmedTeachingContextService.ConfirmedContextReference confirmedContextReference(Long projectId) {
        projectAccessService.requireAuthenticatedTeacherAccess(projectId);
        return confirmedContextService.currentReference(projectId);
    }

    @Transactional
    public PlanningResponse createProposal(Long projectId, PlanningRequest request) {
        projectAccessService.requireAuthenticatedTeacherAccess(projectId);
        if (request == null) throw new BadRequestException("PLANNING_REQUEST_REQUIRED");
        if (aiProperties.getProvider() != AiProvider.MOCK && request.modelConnectionId() == null) {
            throw new BadRequestException("MODEL_CONNECTION_REQUIRED: Planning must explicitly bind a modelConnectionId");
        }
        if (!request.explicitTeacherTrigger()) {
            throw new BadRequestException("PLANNING_TRIGGER_REQUIRED: an explicit teacher trigger is required");
        }
        if (request.operation() == PlanningDtos.ProposalOperation.PATCH) {
            throw new BadRequestException("PATCH_NOT_SUPPORTED: Planning Patch is not implemented; no DRAFT was written");
        }
        ConfirmedTeachingContextService.Snapshot confirmedContext = confirmedContextService.load(projectId, request.confirmedContextVersion());
        confirmedContextService.validateRequestBinding(request, confirmedContext);
        String runId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        ResolvedModelConnection resolvedConnection = aiProperties.getProvider() == AiProvider.MOCK
                ? null : credentialResolver.resolveConnection(context(projectId, runId, traceId, request.modelConnectionId()));
        PlanningAgentTrace trace = startTrace(projectId, request, confirmedContext, runId, traceId);
        CapabilityViewResponse capability = null;
        try {
            TemplateProfileVersion profile = findReadyProfile(projectId, request.templateId(), request.templateProfileVersionId());
            capability = safeCapabilityView(profile);
            traceAuditService.setCapabilityChecksum(projectId, traceId, capability.checksum());
            PlanningProposalDocument proposal = buildProposal(projectId, request, confirmedContext, profile, capability, runId, traceId);
            StructuredPlanningResult modelResult = runStructuredBoundary(projectId, request, proposal, runId, traceId, resolvedConnection);
            PlanningProposalDocument modelOutput = modelResult.proposal();
            validateProposal(projectId, request, confirmedContext, profile, capability, modelOutput,
                    modelResult.provider(), modelResult.model());
            SpecificationWriteRequest specificationWrite = toSpecificationWrite(modelOutput);
            ProposalOperation operation = toSpecificationOperation(request.operation());
            SpecificationResponse saved = specificationService.createPlanningProposal(projectId,
                    new ProposalRequest(request.baseSpecificationVersion(), request.baseSpecificationChecksum(), operation, specificationWrite));
            completeTrace(trace, PlanningTraceStatus.COMPLETED, modelResult.provider().name(), modelResult.model(),
                    sha256(objectMapper.writeValueAsString(modelOutput)), null);
            return new PlanningResponse(runId, traceId, executionStatus(modelResult.provider()), requestedProvider(),
                    modelResult.provider().name(), modelResult.model(),
                    null, capability, saved, modelOutput, trace.getCreatedAt());
        } catch (RuntimeException exception) {
            String reason = rejectionReason(exception);
            completeTrace(trace, PlanningTraceStatus.REJECTED, trace.getUsedProvider(), trace.getUsedModel(), null, reason);
            throw exception;
        } catch (JsonProcessingException exception) {
            completeTrace(trace, PlanningTraceStatus.REJECTED, trace.getUsedProvider(), trace.getUsedModel(), null, "OUTPUT_SERIALIZATION_FAILED");
            throw new BadRequestException("Planning proposal could not be serialized");
        }
    }

    @Transactional(readOnly = true)
    public List<TraceResponse> traces(Long projectId, int limit) {
        projectAccessService.requireAuthenticatedTeacherAccess(projectId);
        if (limit < 1 || limit > 100) throw new BadRequestException("limit must be between 1 and 100");
        return traceRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectId, PageRequest.of(0, limit))
                .stream().map(this::toTrace).toList();
    }

    private PlanningAgentTrace startTrace(Long projectId, PlanningRequest request,
                                          ConfirmedTeachingContextService.Snapshot confirmedContext,
                                          String runId, String traceId) {
        return traceAuditService.start(projectId, runId, traceId, request.operation(), requestedProvider(),
                inputChecksum(request, confirmedContext));
    }

    private TemplateProfileVersion findReadyProfile(Long projectId, Long templateId, Long profileVersionId) {
        TemplateProfileVersion profile = profileRepository.findByIdAndProjectId(profileVersionId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Template profile version not found: " + profileVersionId));
        if (!profile.getTemplateId().equals(templateId)) throw new ResourceNotFoundException("Template profile does not belong to the template");
        if (profile.getStatus() != TemplateProfileStatus.READY
                && profile.getStatus() != TemplateProfileStatus.CONFIRMED) {
            throw new ConflictException("Planning Agent requires a READY or CONFIRMED Template Profile");
        }
        profileContractGate.requirePlanningBinding(projectId, templateId, profileVersionId);
        return profile;
    }

    private CapabilityViewResponse safeCapabilityView(TemplateProfileVersion profile) {
        String actualChecksum = sha256(profile.getCapabilityViewJson());
        if (!actualChecksum.equalsIgnoreCase(profile.getCapabilityViewChecksum())) {
            throw new ConflictException("Template Capability View checksum is stale");
        }
        try {
            JsonNode node = objectMapper.readTree(profile.getCapabilityViewJson());
            if (containsForbiddenField(node) || containsUnknownCapabilityField(node, "")) throw new BadRequestException("Capability View contains unsupported or execution-layer fields");
            TemplateDtos.CandidateProfileRequest projection = objectMapper.treeToValue(node, TemplateDtos.CandidateProfileRequest.class);
            if (!StringUtils.hasText(projection.displayName()) || projection.semanticLayouts() == null) {
                throw new BadRequestException("Capability View is incomplete");
            }
            return new CapabilityViewResponse(profile.getId(), profile.getVersionNumber(), profile.getCapabilityViewChecksum(),
                    projection.displayName(), projection.pageRoles(), projection.semanticLayouts(), projection.imageCapability(),
                    projection.tableCapability(), projection.chartCapability(), projection.fixedBrandAreas(), projection.limitations());
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("Capability View is not valid JSON");
        }
    }

    private PlanningProposalDocument buildProposal(Long projectId, PlanningRequest request,
                                                   ConfirmedTeachingContextService.Snapshot confirmedContext,
                                                   TemplateProfileVersion profile,
                                                   CapabilityViewResponse capability, String runId, String traceId) {
        PlanningDtos.TeachingContext context = confirmedContext.context();
        int serverPageCount = confirmedContext.confirmedPageCount();
        if (request.targetSlideCount() != serverPageCount) {
            throw new ConflictException("PAGE_COUNT_MISMATCH: targetSlideCount must equal the server-confirmed page count; no split or merge is performed");
        }
        if (request.operation() != INITIAL_PROPOSAL) {
            SpecificationResponse latest = specificationService.latest(projectId);
            if (latest == null || latest.targetSlideCount() == null || latest.targetSlideCount() != request.targetSlideCount()) {
                throw new ConflictException("PAGE_COUNT_CHANGED: proposal page count must remain unchanged");
            }
        }
        String layoutName = chooseLayout(capability);
        int maxItems = chooseCapacity(capability, layoutName);
        List<PlannedSlide> slides = new ArrayList<>();
        slides.add(slide(1, context.topic(), context.teachingObjectives().get(0),
                "COVER", layoutName, maxItems, request, "confirmed-context:" + confirmedContext.checksum()));
        for (int index = 0; index < confirmedContext.confirmedPageOutline().size(); index++) {
            ConfirmedTeachingContextService.ConfirmedPageOutlineItem page = confirmedContext.confirmedPageOutline().get(index);
            String source = confirmedContext.evidence().isEmpty() ? "confirmed-context:" + confirmedContext.checksum() : confirmedContext.evidence().get(Math.min(index, confirmedContext.evidence().size() - 1)).sourceId();
            slides.add(slide(page.pageNumber(), page.title(),
                    context.teachingObjectives().get(Math.min(index + 1, context.teachingObjectives().size() - 1)),
                    "CONTENT", layoutName, maxItems, request, source));
        }
        return new PlanningProposalDocument(CONTRACT_VERSION, profile.getId().toString(), profile.getVersionNumber(),
                profile.getCapabilityViewVersion(), profile.getCapabilityViewChecksum(), request.targetSlideCount(), request.slideCountTolerance(),
                request.locale(), "MOCK", "mock-v1", "DISABLED", slides);
    }

    private PlannedSlide slide(int page, String title, String goal, String role, String layoutName, int maxItems,
                               PlanningRequest request, String source) {
        return new PlannedSlide("slide-" + page, page, title, goal,
                new PlannedLayout(layoutName, List.of(new PlannedRegion("region-" + page + "-body", "BODY", "CENTER", maxItems)), null),
                List.of(new PlannedContentBlock("block-" + page + "-title", "TITLE", title, "TEACHER", source, true),
                        new PlannedContentBlock("block-" + page + "-body", "BODY", goal, "TEACHER", source, true)),
                List.of(), List.of(new PlannedProvenance("TEACHER", source)),
                "Planning Agent semantic proposal only; no component, slot, shape, group, coordinate, or OXML instruction.");
    }

    private StructuredPlanningResult runStructuredBoundary(Long projectId, PlanningRequest request, PlanningProposalDocument fixture,
                                                           String runId, String traceId, ResolvedModelConnection resolvedConnection) {
        ModelProvider requested = aiProperties.getProvider() == AiProvider.MOCK ? ModelProvider.MOCK : ModelProvider.OPENAI_COMPATIBLE;
        try {
            String fixtureJson = objectMapper.writeValueAsString(fixture);
            ModelExecutionContext executionContext = context(projectId, runId, traceId, request.modelConnectionId());
            ResolvedModelConnection connection = requested == ModelProvider.OPENAI_COMPATIBLE ? resolvedConnection : null;
            String model = requested == ModelProvider.OPENAI_COMPATIBLE ? connection.modelId() : "mock-v1";
            int maxTokens = requested == ModelProvider.OPENAI_COMPATIBLE ? kimiProperties.getWorkflowMaxCompletionTokens() : 8000;
            long timeoutMs = requested == ModelProvider.OPENAI_COMPATIBLE ? Math.max(1000L, kimiProperties.getWorkflowTimeoutSeconds() * 1000L) : 180000L;
            ModelRequest modelRequest = new ModelRequest(
                    "Return one structured PlanningProposalDocument. Use only the teacher-confirmed context and semantic capability view.",
                    List.of(ModelMessage.user("FIXTURE_JSON:" + fixtureJson)),
                    model, maxTokens, 0, timeoutMs,
                    java.util.Map.of("mockStructuredFromMessage", "true"), List.of("planning-agent", request.operation().name()));
            StructuredOutputContract<PlanningProposalDocument> contract = new StructuredOutputContract<>(
                    "planning-proposal-v1", planningProposalSchema(), true,
                    PlanningProposalDocument.class, com.auvdidao.a12teachingagent.agent.model.RepairPolicy.NONE);
            if (requested == ModelProvider.OPENAI_COMPATIBLE) {
                StructuredModelResult<PlanningProposalDocument> result = kimiModelGateway.completeStructuredWithConnection(
                        executionContext, modelRequest, contract);
                return bindRuntimeIdentity(result, requested, model);
            }
            StructuredModelResult<PlanningProposalDocument> result = modelGateway.completeStructured(executionContext, modelRequest, contract);
            return bindRuntimeIdentity(result, requested, model);
        } catch (ModelFailureException exception) {
            throw new AiWorkflowUnavailableException("Planning Agent model connection is unavailable", exception.safeCode(), exception.statusCode());
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("Planning proposal output is not valid JSON");
        }
    }

    private ModelExecutionContext context(Long projectId, String runId, String traceId, Long modelConnectionId) {
        String actor = currentUserService.currentUser().map(user -> user.userId().toString()).orElse("system");
        return new ModelExecutionContext(projectId, actor, "TEACHER", traceId, runId, "PLANNING_AGENT", 180000,
                java.util.Map.of("workflow", "planning-agent", "purpose", "PLANNING"),
                modelConnectionId, "PLANNING");
    }

    private void validateProposal(Long projectId, PlanningRequest request,
                                  ConfirmedTeachingContextService.Snapshot confirmedContext,
                                  TemplateProfileVersion profile,
                                  CapabilityViewResponse capability, PlanningProposalDocument proposal,
                                  ModelProvider actualProvider, String actualModel) {
        if (proposal == null || proposal.slides() == null) throw new BadRequestException("MODEL_OUTPUT_INVALID: proposal slides are missing");
        if (!CONTRACT_VERSION.equals(proposal.contractVersion())) throw new BadRequestException("MODEL_OUTPUT_INVALID: contract version is unsupported");
        if (!profile.getId().toString().equals(proposal.templateProfileId())
                || !profile.getVersionNumber().equals(proposal.templateProfileVersion())
                || !profile.getCapabilityViewVersion().equals(proposal.templateCapabilityViewVersion())
                || !profile.getCapabilityViewChecksum().equalsIgnoreCase(proposal.templateCapabilityViewChecksum())
                || !request.targetSlideCount().equals(proposal.targetSlideCount())
                || !request.slideCountTolerance().equals(proposal.slideCountTolerance())
                || !request.locale().equals(proposal.locale())
                || actualProvider == null
                || !actualProvider.name().equals(proposal.provider())
                || !actualModel.equals(proposal.model())
                || !"DISABLED".equals(proposal.aiSupplementPolicy())) {
            throw new BadRequestException("MODEL_OUTPUT_INVALID: runtime Provider/Model binding changed");
        }
        if (proposal.slides().size() != request.targetSlideCount()) throw new ConflictException("PAGE_COUNT_CHANGED: model output changed page count");
        Set<Integer> pages = new HashSet<>();
        for (int i = 0; i < proposal.slides().size(); i++) {
            PlannedSlide slide = proposal.slides().get(i);
            if (slide == null || slide.pageNumber() != i + 1 || !pages.add(slide.pageNumber())
                    || slide.semanticLayout() == null || slide.contentBlocks() == null
                    || slide.assetRequirements() == null || slide.provenance() == null) {
                throw new BadRequestException("MODEL_OUTPUT_INVALID: slide nested fields are required");
            }
            if (containsForbiddenField(objectMapper.valueToTree(slide))) throw new BadRequestException("MODEL_OUTPUT_INVALID: execution-layer field leaked");
            validateCapacity(capability, slide.semanticLayout(), slide.contentBlocks());
            for (PlannedContentBlock block : slide.contentBlocks()) {
                if (block == null) throw new BadRequestException("MODEL_OUTPUT_INVALID: content block is null");
                if (!Set.of("TITLE", "BODY", "BULLETS", "QUOTE", "TABLE", "CHART", "IMAGE", "TEXT").contains(block.type())) throw new BadRequestException("MODEL_OUTPUT_INVALID: unsupported content block type");
                if (!Set.of("TEACHER", "MATERIAL", "AI_EXAMPLE", "AI_IMAGE").contains(block.sourceType())) throw new BadRequestException("CONTENT_SOURCE_MISMATCH");
                validateContentAndCapability(block, capability, confirmedContext);
            }
            validateAssets(slide, capability, confirmedContext);
            validateProvenance(slide, confirmedContext);
        }
    }

    private void validateCapacity(CapabilityViewResponse capability, PlannedLayout layout, List<PlannedContentBlock> blocks) {
        if (layout == null || !StringUtils.hasText(layout.primaryRole()) || layout.regions() == null) throw new BadRequestException("CAPACITY_EXCEEDED: semantic layout is missing");
        if (layout.requestedTransform() != null && !TRANSFORMS.contains(layout.requestedTransform())) {
            throw new BadRequestException("MODEL_OUTPUT_INVALID: requestedTransform is unsupported");
        }
        for (PlannedRegion region : layout.regions()) {
            if (region == null || !StringUtils.hasText(region.regionId()) || !SEMANTIC_ROLES.contains(region.semanticRole())
                    || !StringUtils.hasText(region.preferredPosition()) || region.maxItems() == null) {
                throw new BadRequestException("MODEL_OUTPUT_INVALID: semantic region is invalid");
            }
        }
        TemplateDtos.SemanticLayout match = capability.semanticLayouts() == null ? null : capability.semanticLayouts().stream().filter(item -> item.name().equals(layout.primaryRole())).findFirst().orElse(null);
        if (match == null) throw new BadRequestException("CAPACITY_EXCEEDED: semantic layout is not in the Capability View");
        long contentItems = blocks == null ? 0 : blocks.stream().filter(block -> block != null && !"TITLE".equals(block.type())).count();
        if (match.maxCapacity() != null && contentItems > match.maxCapacity()) throw new BadRequestException("CAPACITY_EXCEEDED: content exceeds semantic layout capacity");
    }

    private void validateContentAndCapability(PlannedContentBlock block, CapabilityViewResponse capability,
                                              ConfirmedTeachingContextService.Snapshot snapshot) {
        if (block.content() == null || block.content().isBlank()) {
            throw new BadRequestException("CONTENT_FACT_MISMATCH: model output contains empty content");
        }
        if (Set.of("IMAGE", "TABLE", "CHART").contains(block.type())) {
            boolean supported = switch (block.type()) {
                case "IMAGE" -> capability.imageCapability() != null && Boolean.TRUE.equals(capability.imageCapability().supported());
                case "TABLE" -> capability.tableCapability() != null && Boolean.TRUE.equals(capability.tableCapability().supported());
                case "CHART" -> capability.chartCapability() != null && Boolean.TRUE.equals(capability.chartCapability().supported());
                default -> false;
            };
            if (!supported) throw new BadRequestException("CAPABILITY_UNSUPPORTED: " + block.type());
        }
        String reference = block.sourceReference() == null ? "" : block.sourceReference().strip();
        SourceEvidence evidence = snapshot.evidence().stream()
                .filter(item -> item.sourceId().equals(reference)).findFirst().orElse(null);
        boolean contextReference = ("confirmed-context:" + snapshot.checksum()).equals(reference);
        if (!contextReference && evidence == null) {
            throw new BadRequestException("CONTENT_SOURCE_MISMATCH: source is not confirmed");
        }
        if (!knownContextText(snapshot, block.content())
                && (evidence == null || !evidence.excerpt().equals(block.content().strip()))) {
            throw new BadRequestException("CONTENT_FACT_MISMATCH: output is not present in the confirmed context or evidence");
        }
    }

    private void validateAssets(PlannedSlide slide, CapabilityViewResponse capability,
                                ConfirmedTeachingContextService.Snapshot snapshot) {
        List<PlanningDtos.PlannedAssetRequirement> assets = slide.assetRequirements() == null ? List.of() : slide.assetRequirements();
        for (PlanningDtos.PlannedAssetRequirement asset : assets) {
            if (asset == null || asset.assetId() == null || asset.assetType() == null || asset.source() == null
                    || asset.approvalStatus() == null || asset.required() == null || asset.placementIntent() == null) {
                throw new BadRequestException("MODEL_OUTPUT_INVALID: incomplete asset requirement");
            }
            String source = asset.source().strip();
            boolean confirmedContext = ("confirmed-context:" + snapshot.checksum()).equals(source);
            boolean confirmedEvidence = snapshot.evidence().stream().anyMatch(item -> item.sourceId().equals(source));
            if (!confirmedContext && !confirmedEvidence) throw new BadRequestException("ASSET_SOURCE_MISMATCH: asset source is not confirmed");
            if ("IMAGE".equals(asset.assetType()) && (capability.imageCapability() == null || !Boolean.TRUE.equals(capability.imageCapability().supported()))) {
                throw new BadRequestException("CAPABILITY_UNSUPPORTED: IMAGE");
            }
            if ("TABLE".equals(asset.assetType()) && (capability.tableCapability() == null || !Boolean.TRUE.equals(capability.tableCapability().supported()))) {
                throw new BadRequestException("CAPABILITY_UNSUPPORTED: TABLE");
            }
            if ("CHART".equals(asset.assetType()) && (capability.chartCapability() == null || !Boolean.TRUE.equals(capability.chartCapability().supported()))) {
                throw new BadRequestException("CAPABILITY_UNSUPPORTED: CHART");
            }
        }
    }

    private void validateProvenance(PlannedSlide slide, ConfirmedTeachingContextService.Snapshot snapshot) {
        Set<String> allowed = new HashSet<>();
        allowed.add("confirmed-context:" + snapshot.checksum());
        snapshot.evidence().forEach(item -> allowed.add(item.sourceId()));
        for (PlannedProvenance item : slide.provenance()) {
            if (item == null || item.sourceType() == null || item.sourceReference() == null || !allowed.contains(item.sourceReference())) {
                throw new BadRequestException("SOURCE_PROVENANCE_MISMATCH");
            }
        }
        for (PlannedContentBlock block : slide.contentBlocks()) {
            if (slide.provenance().stream().noneMatch(item -> item.sourceType().equals(block.sourceType())
                    && item.sourceReference().equals(block.sourceReference()))) {
                throw new BadRequestException("SOURCE_PROVENANCE_MISMATCH: content source is not individually bound");
            }
        }
        for (PlanningDtos.PlannedAssetRequirement asset : slide.assetRequirements()) {
            if (slide.provenance().stream().noneMatch(item -> item.sourceReference().equals(asset.source()))) {
                throw new BadRequestException("SOURCE_PROVENANCE_MISMATCH: asset source is not individually bound");
            }
        }
    }

    private boolean knownContextText(ConfirmedTeachingContextService.Snapshot snapshot, String value) {
        String normalized = value.strip();
        return normalized.equals(snapshot.context().courseName()) || normalized.equals(snapshot.context().topic())
                || snapshot.context().teachingObjectives().contains(normalized)
                || snapshot.context().outline().contains(normalized)
                || snapshot.context().lessonPlan().contains(normalized)
                || snapshot.evidence().stream().anyMatch(item -> item.excerpt().equals(normalized));
    }

    private SpecificationWriteRequest toSpecificationWrite(PlanningProposalDocument proposal) {
        List<SlideWrite> slides = proposal.slides().stream().map(slide -> new SlideWrite(slide.slideId(), slide.pageNumber(), slide.title(), slide.teachingGoal(),
                new SemanticLayout(slide.semanticLayout().primaryRole(), slide.semanticLayout().regions().stream().map(region -> new SemanticRegion(region.regionId(), region.semanticRole(), region.preferredPosition(), region.maxItems())).toList(), slide.semanticLayout().requestedTransform()),
                slide.contentBlocks().stream().map(block -> new ContentBlockWrite(block.blockId(), block.type(), block.content(), block.sourceType(), block.sourceReference(), block.locked())).toList(),
                slide.assetRequirements().stream().map(asset -> new AssetRequirementWrite(asset.assetId(), asset.assetType(), asset.source(), asset.approvalStatus(), asset.required(), asset.placementIntent())).toList(),
                slide.provenance().stream().map(value -> new ProvenanceWrite(value.sourceType(), value.sourceReference())).toList(), slide.notes())).toList();
        return new SpecificationWriteRequest(proposal.contractVersion(), proposal.templateProfileId(), proposal.templateProfileVersion(),
                proposal.templateCapabilityViewVersion(), proposal.templateCapabilityViewChecksum(), proposal.targetSlideCount(), proposal.slideCountTolerance(),
                proposal.locale(), proposal.provider(), proposal.model(), AiSupplementPolicyValue.DISABLED, slides, null, null);
    }

    private ProposalOperation toSpecificationOperation(PlanningDtos.ProposalOperation operation) {
        return switch (operation) {
            case INITIAL_PROPOSAL -> ProposalOperation.INITIAL_PROPOSAL;
            case PATCH -> ProposalOperation.PATCH;
            case NEW_DRAFT_VERSION -> ProposalOperation.NEW_DRAFT_VERSION;
        };
    }

    private String chooseLayout(CapabilityViewResponse view) {
        if (view.semanticLayouts() == null || view.semanticLayouts().isEmpty()) throw new BadRequestException("CAPABILITY_VIEW_INCOMPLETE: no semantic layout is available");
        return view.semanticLayouts().stream().filter(layout -> layout.maxCapacity() == null || layout.maxCapacity() >= 2).findFirst().orElse(view.semanticLayouts().get(0)).name();
    }

    private int chooseCapacity(CapabilityViewResponse view, String layoutName) {
        return view.semanticLayouts().stream().filter(layout -> layout.name().equals(layoutName)).findFirst().map(layout -> layout.maxCapacity() == null ? 2 : Math.max(1, layout.maxCapacity())).orElse(2);
    }

    private boolean containsUnknownCapabilityField(JsonNode node, String path) {
        if (node == null) return false;
        if (node.isArray()) { for (JsonNode child : node) if (containsUnknownCapabilityField(child, path)) return true; return false; }
        if (!node.isObject()) return false;
        Set<String> allowed = path.isEmpty()
                ? Set.of("displayName", "pageRoles", "semanticLayouts", "imageCapability", "tableCapability", "chartCapability", "fixedBrandAreas", "limitations")
                : path.equals("layout") ? Set.of("name", "description", "minCapacity", "maxCapacity")
                : Set.of("supported", "minCount", "maxCount", "notes");
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!allowed.contains(field.getKey())) return true;
            String childPath = path.isEmpty() && "semanticLayouts".equals(field.getKey()) ? "layout"
                    : field.getKey().endsWith("Capability") ? "capability" : path;
            if (containsUnknownCapabilityField(field.getValue(), childPath)) return true;
        }
        return false;
    }

    private JsonNode planningProposalSchema() {
        var root = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var props = root.putObject("properties");
        props.putObject("contractVersion").put("type", "string").putArray("enum").add(CONTRACT_VERSION);
        props.putObject("templateProfileId").put("type", "string").put("minLength", 1).put("maxLength", 128).put("pattern", "^[A-Za-z0-9._:-]+$");
        props.putObject("templateProfileVersion").put("type", "integer").put("minimum", 1);
        props.putObject("templateCapabilityViewVersion").put("type", "integer").put("minimum", 1);
        props.putObject("templateCapabilityViewChecksum").put("type", "string").put("pattern", "^[0-9a-fA-F]{64}$");
        props.putObject("targetSlideCount").put("type", "integer").put("minimum", 1).put("maximum", 200);
        props.putObject("slideCountTolerance").put("type", "integer").put("minimum", 0).put("maximum", 20);
        props.putObject("locale").put("type", "string").put("minLength", 2).put("maxLength", 32).put("pattern", "^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$");
        props.putObject("provider").put("type", "string").putArray("enum").add("MOCK").add("KIMI");
        props.putObject("model").put("type", "string").put("minLength", 1).put("maxLength", 128).put("pattern", "^[A-Za-z0-9._:/-]+$");
        props.putObject("aiSupplementPolicy").put("type", "string").putArray("enum").add("DISABLED").add("TEACHER_APPROVED_ONLY");

        var slides = props.putObject("slides").put("type", "array").put("minItems", 1).put("maxItems", 200);
        var slide = slides.putObject("items").put("type", "object").put("additionalProperties", false);
        var sp = slide.putObject("properties");
        sp.putObject("slideId").put("type", "string").put("minLength", 1).put("maxLength", 128).put("pattern", "^[A-Za-z0-9._:-]+$");
        sp.putObject("pageNumber").put("type", "integer").put("minimum", 1);
        sp.putObject("title").put("type", "string").put("minLength", 1).put("maxLength", 500);
        sp.putObject("teachingGoal").put("type", "string").put("minLength", 1).put("maxLength", 2000);
        sp.putObject("notes").put("type", "string").put("maxLength", 2000);

        var layout = sp.putObject("semanticLayout").put("type", "object").put("additionalProperties", false);
        var lp = layout.putObject("properties");
        lp.putObject("primaryRole").put("type", "string").put("minLength", 1).put("maxLength", 64);
        var transform = lp.putObject("requestedTransform");
        transform.putArray("type").add("string").add("null");
        transform.putArray("enum").add("FIXED").add("TRANSLATE_ONLY").add("UNIFORM_SCALE").add("STRETCH_X").add("STRETCH_Y").add("RESPONSIVE").addNull();
        var regions = lp.putObject("regions").put("type", "array").put("minItems", 1).put("maxItems", 50);
        var region = regions.putObject("items").put("type", "object").put("additionalProperties", false);
        var rp = region.putObject("properties");
        rp.putObject("regionId").put("type", "string").put("minLength", 1).put("maxLength", 128).put("pattern", "^[A-Za-z0-9._:-]+$");
        rp.putObject("semanticRole").put("type", "string").putArray("enum").addAll(SEMANTIC_ROLES.stream().sorted().map(objectMapper.getNodeFactory()::textNode).toList());
        rp.putObject("preferredPosition").put("type", "string").putArray("enum").add("TOP").add("LEFT").add("RIGHT").add("CENTER").add("BOTTOM").add("FULL_BLEED");
        rp.putObject("maxItems").put("type", "integer").put("minimum", 1).put("maximum", 100);
        required(region, "regionId", "semanticRole", "preferredPosition", "maxItems");
        required(layout, "primaryRole", "regions");

        var blocks = sp.putObject("contentBlocks").put("type", "array").put("minItems", 1).put("maxItems", 100);
        var block = blocks.putObject("items").put("type", "object").put("additionalProperties", false);
        var bp = block.putObject("properties");
        bp.putObject("blockId").put("type", "string").put("minLength", 1).put("maxLength", 128).put("pattern", "^[A-Za-z0-9._:-]+$");
        bp.putObject("type").put("type", "string").putArray("enum").add("TITLE").add("BODY").add("BULLETS").add("QUOTE").add("TABLE").add("CHART").add("IMAGE").add("TEXT");
        bp.putObject("content").put("type", "string").put("minLength", 1).put("maxLength", 4000);
        bp.putObject("sourceType").put("type", "string").putArray("enum").add("TEACHER").add("MATERIAL").add("AI_EXAMPLE").add("AI_IMAGE");
        bp.putObject("sourceReference").put("type", "string").put("minLength", 1).put("maxLength", 500);
        bp.putObject("locked").put("type", "boolean");
        required(block, "blockId", "type", "content", "sourceType", "sourceReference", "locked");

        var assets = sp.putObject("assetRequirements").put("type", "array").put("maxItems", 100);
        var asset = assets.putObject("items").put("type", "object").put("additionalProperties", false);
        var ap = asset.putObject("properties");
        ap.putObject("assetId").put("type", "string").put("minLength", 1).put("maxLength", 128).put("pattern", "^[A-Za-z0-9._:-]+$");
        ap.putObject("assetType").put("type", "string").putArray("enum").add("IMAGE").add("CHART").add("TABLE").add("ICON").add("VIDEO").add("OTHER");
        ap.putObject("source").put("type", "string").put("minLength", 1).put("maxLength", 500);
        ap.putObject("approvalStatus").put("type", "string").putArray("enum").add("APPROVED").add("PENDING").add("REJECTED");
        ap.putObject("required").put("type", "boolean");
        ap.putObject("placementIntent").put("type", "string").putArray("enum").add("TITLE").add("BODY").add("SIDEBAR").add("FOOTER").add("IMAGE").add("CHART").add("TABLE").add("DECORATION").add("OTHER");
        required(asset, "assetId", "assetType", "source", "approvalStatus", "required", "placementIntent");

        var provenance = sp.putObject("provenance").put("type", "array").put("minItems", 1).put("maxItems", 20);
        var evidence = provenance.putObject("items").put("type", "object").put("additionalProperties", false);
        var ep = evidence.putObject("properties");
        ep.putObject("sourceType").put("type", "string").putArray("enum").add("TEACHER").add("MATERIAL").add("AI_EXAMPLE").add("AI_IMAGE");
        ep.putObject("sourceReference").put("type", "string").put("minLength", 1).put("maxLength", 500);
        required(evidence, "sourceType", "sourceReference");
        required(slide, "slideId", "pageNumber", "title", "teachingGoal", "semanticLayout", "contentBlocks", "assetRequirements", "provenance", "notes");
        required(root, "contractVersion", "templateProfileId", "templateProfileVersion", "templateCapabilityViewVersion", "templateCapabilityViewChecksum", "targetSlideCount", "slideCountTolerance", "locale", "provider", "model", "aiSupplementPolicy", "slides");
        return root;
    }

    private void required(com.fasterxml.jackson.databind.node.ObjectNode schema, String... fields) {
        var required = schema.putArray("required");
        for (String field : fields) required.add(field);
    }

    private String inputChecksum(PlanningRequest request, ConfirmedTeachingContextService.Snapshot snapshot) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("confirmedContextRevision", snapshot.revision()); value.put("confirmedContextChecksum", snapshot.checksum());
        value.put("canonicalSnapshot", snapshot.canonicalJson()); value.put("templateId", request.templateId());
        value.put("templateProfileVersionId", request.templateProfileVersionId()); value.put("operation", request.operation());
        value.put("teacherInstruction", request.teacherInstruction()); value.put("baseSpecificationVersion", request.baseSpecificationVersion());
        value.put("baseSpecificationChecksum", request.baseSpecificationChecksum()); value.put("targetSlideCount", request.targetSlideCount());
        value.put("slideCountTolerance", request.slideCountTolerance()); value.put("locale", request.locale());
        value.put("modelConnectionId", request.modelConnectionId());
        try { return sha256(objectMapper.writeValueAsString(value)); }
        catch (JsonProcessingException exception) { throw new BadRequestException("PLANNING_INPUT_SERIALIZATION_FAILED"); }
    }

    private void completeTrace(PlanningAgentTrace trace, PlanningTraceStatus status, String provider, String model, String outputChecksum, String rejection) {
        traceAuditService.complete(trace.getProjectId(), trace.getTraceId(), status, provider, model, outputChecksum, rejection);
    }

    private String providerStatus() { return aiProperties.getProvider() == AiProvider.MOCK ? "MOCK_FIXTURE" : "NOT_RUN"; }
    private String requestedProvider() { return aiProperties.getProvider() == AiProvider.MOCK ? "MOCK" : "OPENAI_COMPATIBLE"; }

    private String executionStatus(ModelProvider provider) {
        return provider == ModelProvider.MOCK ? "MOCK_FIXTURE" : "COMPLETED";
    }

    private StructuredPlanningResult bindRuntimeIdentity(StructuredModelResult<PlanningProposalDocument> result,
                                                         ModelProvider requestedProvider, String requestedModel) {
        if (result == null || result.value() == null || result.provider() == null || !StringUtils.hasText(result.model())) {
            throw new BadRequestException("MODEL_OUTPUT_INVALID: structured result metadata is incomplete");
        }
        if (result.provider() != requestedProvider || !requestedModel.equals(result.model())) {
            throw new BadRequestException("MODEL_OUTPUT_PROVIDER_MISMATCH: gateway result does not match the requested Provider/Model");
        }
        PlanningProposalDocument value = result.value();
        PlanningProposalDocument runtimeBound = new PlanningProposalDocument(value.contractVersion(), value.templateProfileId(),
                value.templateProfileVersion(), value.templateCapabilityViewVersion(), value.templateCapabilityViewChecksum(),
                value.targetSlideCount(), value.slideCountTolerance(), value.locale(), result.provider().name(), result.model(),
                value.aiSupplementPolicy(), value.slides());
        return new StructuredPlanningResult(runtimeBound, result.provider(), result.model());
    }

    private String rejectionReason(RuntimeException exception) {
        String message = exception.getMessage() == null ? "PLANNING_REJECTED" : exception.getMessage();
        for (String code : List.of("PAGE_COUNT_MISMATCH", "PAGE_COUNT_CHANGED", "CAPACITY_EXCEEDED", "CONTENT_SOURCE_MISMATCH", "MODEL_OUTPUT_INVALID", "CAPABILITY_VIEW_INCOMPLETE")) if (message.contains(code)) return code;
        if (exception instanceof ConflictException) return "CONFLICT";
        return "PLANNING_REJECTED";
    }

    private TraceResponse toTrace(PlanningAgentTrace trace) {
        return new TraceResponse(trace.getId(), trace.getProjectId(), trace.getRunId(), trace.getTraceId(), trace.getOperation(), trace.getStatus().name(),
                trace.getRequestedProvider(), trace.getUsedProvider(), trace.getUsedModel(), trace.getCapabilityViewChecksum(), trace.getInputChecksum(), trace.getOutputChecksum(),
                trace.getRejectionReason(), trace.getCreatedAt(), trace.getCompletedAt());
    }

    private boolean containsForbiddenField(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (FORBIDDEN_FIELDS.contains(field.getKey().toLowerCase())) return true;
                if (containsForbiddenField(field.getValue())) return true;
            }
        } else if (node.isArray()) for (JsonNode child : node) if (containsForbiddenField(child)) return true;
        return false;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private record StructuredPlanningResult(PlanningProposalDocument proposal, ModelProvider provider, String model) { }
}
