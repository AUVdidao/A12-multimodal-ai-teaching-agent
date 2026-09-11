package com.auvdidao.a12teachingagent.asset;

import com.auvdidao.a12teachingagent.common.exception.UnauthorizedException;
import com.auvdidao.a12teachingagent.domain.asset.CandidateAsset;
import com.auvdidao.a12teachingagent.domain.asset.repository.ApprovedAssetManifestRepository;
import com.auvdidao.a12teachingagent.domain.asset.repository.CandidateAssetRepository;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AssetControllerHttpTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private CandidateAssetRepository candidates;
    @Autowired private ApprovedAssetManifestRepository manifests;
    @MockBean private CurrentUserService users;
    @MockBean private ProjectAccessService access;

    @BeforeEach
    void teacherContext() {
        when(users.currentUser()).thenReturn(java.util.Optional.of(new AuthenticatedUser(1L, 9L, "teacher", "Teacher", UserRole.TEACHER)));
        when(users.requireRole(UserRole.TEACHER)).thenReturn(new AuthenticatedUser(1L, 9L, "teacher", "Teacher", UserRole.TEACHER));
        when(access.requireAuthenticatedTeacherAccess(7L)).thenReturn(new AuthenticatedUser(1L, 9L, "teacher", "Teacher", UserRole.TEACHER));
    }

    @Test
    void approveWithoutCurrentHashIsBadRequestAndLeavesCandidateAndManifestUntouched() throws Exception {
        CandidateAsset candidate = candidates.saveAndFlush(candidate());

        mockMvc.perform(post("/api/v1/projects/7/asset-candidates/{assetId}/approve", candidate.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"review\"}"))
                .andExpect(status().isBadRequest());

        CandidateAsset unchanged = candidates.findById(candidate.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(AssetStatus.IN_REVIEW);
        assertThat(manifests.findByProjectIdOrderByAssetKeyAscManifestVersionDesc(7L)).isEmpty();
    }

    @Test
    void assetHttpBoundaryRejectsMissingIdentityBeforeServiceSideEffects() throws Exception {
        when(access.requireAuthenticatedTeacherAccess(anyLong())).thenThrow(new UnauthorizedException("Authentication is required"));
        mockMvc.perform(post("/api/v1/projects/7/asset-candidates/999/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"review\",\"expectedSha256\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private CandidateAsset candidate() {
        CandidateAsset value = new CandidateAsset();
        value.setProjectId(7L); value.setOwnerUserId(9L); value.setAssetKey("hero"); value.setVersionNumber(1);
        value.setRequirementKey("hero-image"); value.setPlacementIntent("IMAGE"); value.setSourceType("TEACHER_UPLOAD");
        value.setSourceReference("uploaded-material:1"); value.setStorageKey("missing/hero.png"); value.setOriginalFileReference("1");
        value.setMimeType("image/png"); value.setFileSize(10L); value.setSha256("a".repeat(64)); value.setProvider("UPLOAD");
        value.setModel("teacher-upload"); value.setStatus(AssetStatus.IN_REVIEW); return value;
    }
}
