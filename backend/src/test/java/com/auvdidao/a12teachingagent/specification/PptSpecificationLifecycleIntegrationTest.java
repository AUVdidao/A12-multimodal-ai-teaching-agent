package com.auvdidao.a12teachingagent.specification;

import com.auvdidao.a12teachingagent.domain.specification.PptSpecificationVersion;
import com.auvdidao.a12teachingagent.domain.specification.repository.PptSpecificationVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.Template;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileOrigin;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileVersion;
import com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion;
import com.auvdidao.a12teachingagent.domain.template.TemplateStructuralSnapshot;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateStructuralSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.asset.AssetService;
import com.auvdidao.a12teachingagent.asset.AssetStatus;
import com.auvdidao.a12teachingagent.asset.ManifestStatus;
import com.auvdidao.a12teachingagent.domain.asset.CandidateAsset;
import com.auvdidao.a12teachingagent.domain.asset.repository.ApprovedAssetManifestRepository;
import com.auvdidao.a12teachingagent.domain.asset.repository.AssetAuditEventRepository;
import com.auvdidao.a12teachingagent.domain.asset.repository.CandidateAssetRepository;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PptSpecificationLifecycleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PptSpecificationVersionRepository versionRepository;
    @Autowired private TemplateRepository templateRepository;
    @Autowired private TemplateSourceVersionRepository sourceRepository;
    @Autowired private TemplateStructuralSnapshotRepository snapshotRepository;
    @Autowired private TemplateProfileVersionRepository profileRepository;
    @Autowired private AssetService assetService;
    @Autowired private CandidateAssetRepository candidateRepository;
    @Autowired private ApprovedAssetManifestRepository manifestRepository;
    @Autowired private AssetAuditEventRepository auditRepository;
    @MockBean private CurrentUserService currentUserService;
    @MockBean private FileStorageService storage;

    @org.junit.jupiter.api.BeforeEach
    void authenticateFixtureTeacher() {
        org.mockito.Mockito.when(currentUserService.currentUser()).thenReturn(java.util.Optional.of(
                new AuthenticatedUser(1L, 100L, "fixture-teacher", "Fixture Teacher", UserRole.TEACHER)));
        org.mockito.Mockito.when(currentUserService.requireRole(UserRole.TEACHER)).thenReturn(
                new AuthenticatedUser(1L, 100L, "fixture-teacher", "Fixture Teacher", UserRole.TEACHER));
        org.mockito.Mockito.when(storage.loadAndVerify(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        org.mockito.Mockito.when(storage.identity(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new FileStorageService.StoredFileIdentity(10L, "a".repeat(64), "2026-08-28T02:00:00Z"));
    }

    @org.junit.jupiter.api.Test
    void specificationEndpointsFailClosedWithoutCurrentUser() throws Exception {
        org.mockito.Mockito.when(currentUserService.currentUser()).thenReturn(java.util.Optional.empty());
        mockMvc.perform(get("/api/v1/projects/{projectId}/ppt-specifications", 999L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnboundSpecificationWriteBeforePersistence() throws Exception {
        long projectId = createProject();
        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications", projectId)
                .contentType(MediaType.APPLICATION_JSON).content(payload("首个标题", true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("WRITE Specification templateProfileId is invalid"));
    }

    @Test
    void validConfirmedProfileCanEnterReviewThroughStrongGate() throws Exception {
        long projectId = createProject();
        FixtureBinding binding = createConfirmedProfile(projectId);
        JsonNode created = body(mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(payloadForProfile("有效绑定", true, binding, null, null)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long versionId = created.path("data").path("id").asLong();
        String checksum = created.path("data").path("checksum").asText();
        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications/{versionId}/submit-review", projectId, versionId)
                        .contentType(MediaType.APPLICATION_JSON).content(action(checksum)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEW"));
    }

    @Test
    void confirmedProfileWithoutAnalyzerProvenanceCannotBeWritten() throws Exception {
        long projectId = createProject();
        FixtureBinding binding = createConfirmedProfile(projectId);
        TemplateProfileVersion profile = profileRepository.findById(Long.parseLong(binding.profileId())).orElseThrow();
        profile.setOrigin(TemplateProfileOrigin.MANUAL_DRAFT);
        profileRepository.saveAndFlush(profile);

        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadForProfile("手工确认但无分析链", true, binding, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("WRITE Specification requires a complete Analyzer-backed Template Profile"));
    }

    @Test
    void returningReviewCreatesNewDraftAndPreservesOldReview() throws Exception {
        long projectId = createProject();
        FixtureBinding binding = createConfirmedProfile(projectId);
        JsonNode created = body(mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(payloadForProfile("需审核", true, binding, null, null))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String versionId = created.get("data").get("id").asText(); String checksum = created.get("data").get("checksum").asText();
        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications/{versionId}/submit-review", projectId, versionId)
                        .contentType(MediaType.APPLICATION_JSON).content(action(checksum))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications/{versionId}/return-to-draft", projectId, versionId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedChecksum\":\"" + checksum + "\",\"reason\":\"请补充例子\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(2)).andExpect(jsonPath("$.data.returnedFromVersion").value(1));

        mockMvc.perform(get("/api/v1/projects/{projectId}/ppt-specifications", projectId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.versions.length()").value(2))
                .andExpect(jsonPath("$.data.versions[0].status").value("REVIEW"))
                .andExpect(jsonPath("$.data.versions[1].status").value("DRAFT"));
    }

    @Test
    void checksumTamperingIsRejectedBeforeLock() throws Exception {
        long projectId = createProject();
        FixtureBinding binding = createConfirmedProfile(projectId);
        JsonNode created = body(mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(payloadForProfile("完整性测试", true, binding, null, null))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long id = created.get("data").get("id").asLong(); String checksum = created.get("data").get("checksum").asText();
        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications/{versionId}/submit-review", projectId, id)
                        .contentType(MediaType.APPLICATION_JSON).content(action(checksum))).andExpect(status().isOk());
        PptSpecificationVersion entity = versionRepository.findById(id).orElseThrow(); entity.setChecksum("0".repeat(64)); versionRepository.saveAndFlush(entity);
        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications/{versionId}/lock", projectId, id)
                .contentType(MediaType.APPLICATION_JSON).content(action(checksum))).andExpect(status().isConflict());
    }

    @Test
    void lockedSpecificationUsesRealAssetManifestAfterRevokeAndRollsBackBeforePersistingLock() throws Exception {
        long projectId = createProject();
        FixtureBinding binding = createConfirmedProfile(projectId);
        String payload = payloadForProfile("素材锁定门禁", true, binding, null, null)
                .replace("\"assetRequirements\":[]", "\"assetRequirements\":[{\"assetId\":\"hero\",\"assetType\":\"IMAGE\",\"source\":\"uploaded-material:1\",\"approvalStatus\":\"APPROVED\",\"required\":true,\"placementIntent\":\"IMAGE\"}]");
        JsonNode created = body(mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications", projectId)
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long versionId = created.path("data").path("id").asLong();
        String checksum = created.path("data").path("checksum").asText();
        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications/{versionId}/submit-review", projectId, versionId)
                .contentType(MediaType.APPLICATION_JSON).content(action(checksum))).andExpect(status().isOk());
        String reviewChecksum = body(mockMvc.perform(get("/api/v1/projects/{projectId}/ppt-specifications", projectId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("latest").path("checksum").asText();
        CandidateAsset candidate = candidateRepository.saveAndFlush(candidate(projectId));
        assetService.approve(projectId, candidate.getId(), new com.auvdidao.a12teachingagent.asset.dto.AssetDtos.ReviewRequest("approve", candidate.getSha256()));
        assetService.revoke(projectId, candidate.getId(), new com.auvdidao.a12teachingagent.asset.dto.AssetDtos.ReviewRequest("withdraw", candidate.getSha256()));

        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications/{versionId}/lock", projectId, versionId)
                .contentType(MediaType.APPLICATION_JSON).content(action(reviewChecksum)))
                .andExpect(status().isConflict());
        assertThat(candidateRepository.findById(candidate.getId()).orElseThrow().getStatus()).isEqualTo(AssetStatus.REVOKED);
        assertThat(manifestRepository.findByProjectIdAndAssetKeyAndStatus(projectId, "hero", ManifestStatus.ACTIVE)).isEmpty();
        assertThat(manifestRepository.findByProjectIdAndAssetKeyAndStatus(projectId, "hero", ManifestStatus.REVOKED)).hasSize(2);
        assertThat(auditRepository.findByProjectIdAndAssetIdOrderByCreatedAtAsc(projectId, candidate.getId())).hasSize(2);
        assertThat(body(mockMvc.perform(get("/api/v1/projects/{projectId}/ppt-specifications", projectId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("latest").path("status").asText())
                .isEqualTo("REVIEW");
    }

    private CandidateAsset candidate(long projectId) {
        CandidateAsset value = new CandidateAsset();
        value.setProjectId(projectId); value.setOwnerUserId(100L); value.setAssetKey("hero"); value.setVersionNumber(1);
        value.setRequirementKey("hero-image"); value.setPlacementIntent("IMAGE"); value.setSourceType("TEACHER_UPLOAD");
        value.setSourceReference("uploaded-material:1"); value.setStorageKey("fixture/hero.png"); value.setOriginalFileReference("1");
        value.setMimeType("image/png"); value.setFileSize(10L); value.setSha256("a".repeat(64)); value.setProvider("UPLOAD");
        value.setModel("teacher-upload"); value.setStatus(AssetStatus.IN_REVIEW); return value;
    }

    @Test
    void planningProposalCannotOverwriteTeacherEditedDraft() throws Exception {
        long projectId = createProject();
        FixtureBinding binding = createConfirmedProfile(projectId);
        JsonNode created = body(mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(payloadForProfile("教师草稿", true, binding, null, null))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String id = created.get("data").get("id").asText(); String checksum = created.get("data").get("checksum").asText(); long entityVersion = created.get("data").get("entityVersion").asLong();
        JsonNode edited = body(mockMvc.perform(put("/api/v1/projects/{projectId}/ppt-specifications/{versionId}", projectId, id)
                        .contentType(MediaType.APPLICATION_JSON).content(payloadForProfile("教师已经开始编辑", true, binding, checksum, entityVersion))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String editedChecksum = edited.get("data").get("checksum").asText();
        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications/proposals", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":1,\"baseChecksum\":\"" + editedChecksum + "\",\"operation\":\"PATCH\",\"specification\":" + payload("Agent 覆盖", true) + "}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/projects/{projectId}/ppt-specifications", projectId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.versions.length()").value(1))
                .andExpect(jsonPath("$.data.latest.slides[0].title").value("教师已经开始编辑"));
    }

    @Test
    void initialPlanningProposalCreatesFirstDraftVersion() throws Exception {
        long projectId = createProject();
        FixtureBinding binding = createConfirmedProfile(projectId);
        mockMvc.perform(post("/api/v1/projects/{projectId}/ppt-specifications/proposals", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"INITIAL_PROPOSAL\",\"specification\":" + payloadForProfile("Planning Agent 初稿", true, binding, null, null) + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    private long createProject() throws Exception {
        JsonNode response = body(mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"courseName\":\"数学\",\"chapterTitle\":\"函数\",\"targetStudents\":\"高中\",\"lessonDuration\":45,\"description\":\"specification test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return response.get("data").get("id").asLong();
    }

    private String payload(String title, boolean locked) { return payloadWithConcurrency(title, locked, null, null); }
    private String payloadWithConcurrency(String title, boolean locked, String checksum, Long entityVersion) {
        return payloadForProfile(title, locked, new FixtureBinding("profile-1", "a".repeat(64), 1, null), checksum, entityVersion);
    }
    private String payloadForProfile(String title, boolean locked, FixtureBinding binding, String checksum, Long entityVersion) {
        String suffix = checksum == null ? "" : ",\"expectedChecksum\":\"" + checksum + "\",\"expectedEntityVersion\":" + entityVersion;
        return "{\"contractVersion\":\"1.0.0\",\"templateProfileId\":\"" + binding.profileId + "\",\"templateProfileVersion\":" + binding.profileVersion + ",\"templateCapabilityViewVersion\":1,\"templateCapabilityViewChecksum\":\"" + binding.capabilityChecksum + "\",\"targetSlideCount\":1,\"slideCountTolerance\":0,\"locale\":\"zh-CN\",\"provider\":\"MOCK\",\"model\":\"proposal-boundary\",\"aiSupplementPolicy\":\"DISABLED\",\"slides\":[{\"slideId\":\"slide-1\",\"pageNumber\":1,\"title\":\"" + title + "\",\"teachingGoal\":\"理解函数定义\",\"semanticLayout\":{\"primaryRole\":\"content\",\"regions\":[]},\"contentBlocks\":[{\"blockId\":\"block-1\",\"type\":\"BODY\",\"content\":\"函数是两个变量之间的对应关系\",\"sourceType\":\"TEACHER\",\"sourceReference\":\"teacher-input\",\"locked\":" + locked + "}],\"assetRequirements\":[],\"provenance\":[{\"sourceType\":\"TEACHER\",\"sourceReference\":\"teacher-input\"}],\"notes\":\"\"}]" + suffix + "}";
    }
    private String action(String checksum) { return "{\"expectedChecksum\":\"" + checksum + "\"}"; }
    private JsonNode body(String json) throws Exception { return objectMapper.readTree(json); }

    private FixtureBinding createConfirmedProfile(long projectId) {
        Template template = new Template(); template.setProjectId(projectId); template.setName("fixture-template"); template.setCreatedByUserId(100L);
        template = templateRepository.saveAndFlush(template);
        TemplateSourceVersion source = new TemplateSourceVersion(); source.setTemplateId(template.getId()); source.setProjectId(projectId); source.setVersionNumber(1);
        source.setCreatedByUserId(100L); source.setOriginalFilename("fixture.pptx"); source.setStoredFilename("fixture.pptx"); source.setStorageKey("fixture/fixture.pptx");
        source.setContentType("application/vnd.openxmlformats-officedocument.presentationml.presentation"); source.setFileSize(1L); source.setSha256("0".repeat(64));
        source.setParseStatus(TemplateProcessingStatus.SUCCEEDED); source.setRenderStatus(TemplateProcessingStatus.SUCCEEDED); source.setAnalysisStatus(TemplateProcessingStatus.SUCCEEDED);
        source = sourceRepository.saveAndFlush(source);
        String snapshotJson = "{\"slideCount\":1}"; String snapshotChecksum = sha256(snapshotJson);
        TemplateStructuralSnapshot snapshot = new TemplateStructuralSnapshot(); snapshot.setTemplateId(template.getId()); snapshot.setSourceVersionId(source.getId()); snapshot.setSlideCount(1);
        snapshot.setChecksum(snapshotChecksum); snapshot.setSnapshotJson(snapshotJson); snapshotRepository.saveAndFlush(snapshot);
        String profileJson = "{\"displayName\":\"fixture\"}"; String capabilityJson = "{\"displayName\":\"fixture\"}"; String profileChecksum = sha256(profileJson); String capabilityChecksum = sha256(capabilityJson);
        TemplateProfileVersion profile = new TemplateProfileVersion(); profile.setTemplateId(template.getId()); profile.setProjectId(projectId); profile.setSourceVersionId(source.getId()); profile.setVersionNumber(1); profile.setCreatedByUserId(100L); profile.setOwnedByTeacherId(100L);
        profile.setOrigin(TemplateProfileOrigin.ANALYZER_CANDIDATE); profile.setParserSnapshotChecksum(snapshotChecksum); profile.setRendererStatus(TemplateProcessingStatus.SUCCEEDED); profile.setAnalyzerStatus(TemplateProcessingStatus.SUCCEEDED);
        profile.setStatus(TemplateProfileStatus.CONFIRMED); profile.setChecksum(profileChecksum); profile.setConfirmedChecksum(profileChecksum); profile.setCapabilityViewVersion(1); profile.setCapabilityViewChecksum(capabilityChecksum); profile.setProfileJson(profileJson); profile.setCapabilityViewJson(capabilityJson);
        profile = profileRepository.saveAndFlush(profile);
        return new FixtureBinding(profile.getId().toString(), capabilityChecksum, 1, profileChecksum);
    }

    private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private record FixtureBinding(String profileId, String capabilityChecksum, int profileVersion, String profileChecksum) { }
}
