package com.auvdidao.a12teachingagent.pptengine;

import com.auvdidao.a12teachingagent.asset.AssetService;
import com.auvdidao.a12teachingagent.asset.ManifestStatus;
import com.auvdidao.a12teachingagent.domain.generation.GenerationJob;
import com.auvdidao.a12teachingagent.domain.generation.GenerationJobStatus;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationJobRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationAssetRequirement;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationSlide;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationStatus;
import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationVersion;
import com.auvdidao.a12teachingagent.domain.specification.repository.PptSpecificationVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileVersion;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ArtifactReceipt;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.EngineStatus;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.ExecutionResponse;
import com.auvdidao.a12teachingagent.pptengine.PptEngineContracts.GenerationRequest;
import com.auvdidao.a12teachingagent.pptengine.PptGenerationDtos.CreateGenerationJobRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PptGenerationServiceTest {
    private final ProjectRepository projects = org.mockito.Mockito.mock(ProjectRepository.class);
    private final PptSpecificationVersionRepository specifications = org.mockito.Mockito.mock(PptSpecificationVersionRepository.class);
    private final com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository profiles = org.mockito.Mockito.mock(com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository.class);
    private final GenerationJobRepository jobs = org.mockito.Mockito.mock(GenerationJobRepository.class);
    private final ProjectAccessService access = org.mockito.Mockito.mock(ProjectAccessService.class);
    private final com.auvdidao.a12teachingagent.specification.PptSpecificationService specificationService = org.mockito.Mockito.mock(com.auvdidao.a12teachingagent.specification.PptSpecificationService.class);
    private final com.auvdidao.a12teachingagent.template.TemplateProfileContractGate profileGate = org.mockito.Mockito.mock(com.auvdidao.a12teachingagent.template.TemplateProfileContractGate.class);
    private final AssetService assets = org.mockito.Mockito.mock(AssetService.class);
    private final PptEngineClient engine = org.mockito.Mockito.mock(PptEngineClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PptGenerationService service;
    private Project project;
    private PptSpecificationVersion specification;
    private TemplateProfileVersion profile;
    private AssetService.EngineManifest manifest;

    @BeforeEach
    void setUp() {
        service = new PptGenerationService(projects, specifications, profiles, jobs, access, specificationService,
                profileGate, assets, engine, objectMapper);
        project = new Project();
        project.setId(7L);
        project.setOwnerUserId(99L);
        when(access.requireAuthenticatedTeacherAccess(7L)).thenReturn(new AuthenticatedUser(1L, 99L, "teacher", "Teacher", UserRole.TEACHER));
        when(projects.findById(7L)).thenReturn(Optional.of(project));

        specification = new PptSpecificationVersion();
        specification.setId(11L);
        specification.setProjectId(7L);
        specification.setSpecificationId("spec-1");
        specification.setVersionNumber(3);
        specification.setStatus(PptSpecificationStatus.LOCKED);
        specification.setChecksum("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        specification.setContractVersion("1.0.0");
        specification.setTemplateProfileId("21");
        specification.setTemplateProfileVersion(2);
        specification.setTemplateProfileChecksum("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        specification.setTemplateCapabilityViewVersion(1);
        specification.setTemplateCapabilityViewChecksum("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        PptSpecificationSlide slide = new PptSpecificationSlide();
        slide.setSlideId("slide-1"); slide.setPageNumber(1); slide.setTitle("Title"); slide.setTeachingGoal("Goal"); slide.setSemanticLayoutJson("{}");
        PptSpecificationAssetRequirement requirement = new PptSpecificationAssetRequirement();
        requirement.setAssetId("hero"); requirement.setAssetType("IMAGE"); requirement.setSource("uploaded-material:44"); requirement.setApprovalStatus("APPROVED"); requirement.setRequired(true); requirement.setPlacementIntent("IMAGE");
        slide.addAssetRequirement(requirement);
        specification.addSlide(slide);
        when(specificationService.requireLockedForGeneration(7L, 11L)).thenReturn(specification);
        when(profileGate.requireLockedBinding(7L, "21", 2, specification.getTemplateProfileChecksum(), 1, specification.getTemplateCapabilityViewChecksum()))
                .thenReturn(new com.auvdidao.a12teachingagent.template.TemplateProfileContractGate.ProfileBinding(21L, 31L, 41L, 2, specification.getTemplateProfileChecksum(), 1, specification.getTemplateCapabilityViewChecksum()));
        profile = new TemplateProfileVersion(); profile.setId(21L); profile.setProjectId(7L); profile.setTemplateId(31L); profile.setVersionNumber(2); profile.setChecksum(specification.getTemplateProfileChecksum());
        profile.setStatus(com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus.READY);
        profile.setOwnedByTeacherId(99L);
        profile.setProfileJson("{\"displayName\":\"Confirmed\"}"); profile.setCapabilityViewJson("{\"displayName\":\"Confirmed\"}");
        when(profiles.findByIdAndProjectId(21L, 7L)).thenReturn(Optional.of(profile));
        manifest = new AssetService.EngineManifest(61L, 7L, 99L, 51L, "hero", 4, 2, "hero", "IMAGE", "UPLOAD", "uploaded-material:44", "uploads/hero.png", "image/png", 10L,
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", "UPLOAD", "teacher-upload", ManifestStatus.ACTIVE.name(), "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        when(assets.requireApprovedManifestForEngine(7L, "hero", "uploaded-material:44", "IMAGE")).thenReturn(manifest);
        when(jobs.saveAndFlush(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob job = invocation.getArgument(0);
            if (job.getId() == null) job.setId(101L);
            return job;
        });
    }

    @Test
    void bindsAllThreeImmutableInputsAndNeverSerializesCredentialFields() throws Exception {
        String manifestChecksum = checksumForManifest(manifest);
        when(engine.execute(any(GenerationRequest.class))).thenAnswer(invocation -> {
            GenerationRequest request = invocation.getArgument(0);
            assertThat(request.executionContext().jobId()).isEqualTo(101L);
            assertThat(request.specification().status()).isEqualTo("LOCKED");
            assertThat(request.templateProfile().checksum()).isEqualTo(profile.getChecksum());
            assertThat(request.templateProfile().ownerUserId()).isEqualTo(99L);
            assertThat(request.approvedAssetManifest()).hasSize(1);
            assertThat(objectMapper.writeValueAsString(request)).doesNotContain("apiKey", "credential", "authorization", "secret");
            return new ExecutionResponse(request.executionContext().executionId(), "engine-v1", EngineStatus.SUCCEEDED,
                    request.specification().checksum(), request.templateProfile().checksum(), manifestChecksum,
                    List.of(new ArtifactReceipt("artifact-1", "PPTX", "engine/artifact-1.pptx", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 20L,
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation")), List.of());
        });

        var response = service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "request-1"));

        assertThat(response.status()).isEqualTo(GenerationJobStatus.SUCCEEDED);
        assertThat(response.specificationChecksum()).isEqualTo(specification.getChecksum());
        assertThat(response.assetManifestChecksum()).isEqualTo(manifestChecksum);
        assertThat(response.inputIdentityChecksum()).hasSize(64);
        assertThat(response.engineReceiptJson()).contains("artifact-1");
        verify(engine).execute(any(GenerationRequest.class));
    }

    @Test
    void rejectsMismatchedEngineResponseAndPersistsOnlyContractFailure() {
        when(engine.execute(any(GenerationRequest.class))).thenReturn(new ExecutionResponse("forged-execution", "engine-v1", EngineStatus.SUCCEEDED,
                specification.getChecksum(), profile.getChecksum(), checksumForManifest(manifest),
                List.of(new ArtifactReceipt("artifact-1", "PPTX", "engine/a.pptx", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 20L, "application/pptx")), List.of()));

        assertThatThrownBy(() -> service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "request-forged")))
                .isInstanceOf(PptEngineException.class).hasMessageContaining("binding");
        var saved = org.mockito.ArgumentCaptor.forClass(GenerationJob.class);
        verify(jobs, org.mockito.Mockito.atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(saved.getAllValues().size() - 1).getStatus()).isEqualTo(GenerationJobStatus.CONTRACT_FAILED);
    }

    @Test
    void transportFailureIsFailClosedAndDoesNotCreateSuccessArtifact() {
        when(engine.execute(any(GenerationRequest.class))).thenThrow(new PptEngineException("PPT_ENGINE_TRANSPORT_FAILED", 503, "unavailable"));

        assertThatThrownBy(() -> service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "request-timeout")))
                .isInstanceOf(PptEngineException.class).hasMessageContaining("unavailable");
        var saved = org.mockito.ArgumentCaptor.forClass(GenerationJob.class);
        verify(jobs, org.mockito.Mockito.atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(saved.getAllValues().size() - 1).getStatus()).isEqualTo(GenerationJobStatus.TRANSPORT_FAILED);
    }

    @Test
    void idempotencyReturnsExistingJobOnlyAfterRecheckingCurrentInputs() {
        GenerationJob existing = new GenerationJob(); existing.setId(77L); existing.setProjectId(7L); existing.setSpecificationVersionId(11L); existing.setEngineVersion("engine-v1"); existing.setIdempotencyKey("same"); existing.setStatus(GenerationJobStatus.FAILED);
        existing.setInputIdentityChecksum(null);
        when(jobs.findByProjectIdAndIdempotencyKey(7L, "same")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "same")))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ConflictException.class);
        verify(engine, never()).execute(any());
        verify(specificationService).requireLockedForGeneration(7L, 11L);
        verify(profileGate).requireLockedBinding(any(), any(), any(), any(), any(), any());
        verify(assets).requireApprovedManifestForEngine(7L, "hero", "uploaded-material:44", "IMAGE");
    }

    @Test
    void changedCurrentManifestCannotReuseHistoricalIdempotencyJob() {
        GenerationJob existing = new GenerationJob(); existing.setId(77L); existing.setProjectId(7L); existing.setSpecificationVersionId(11L);
        existing.setEngineVersion("engine-v1"); existing.setIdempotencyKey("changed"); existing.setStatus(GenerationJobStatus.SUCCEEDED);
        existing.setInputIdentityChecksum("old");
        when(jobs.findByProjectIdAndIdempotencyKey(7L, "changed")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "changed")))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ConflictException.class)
                .hasMessageContaining("different generation input");
        verify(engine, never()).execute(any());
        verify(jobs, never()).saveAndFlush(any(GenerationJob.class));
    }

    @Test
    void identicalCompleteInputReusesSameJobAfterRunningEveryCurrentGate() {
        when(engine.execute(any(GenerationRequest.class))).thenAnswer(invocation -> {
            GenerationRequest request = invocation.getArgument(0);
            return new ExecutionResponse(request.executionContext().executionId(), "engine-v1", EngineStatus.SUCCEEDED,
                    request.specification().checksum(), request.templateProfile().checksum(), checksumForManifest(manifest),
                    List.of(new ArtifactReceipt("artifact-1", "PPTX", "engine/a.pptx", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 1L, "application/pptx")), List.of());
        });
        var first = service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "same-complete"));
        var capture = org.mockito.ArgumentCaptor.forClass(GenerationJob.class);
        verify(jobs, org.mockito.Mockito.atLeastOnce()).saveAndFlush(capture.capture());
        GenerationJob historical = capture.getAllValues().get(capture.getAllValues().size() - 1);
        org.mockito.Mockito.doReturn(Optional.of(historical)).when(jobs).findByProjectIdAndIdempotencyKey(7L, "same-complete");

        var second = service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "same-complete"));

        assertThat(second.id()).isEqualTo(first.id());
        verify(engine).execute(any(GenerationRequest.class));
        verify(specificationService, org.mockito.Mockito.times(2)).requireLockedForGeneration(7L, 11L);
        verify(profileGate, org.mockito.Mockito.times(2)).requireLockedBinding(any(), any(), any(), any(), any(), any());
        verify(assets, org.mockito.Mockito.times(2)).requireApprovedManifestForEngine(7L, "hero", "uploaded-material:44", "IMAGE");
    }

    @Test
    void profileOwnerMismatchFailsBeforeJobOrEngine() {
        profile.setOwnedByTeacherId(123L);

        assertThatThrownBy(() -> service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "wrong-owner")))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ConflictException.class);
        verify(engine, never()).execute(any());
        verify(jobs, never()).saveAndFlush(any(GenerationJob.class));
        verify(assets, never()).requireApprovedManifestForEngine(any(), any(), any(), any());
    }

    @Test
    void idempotencyInsertRaceReturnsStableConflictWithoutEngineCall() {
        when(jobs.saveAndFlush(any(GenerationJob.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique key"));

        assertThatThrownBy(() -> service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "race")))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ConflictException.class)
                .hasMessageContaining("already being created");
        verify(engine, never()).execute(any());
    }

    @Test
    void mapsPartialFeedbackAndFailedWithoutCreatingSuccessArtifact() {
        when(engine.execute(any(GenerationRequest.class))).thenAnswer(invocation -> {
            GenerationRequest request = invocation.getArgument(0);
            return new ExecutionResponse(request.executionContext().executionId(), "engine-v1", EngineStatus.PARTIAL,
                    request.specification().checksum(), request.templateProfile().checksum(), checksumForManifest(manifest),
                    List.of(new ArtifactReceipt("partial", "PPTX", "engine/partial.pptx", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 1L, "application/pptx")), List.of("review"));
        });
        assertThat(service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "partial")).status()).isEqualTo(GenerationJobStatus.PARTIAL);

        when(engine.execute(any(GenerationRequest.class))).thenAnswer(invocation -> {
            GenerationRequest request = invocation.getArgument(0);
            return new ExecutionResponse(request.executionContext().executionId(), "engine-v1", EngineStatus.FAILED,
                    request.specification().checksum(), request.templateProfile().checksum(), checksumForManifest(manifest), List.of(), List.of("not generated"));
        });
        assertThat(service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "failed")).status()).isEqualTo(GenerationJobStatus.FAILED);
    }

    @Test
    void mapsSucceededWithFeedbackAndRejectsFailedResponseWithReceipt() {
        when(engine.execute(any(GenerationRequest.class))).thenAnswer(invocation -> {
            GenerationRequest request = invocation.getArgument(0);
            return new ExecutionResponse(request.executionContext().executionId(), "engine-v1", EngineStatus.SUCCEEDED_WITH_FEEDBACK,
                    request.specification().checksum(), request.templateProfile().checksum(), checksumForManifest(manifest),
                    List.of(new ArtifactReceipt("feedback", "PPTX", "engine/feedback.pptx", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 1L, "application/pptx")), List.of("adjustment"));
        });
        assertThat(service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "feedback")).status()).isEqualTo(GenerationJobStatus.SUCCEEDED_WITH_FEEDBACK);

        when(engine.execute(any(GenerationRequest.class))).thenAnswer(invocation -> {
            GenerationRequest request = invocation.getArgument(0);
            return new ExecutionResponse(request.executionContext().executionId(), "engine-v1", EngineStatus.FAILED,
                    request.specification().checksum(), request.templateProfile().checksum(), checksumForManifest(manifest),
                    List.of(new ArtifactReceipt("forbidden", "PPTX", "engine/forbidden.pptx", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 1L, "application/pptx")), List.of());
        });
        assertThatThrownBy(() -> service.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "failed-receipt")))
                .isInstanceOf(PptEngineException.class).hasMessageContaining("must not contain");
        var saved = org.mockito.ArgumentCaptor.forClass(GenerationJob.class);
        verify(jobs, org.mockito.Mockito.atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(saved.getAllValues().size() - 1).getStatus()).isEqualTo(GenerationJobStatus.CONTRACT_FAILED);
    }

    @Test
    void legacyProfileIsRejectedBeforeGenerationJobOrHttpCall() {
        var sources = org.mockito.Mockito.mock(com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository.class);
        var source = new com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion();
        profile.setSourceVersionId(41L);
        when(sources.findByIdAndTemplateIdAndProjectId(41L, 31L, 7L)).thenReturn(Optional.of(source));
        var storage = new com.auvdidao.a12teachingagent.material.storage.StorageProperties();
        PptGenerationService guarded = new PptGenerationService(projects, specifications, profiles, jobs, access,
                specificationService, profileGate, assets, engine, objectMapper, sources, storage);

        assertThatThrownBy(() -> guarded.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "legacy-profile")))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ConflictException.class)
                .hasMessageContaining("ENGINE_PROFILE_MIGRATION_REQUIRED");
        verify(jobs, never()).saveAndFlush(any(GenerationJob.class));
        verify(engine, never()).execute(any());
    }

    @Test
    void fileBackedReadyProfileBuildsCompleteNativeExecutionBinding() throws Exception {
        Path root = Files.createTempDirectory("a12-0098-file-backed-");
        try {
            var sourceRepository = org.mockito.Mockito.mock(com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository.class);
            var storage = new com.auvdidao.a12teachingagent.material.storage.StorageProperties();
            storage.setUploadDir(root.toString());
            byte[] templateBytes = "controlled-template".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path templateFile = root.resolve("template.pptx");
            Files.write(templateFile, templateBytes);
            String templateHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(templateBytes));
            var source = new com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion();
            source.setId(41L); source.setTemplateId(31L); source.setProjectId(7L); source.setVersionNumber(1);
            source.setCreatedByUserId(99L); source.setStorageKey("template.pptx"); source.setFileSize((long) templateBytes.length);
            source.setSha256(templateHash);
            when(sourceRepository.findByIdAndTemplateIdAndProjectId(41L, 31L, 7L)).thenReturn(Optional.of(source));

            String snapshotJson = "{\"slideCount\":1,\"pageWidth\":720,\"pageHeight\":540,\"slides\":[{\"pageNumber\":1,\"shapes\":[{\"reference\":\"slide-1/shape-1\",\"type\":\"XSLFTextBox\",\"x\":10,\"y\":10,\"width\":200,\"height\":100,\"text\":true}]}]}";
            String snapshotHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(snapshotJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var snapshot = new com.auvdidao.a12teachingagent.domain.template.TemplateStructuralSnapshot();
            snapshot.setTemplateId(31L); snapshot.setSourceVersionId(41L); snapshot.setSlideCount(1);
            snapshot.setSnapshotJson(snapshotJson); snapshot.setChecksum(snapshotHash);
            when(profileGate.loadAndVerifySnapshot(41L)).thenReturn(snapshot);
            profile.setSourceVersionId(41L);
            profile.setParserSnapshotChecksum(snapshotHash);
            profile.setEngineNativeProfileJson(nativeProfileJson("READY", templateHash, snapshotHash));
            profile.setEngineNativeProfileChecksum(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(profile.getEngineNativeProfileJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            specification.setAiSupplementPolicy(com.auvdidao.a12teachingagent.domain.specification.AiSupplementPolicy.DISABLED);
            specification.setLockedBy(99L); specification.setLockedAt(LocalDateTime.now());
            manifest = fileBackedManifest(root, "asset.png");
            when(assets.requireApprovedManifestForEngine(7L, "hero", "uploaded-material:44", "IMAGE")).thenReturn(manifest);
            when(engine.execute(any(GenerationRequest.class))).thenAnswer(invocation -> {
                GenerationRequest request = invocation.getArgument(0);
                assertThat(request.executionBindings()).isNotNull();
                assertThat(request.executionBindings().templateProfile().path("sourceVersionId").asLong()).isEqualTo(41L);
                assertThat(request.executionBindings().templateProfile().path("sourceSha256").asText()).isEqualTo(templateHash);
                assertThat(request.executionBindings().templateProfile().path("parserSnapshotChecksum").asText()).isEqualTo(snapshotHash);
                assertThat(request.executionBindings().templateProfile().path("executionStatus").asText())
                        .isEqualTo("EXECUTION_READY");
                return new ExecutionResponse(request.executionContext().executionId(), "engine-v1", EngineStatus.SUCCEEDED,
                        request.executionBindings().specificationChecksum(), request.executionBindings().templateProfileChecksum(),
                        request.executionBindings().assetManifestChecksum(), List.of(new ArtifactReceipt("artifact-file-backed", "PPTX", "engine/a.pptx",
                                "f".repeat(64), 1L, "application/pptx")), List.of());
            });
            PptGenerationService guarded = new PptGenerationService(projects, specifications, profiles, jobs, access,
                    specificationService, profileGate, assets, engine, objectMapper, sourceRepository, storage);

            var result = guarded.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "file-backed-ready"));

            assertThat(result.status()).isEqualTo(GenerationJobStatus.SUCCEEDED);
            verify(engine).execute(any(GenerationRequest.class));

            var confirmedNode = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(profile.getEngineNativeProfileJson());
            confirmedNode.put("status", "CONFIRMED");
            profile.setStatus(com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus.CONFIRMED);
            profile.setEngineNativeProfileJson(objectMapper.writeValueAsString(confirmedNode));
            profile.setEngineNativeProfileChecksum(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(profile.getEngineNativeProfileJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            var confirmedResult = guarded.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "file-backed-confirmed"));
            assertThat(confirmedResult.status()).isEqualTo(GenerationJobStatus.SUCCEEDED);
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    @Test
    void fileBackedNativeSourceBindingTamperFailsBeforeJobOrEngine() throws Exception {
        Path root = Files.createTempDirectory("a12-0098-file-backed-tamper-");
        try {
            var sourceRepository = org.mockito.Mockito.mock(com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository.class);
            var storage = new com.auvdidao.a12teachingagent.material.storage.StorageProperties();
            storage.setUploadDir(root.toString());
            byte[] templateBytes = "controlled-template".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path templateFile = root.resolve("template.pptx");
            Files.write(templateFile, templateBytes);
            String templateHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(templateBytes));
            var source = new com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion();
            source.setId(41L); source.setTemplateId(31L); source.setProjectId(7L); source.setVersionNumber(1);
            source.setCreatedByUserId(99L); source.setStorageKey("template.pptx"); source.setFileSize((long) templateBytes.length);
            source.setSha256(templateHash);
            when(sourceRepository.findByIdAndTemplateIdAndProjectId(41L, 31L, 7L)).thenReturn(Optional.of(source));
            String snapshotJson = "{\"slideCount\":1,\"pageWidth\":720,\"pageHeight\":540,\"slides\":[{\"pageNumber\":1,\"shapes\":[{\"reference\":\"slide-1/shape-1\",\"type\":\"XSLFTextBox\",\"x\":10,\"y\":10,\"width\":200,\"height\":100,\"text\":true}]}]}";
            String snapshotHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(snapshotJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var snapshot = new com.auvdidao.a12teachingagent.domain.template.TemplateStructuralSnapshot();
            snapshot.setTemplateId(31L); snapshot.setSourceVersionId(41L); snapshot.setSlideCount(1);
            snapshot.setSnapshotJson(snapshotJson); snapshot.setChecksum(snapshotHash);
            when(profileGate.loadAndVerifySnapshot(41L)).thenReturn(snapshot);
            profile.setSourceVersionId(41L); profile.setParserSnapshotChecksum(snapshotHash);
            profile.setEngineNativeProfileJson(nativeProfileJson("READY", templateHash, snapshotHash));
            profile.setEngineNativeProfileChecksum(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(profile.getEngineNativeProfileJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            specification.setAiSupplementPolicy(com.auvdidao.a12teachingagent.domain.specification.AiSupplementPolicy.DISABLED);
            specification.setLockedBy(99L); specification.setLockedAt(LocalDateTime.now());
            manifest = fileBackedManifest(root, "asset.png");
            when(assets.requireApprovedManifestForEngine(7L, "hero", "uploaded-material:44", "IMAGE")).thenReturn(manifest);
            PptGenerationService guarded = new PptGenerationService(projects, specifications, profiles, jobs, access,
                    specificationService, profileGate, assets, engine, objectMapper, sourceRepository, storage);

            var variants = new java.util.ArrayList<String>();
            var missingSource = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(profile.getEngineNativeProfileJson());
            missingSource.remove("sourceVersionId"); variants.add(objectMapper.writeValueAsString(missingSource));
            var wrongSource = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(profile.getEngineNativeProfileJson());
            wrongSource.put("sourceVersionId", "999"); variants.add(objectMapper.writeValueAsString(wrongSource));
            var wrongSnapshot = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(profile.getEngineNativeProfileJson());
            wrongSnapshot.put("parserSnapshotChecksum", "0".repeat(64)); variants.add(objectMapper.writeValueAsString(wrongSnapshot));
            for (int i = 0; i < variants.size(); i++) {
                final int variantIndex = i;
                profile.setStatus(com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus.READY);
                profile.setEngineNativeProfileJson(variants.get(i));
                profile.setEngineNativeProfileChecksum(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(variants.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                assertThatThrownBy(() -> guarded.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "file-backed-tamper-" + variantIndex)))
                        .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ConflictException.class)
                        .hasMessageContaining("ENGINE_PROFILE_BINDING_STALE");
            }
            profile.setEngineNativeProfileJson(nativeProfileJson("READY", templateHash, snapshotHash));
            profile.setEngineNativeProfileChecksum("0".repeat(64));
            assertThatThrownBy(() -> guarded.start(7L, new CreateGenerationJobRequest(11L, "engine-v1", "file-backed-bad-checksum")))
                    .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ConflictException.class)
                    .hasMessageContaining("ENGINE_NATIVE_PROFILE_BINDING_INVALID");
            verify(jobs, never()).saveAndFlush(any(GenerationJob.class));
            verify(engine, never()).execute(any(GenerationRequest.class));
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    private String nativeProfileJson(String status, String sourceHash, String snapshotHash) throws Exception {
        var root = objectMapper.createObjectNode();
        root.put("contractVersion", "1.0.0"); root.put("profileId", "21"); root.put("templateId", "31");
        root.put("projectId", "7"); root.put("ownerUserId", "99"); root.put("templateVersion", 1);
        root.put("profileVersion", 2); root.put("status", status); root.put("sourceVersionId", "41");
        root.put("sourceSha256", sourceHash); root.put("parserSnapshotChecksum", snapshotHash);
        root.set("pageSize", objectMapper.createObjectNode().put("widthEmu", 9144000).put("heightEmu", 6858000));
        root.set("spatialProfile", objectMapper.createObjectNode().put("safeMarginLeftEmu", 0).put("safeMarginTopEmu", 0)
                .put("safeMarginRightEmu", 0).put("safeMarginBottomEmu", 0));
        var page = objectMapper.createObjectNode(); page.put("pageReferenceId", "page-1"); page.put("sourceSlide", 1);
        page.put("semanticRole", "PAGE"); page.putArray("objectIds").add("slide-1.shape-1"); root.putArray("templatePageReferences").add(page);
        var component = objectMapper.createObjectNode(); component.put("componentId", "component-1").put("name", "shape-1")
                .put("semanticRole", "TEXT").put("sourceSlide", 1);
        component.putArray("shapeRefs").addObject().put("objectType", "TEXT").put("objectId", "slide-1.shape-1");
        component.putArray("childComponentIds");
        var slot = component.putArray("slots").addObject(); slot.put("slotId", "slot-1").put("semanticRole", "TEXT");
        slot.putArray("acceptedContentTypes").add("TEXT"); slot.set("bounds", objectMapper.createObjectNode().put("leftEmu", 127000)
                .put("topEmu", 127000).put("widthEmu", 2540000).put("heightEmu", 1270000)); slot.put("required", false);
        slot.set("capacityConstraint", objectMapper.createObjectNode().putNull("maxCharacters").putNull("maxItems"));
        component.put("transformConstraint", "FIXED"); component.set("fixedStyle", objectMapper.createObjectNode().put("styleId", "style-1")
                .put("fontToken", "template-default").put("colorToken", "template-default").put("preserveTheme", true));
        component.put("reusable", false).put("confidence", 1.0).put("teacherConfirmed", false);
        root.putArray("components").add(component);
        return objectMapper.writeValueAsString(root);
    }

    private AssetService.EngineManifest fileBackedManifest(Path root, String filename) throws Exception {
        Path asset = root.resolve(filename); byte[] bytes = "controlled-asset".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(asset, bytes);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String modified = Files.getLastModifiedTime(asset).toInstant().toString();
        return new AssetService.EngineManifest(61L, 7L, 99L, 51L, "hero", 4, 2, "hero", "IMAGE", "UPLOAD", "uploaded-material:44",
                filename, "image/png", (long) bytes.length, hash, "UPLOAD", "teacher-upload", ManifestStatus.ACTIVE.name(),
                "e".repeat(64), modified);
    }

    private String checksumForManifest(AssetService.EngineManifest item) {
        String value = String.join("|", String.valueOf(item.id()), item.assetKey(), String.valueOf(item.manifestVersion()),
                String.valueOf(item.candidateAssetId()), String.valueOf(item.candidateVersion()), item.sha256(),
                String.valueOf(item.fileSize()), String.valueOf(item.approvedFileLastModifiedUtc()), item.manifestChecksum()) + "\n";
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
