package com.auvdidao.a12teachingagent.asset;

import com.auvdidao.a12teachingagent.asset.dto.AssetDtos;
import com.auvdidao.a12teachingagent.domain.asset.CandidateAsset;
import com.auvdidao.a12teachingagent.domain.asset.repository.ApprovedAssetManifestRepository;
import com.auvdidao.a12teachingagent.domain.asset.repository.AssetAuditEventRepository;
import com.auvdidao.a12teachingagent.domain.asset.repository.CandidateAssetRepository;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AssetControllerLifecycleIntegrationTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projects;
    @Autowired private CandidateAssetRepository candidates;
    @Autowired private ApprovedAssetManifestRepository manifests;
    @Autowired private AssetAuditEventRepository audits;
    @Autowired private UploadedMaterialRepository materials;
    @MockBean private CurrentUserService users;
    @MockBean private FileStorageService storage;

    private Long projectId;

    @BeforeEach
    void teacherOwnerContext() {
        Project project = new Project();
        project.setProjectName("asset-http"); project.setCourseName("数学"); project.setChapterTopic("函数");
        project.setTargetAudience("高中"); project.setLessonDurationMinutes(45); project.setProjectDescription("asset http");
        project.setOwnerUserId(9L); project.setGenerationMode(GenerationMode.STANDARD); project.setStatus(ProjectStatus.CREATED);
        projectId = projects.saveAndFlush(project).getId();
        when(users.currentUser()).thenReturn(Optional.of(new AuthenticatedUser(1L, 9L, "teacher", "Teacher", UserRole.TEACHER)));
        when(users.requireRole(UserRole.TEACHER)).thenReturn(new AuthenticatedUser(1L, 9L, "teacher", "Teacher", UserRole.TEACHER));
        when(storage.loadAndVerify(anyString(), anyString())).thenReturn(null);
        when(storage.identity(anyString())).thenAnswer(invocation -> {
            String storageKey = invocation.getArgument(0, String.class);
            String sha256 = storageKey.contains("hero-2") ? HASH_B : HASH_A;
            return new FileStorageService.StoredFileIdentity(10L, sha256, "2026-08-28T02:00:00Z");
        });
    }

    @Test
    void ownerCanTraverseCreateListSubmitApproveRevokeAndManifestWithRealServiceWiring() throws Exception {
        UploadedMaterial material = material();
        String createBody = "{\"assetKey\":\"hero\",\"requirementKey\":\"hero-image\",\"placementIntent\":\"IMAGE\",\"materialId\":" + material.getId() + ",\"sha256\":\"" + HASH_A + "\"}";
        JsonNode created = body(mockMvc.perform(post("/api/v1/projects/{projectId}/asset-candidates", projectId)
                .contentType(MediaType.APPLICATION_JSON).content(createBody)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long assetId = created.path("data").path("id").asLong();

        mockMvc.perform(post("/api/v1/projects/{projectId}/asset-candidates/{assetId}/submit-review", projectId, assetId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/projects/{projectId}/asset-candidates/{assetId}/approve", projectId, assetId)
                .contentType(MediaType.APPLICATION_JSON).content(review(HASH_A, "approve"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/projects/{projectId}/asset-candidates", projectId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/projects/{projectId}/asset-candidates/manifest", projectId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/projects/{projectId}/asset-candidates/{assetId}/revoke", projectId, assetId)
                .contentType(MediaType.APPLICATION_JSON).content(review(HASH_A, "withdraw"))).andExpect(status().isOk());

        assertThat(candidates.findById(assetId).orElseThrow().getStatus()).isEqualTo(AssetStatus.REVOKED);
        assertThat(manifests.findByProjectIdAndAssetKeyAndStatus(projectId, "hero", ManifestStatus.ACTIVE)).isEmpty();
        assertThat(manifests.findByProjectIdAndAssetKeyAndStatus(projectId, "hero", ManifestStatus.REVOKED)).hasSize(2);
        assertThat(audits.findByProjectIdAndAssetIdOrderByCreatedAtAsc(projectId, assetId)).hasSize(4);
    }

    @Test
    void rejectEntryIsWiredAndChangesOnlyTargetCandidate() throws Exception {
        CandidateAsset candidate = candidates.saveAndFlush(candidate(1, HASH_A, AssetStatus.IN_REVIEW));
        mockMvc.perform(post("/api/v1/projects/{projectId}/asset-candidates/{assetId}/reject", projectId, candidate.getId())
                .contentType(MediaType.APPLICATION_JSON).content(review(HASH_A, "not suitable"))).andExpect(status().isOk());
        assertThat(candidates.findById(candidate.getId()).orElseThrow().getStatus()).isEqualTo(AssetStatus.REJECTED);
        assertThat(manifests.findByProjectIdOrderByAssetKeyAscManifestVersionDesc(projectId)).isEmpty();
        assertThat(audits.findByProjectIdAndAssetIdOrderByCreatedAtAsc(projectId, candidate.getId())).hasSize(1);
    }

    @Test
    void oldCandidateCannotRevokeNewerActiveManifestThroughHttpEntry() throws Exception {
        CandidateAsset oldCandidate = candidates.saveAndFlush(candidate(1, HASH_A, AssetStatus.CANDIDATE));
        CandidateAsset newCandidate = candidates.saveAndFlush(candidate(2, HASH_B, AssetStatus.CANDIDATE));
        approve(oldCandidate.getId(), HASH_A);
        approve(newCandidate.getId(), HASH_B);

        mockMvc.perform(post("/api/v1/projects/{projectId}/asset-candidates/{assetId}/revoke", projectId, oldCandidate.getId())
                .contentType(MediaType.APPLICATION_JSON).content(review(HASH_A, "old"))).andExpect(status().isConflict());

        assertThat(candidates.findById(oldCandidate.getId()).orElseThrow().getStatus()).isEqualTo(AssetStatus.APPROVED);
        assertThat(candidates.findById(newCandidate.getId()).orElseThrow().getStatus()).isEqualTo(AssetStatus.APPROVED);
        var active = manifests.findByProjectIdAndAssetKeyAndStatus(projectId, "hero", ManifestStatus.ACTIVE);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getCandidateAssetId()).isEqualTo(newCandidate.getId());
        assertThat(audits.findByProjectIdAndAssetIdOrderByCreatedAtAsc(projectId, oldCandidate.getId())).hasSize(2);
    }

    @Test
    void malformedApprovalAndMaterialBindingFailWithoutCandidateManifestOrAuditSideEffects() throws Exception {
        CandidateAsset candidate = candidates.saveAndFlush(candidate(1, HASH_A, AssetStatus.IN_REVIEW));
        mockMvc.perform(post("/api/v1/projects/{projectId}/asset-candidates/{assetId}/approve", projectId, candidate.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"missing hash\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/projects/{projectId}/asset-candidates", projectId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"assetKey\":\"other\",\"requirementKey\":\"image\",\"placementIntent\":\"IMAGE\",\"materialId\":999999,\"sha256\":\"" + HASH_A + "\"}"))
                .andExpect(status().isNotFound());
        assertThat(candidates.findById(candidate.getId()).orElseThrow().getStatus()).isEqualTo(AssetStatus.IN_REVIEW);
        assertThat(manifests.findByProjectIdOrderByAssetKeyAscManifestVersionDesc(projectId)).isEmpty();
        assertThat(audits.findByProjectIdAndAssetIdOrderByCreatedAtAsc(projectId, candidate.getId())).isEmpty();
    }

    @Test
    void unauthenticatedNonTeacherAndCrossOwnerAreRejectedByActualProjectAccess() throws Exception {
        when(users.currentUser()).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/projects/{projectId}/asset-candidates", projectId)).andExpect(status().isUnauthorized());

        when(users.currentUser()).thenReturn(Optional.of(new AuthenticatedUser(2L, 9L, "student", "Student", UserRole.STUDENT)));
        mockMvc.perform(get("/api/v1/projects/{projectId}/asset-candidates", projectId)).andExpect(status().isForbidden());

        Project another = new Project(); another.setProjectName("other"); another.setCourseName("语文"); another.setChapterTopic("阅读");
        another.setTargetAudience("高中"); another.setLessonDurationMinutes(45); another.setProjectDescription("other"); another.setOwnerUserId(10L);
        another.setGenerationMode(GenerationMode.STANDARD); another.setStatus(ProjectStatus.CREATED);
        Long otherProjectId = projects.saveAndFlush(another).getId();
        when(users.currentUser()).thenReturn(Optional.of(new AuthenticatedUser(1L, 9L, "teacher", "Teacher", UserRole.TEACHER)));
        mockMvc.perform(get("/api/v1/projects/{projectId}/asset-candidates", otherProjectId)).andExpect(status().isForbidden());
    }

    private void approve(long assetId, String hash) throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/asset-candidates/{assetId}/submit-review", projectId, assetId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/projects/{projectId}/asset-candidates/{assetId}/approve", projectId, assetId)
                .contentType(MediaType.APPLICATION_JSON).content(review(hash, "approve"))).andExpect(status().isOk());
    }

    private UploadedMaterial material() {
        UploadedMaterial value = new UploadedMaterial(); value.setProjectId(projectId); value.setFileName("hero.png"); value.setOriginalFileName("hero.png");
        value.setFileExtension("png"); value.setContentType("image/png"); value.setMaterialDescription("fixture"); value.setFileType(MaterialFileType.PNG);
        value.setFilePath("fixture/hero.png"); value.setFileSize(10L); value.setUploadStatus(UploadStatus.UPLOADED); value.setParseStatus(MaterialParseStatus.NOT_STARTED);
        return materials.saveAndFlush(value);
    }

    private CandidateAsset candidate(int version, String hash, AssetStatus status) {
        CandidateAsset value = new CandidateAsset(); value.setProjectId(projectId); value.setOwnerUserId(9L); value.setAssetKey("hero"); value.setVersionNumber(version);
        value.setRequirementKey("hero-image"); value.setPlacementIntent("IMAGE"); value.setSourceType("TEACHER_UPLOAD"); value.setSourceReference("uploaded-material:1");
        value.setStorageKey("fixture/hero-" + version + ".png"); value.setOriginalFileReference("1"); value.setMimeType("image/png"); value.setFileSize(10L);
        value.setSha256(hash); value.setProvider("UPLOAD"); value.setModel("teacher-upload"); value.setStatus(status); return value;
    }

    private String review(String hash, String reason) { return "{\"reason\":\"" + reason + "\",\"expectedSha256\":\"" + hash + "\"}"; }
    private JsonNode body(String json) throws Exception { return objectMapper.readTree(json); }
}
