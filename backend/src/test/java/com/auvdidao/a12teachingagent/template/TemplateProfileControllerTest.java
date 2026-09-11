package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileReviewRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProcessingRunRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateRenderedSlideSetRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateStructuralSnapshotRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateAnalysisResultRepository;
import com.auvdidao.a12teachingagent.domain.template.TemplateAnalysisResult;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateRenderedSlideSet;
import com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.sl.usermodel.ShapeType;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateProfileControllerTest {

    private static final String PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TemplateRepository templateRepository;
    @Autowired private TemplateSourceVersionRepository sourceRepository;
    @Autowired private TemplateProcessingRunRepository runRepository;
    @Autowired private TemplateStructuralSnapshotRepository snapshotRepository;
    @Autowired private TemplateRenderedSlideSetRepository renderedRepository;
    @SpyBean private TemplateProfileVersionRepository profileRepository;
    @Autowired private TemplateProfileReviewRepository reviewRepository;
    @Autowired private TemplateAnalysisResultRepository analysisResultRepository;
    @MockBean private CurrentUserService currentUserService;

    private Long projectId;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(currentUserService.currentUser()).thenReturn(java.util.Optional.of(
                new AuthenticatedUser(1L, 100L, "fixture-teacher", "Fixture Teacher", UserRole.TEACHER)));
        org.mockito.Mockito.when(currentUserService.requireRole(UserRole.TEACHER)).thenReturn(
                new AuthenticatedUser(1L, 100L, "fixture-teacher", "Fixture Teacher", UserRole.TEACHER));
        Project project = new Project();
        project.setProjectName("真实模板测试项目");
        project.setOwnerUserId(100L);
        project.setStatus(ProjectStatus.CREATED);
        projectId = projectRepository.saveAndFlush(project).getId();
    }

    @Test
    void templateAndProfileEndpointsFailClosedWithoutCurrentUser() throws Exception {
        org.mockito.Mockito.when(currentUserService.currentUser()).thenReturn(java.util.Optional.empty());
        mockMvc.perform(get("/api/projects/{projectId}/templates", projectId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profileDetailFailsClosedWhenPersistedJsonIsTampered() throws Exception {
        JsonNode uploaded = objectMapper.readTree(upload(pptxBytes(1), "tamper.pptx").andReturn().getResponse().getContentAsString()).path("data");
        long templateId = uploaded.path("template").path("id").asLong();
        long sourceId = uploaded.path("sourceVersions").get(0).path("id").asLong();
        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceVersionId\":" + sourceId + ",\"profile\":" + profileJson("篡改检测") + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        var entity = profileRepository.findById(created.path("id").asLong()).orElseThrow();
        entity.setProfileJson(entity.getProfileJson().replace("篡改检测", "被篡改"));
        profileRepository.saveAndFlush(entity);
        mockMvc.perform(get("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}", projectId, templateId, entity.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("integrity")));
    }

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.reset(profileRepository);
        reviewRepository.deleteAll();
        profileRepository.deleteAll();
        analysisResultRepository.deleteAll();
        snapshotRepository.deleteAll();
        renderedRepository.deleteAll();
        runRepository.deleteAll();
        sourceRepository.deleteAll();
        templateRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void analyzerCandidateUsesServerOwnedCandidateAndPersistsItsCanonicalBinding() throws Exception {
        JsonNode uploaded = objectMapper.readTree(upload(pptxBytes(1), "analyzer-owned.pptx").andReturn().getResponse().getContentAsString()).path("data");
        long templateId = uploaded.path("template").path("id").asLong();
        long sourceId = uploaded.path("sourceVersions").get(0).path("id").asLong();
        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/parse", projectId, templateId, sourceId))
                .andExpect(status().isOk());

        TemplateAnalysisResult analysis = successfulAnalysis(templateId, sourceId, "server-owned");
        String candidate = profileJson("server-owned");
        String request = "{\"sourceVersionId\":" + sourceId
                + ",\"origin\":\"ANALYZER_CANDIDATE\",\"parserSnapshotChecksum\":\""
                + analysis.getParserSnapshotChecksum() + "\",\"analysisRunId\":\""
                + analysis.getAnalysisRunId() + "\",\"profile\":" + candidate + "}";

        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.origin", is("ANALYZER_CANDIDATE")))
                .andExpect(jsonPath("$.data.status", is("READY")))
                .andExpect(jsonPath("$.data.profile.displayName", is("server-owned")))
                .andReturn().getResponse().getContentAsString()).path("data");

        TemplateProfileVersion persisted = profileRepository.findById(response.path("id").asLong()).orElseThrow();
        String expected = TemplateChecksum.canonicalJson(objectMapper, objectMapper.readTree(candidate));
        org.junit.jupiter.api.Assertions.assertEquals(expected, persisted.getProfileJson());
        org.junit.jupiter.api.Assertions.assertEquals(expected, persisted.getAnalyzerProposalJson());
        org.junit.jupiter.api.Assertions.assertEquals(TemplateChecksum.sha256(expected), persisted.getChecksum());
        org.junit.jupiter.api.Assertions.assertEquals(TemplateChecksum.sha256(expected), analysis.getCandidateProfileSha256());
        org.junit.jupiter.api.Assertions.assertNotNull(persisted.getEngineNativeProfileJson());
        org.junit.jupiter.api.Assertions.assertEquals(TemplateChecksum.sha256(persisted.getEngineNativeProfileJson()),
                persisted.getEngineNativeProfileChecksum());
        JsonNode nativeProfile = objectMapper.readTree(persisted.getEngineNativeProfileJson());
        org.junit.jupiter.api.Assertions.assertEquals(String.valueOf(persisted.getId()), nativeProfile.path("profileId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(String.valueOf(persisted.getTemplateId()), nativeProfile.path("templateId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("READY", nativeProfile.path("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(nativeProfile.path("templatePageReferences").size() > 0);
        org.junit.jupiter.api.Assertions.assertTrue(nativeProfile.path("components").size() > 0);
        org.junit.jupiter.api.Assertions.assertEquals("COVER",
                nativeProfile.path("templatePageReferences").get(0).path("semanticRole").asText());
        org.junit.jupiter.api.Assertions.assertEquals("ANALYZER_PROFILE_COVER_FIRST_PAGE",
                nativeProfile.path("templatePageReferences").get(0).path("semanticRoleSource").asText());
        org.junit.jupiter.api.Assertions.assertEquals("EXECUTION_READY",
                nativeProfile.path("components").get(0).path("executionEligibility").asText());
        org.junit.jupiter.api.Assertions.assertFalse(nativeProfile.path("components").get(0).path("teacherConfirmed").asBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(nativeProfile.path("components").get(0).has("profileJson"));
        JsonNode decoration = java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(nativeProfile.path("preservedNativeObjects").elements(), 0), false)
                .filter(component -> component.path("stableNativeReference").asText().equals("slide-1.shape-3"))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("PRESERVE_ONLY", decoration.path("classification").asText());
        org.junit.jupiter.api.Assertions.assertEquals("FIXED_DECORATION", decoration.path("classificationReason").asText());
        org.junit.jupiter.api.Assertions.assertTrue(decoration.path("fixedDecoration").asBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(decoration.path("editable").asBoolean());
        JsonNode negativePlaceholder = java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(nativeProfile.path("preservedNativeObjects").elements(), 0), false)
                .filter(component -> "slide-1.shape-4".equals(component.path("stableNativeReference").asText()))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(
                "CONTENT_PLACEHOLDER_NEGATIVE_X",
                negativePlaceholder.path("classificationReason").asText());
        org.junit.jupiter.api.Assertions.assertEquals("PRESERVE_ONLY", negativePlaceholder.path("classification").asText());
        org.junit.jupiter.api.Assertions.assertTrue(negativePlaceholder.path("originalBounds").path("x").asDouble() < 0d);
        org.junit.jupiter.api.Assertions.assertTrue(nativeProfile.path("components").size() < 500);
    }

    @Test
    void analyzerCandidateRejectsDifferentClientProfileWithoutCreatingAnyProfile() throws Exception {
        JsonNode uploaded = objectMapper.readTree(upload(pptxBytes(1), "analyzer-mismatch.pptx").andReturn().getResponse().getContentAsString()).path("data");
        long templateId = uploaded.path("template").path("id").asLong();
        long sourceId = uploaded.path("sourceVersions").get(0).path("id").asLong();
        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/parse", projectId, templateId, sourceId))
                .andExpect(status().isOk());

        TemplateAnalysisResult analysis = successfulAnalysis(templateId, sourceId, "server-truth");
        long profilesBefore = profileRepository.count();
        long analysesBefore = analysisResultRepository.count();
        String originalCandidate = analysis.getCandidateProfileJson();
        String originalCandidateHash = analysis.getCandidateProfileSha256();
        String request = "{\"sourceVersionId\":" + sourceId
                + ",\"origin\":\"ANALYZER_CANDIDATE\",\"parserSnapshotChecksum\":\""
                + analysis.getParserSnapshotChecksum() + "\",\"analysisRunId\":\""
                + analysis.getAnalysisRunId() + "\",\"profile\":" + profileJson("client-forged") + "}";

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("does not match")))
                .andExpect(jsonPath("$.message", not(containsString("Exception"))));

        org.junit.jupiter.api.Assertions.assertEquals(profilesBefore, profileRepository.count());
        org.junit.jupiter.api.Assertions.assertEquals(analysesBefore, analysisResultRepository.count());
        TemplateAnalysisResult unchanged = analysisResultRepository.findByAnalysisRunId(analysis.getAnalysisRunId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(originalCandidate, unchanged.getCandidateProfileJson());
        org.junit.jupiter.api.Assertions.assertEquals(originalCandidateHash, unchanged.getCandidateProfileSha256());
    }

    @Test
    void analyzerCandidateFailsClosedWhenServerCandidateHashIsTampered() throws Exception {
        JsonNode uploaded = objectMapper.readTree(upload(pptxBytes(1), "analyzer-hash-tamper.pptx").andReturn().getResponse().getContentAsString()).path("data");
        long templateId = uploaded.path("template").path("id").asLong();
        long sourceId = uploaded.path("sourceVersions").get(0).path("id").asLong();
        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/parse", projectId, templateId, sourceId))
                .andExpect(status().isOk());

        TemplateAnalysisResult analysis = successfulAnalysis(templateId, sourceId, "server-tamper");
        analysis.setCandidateProfileSha256("0".repeat(64));
        analysisResultRepository.saveAndFlush(analysis);
        long profilesBefore = profileRepository.count();
        String request = "{\"sourceVersionId\":" + sourceId
                + ",\"origin\":\"ANALYZER_CANDIDATE\",\"parserSnapshotChecksum\":\""
                + analysis.getParserSnapshotChecksum() + "\",\"analysisRunId\":\""
                + analysis.getAnalysisRunId() + "\",\"profile\":" + profileJson("server-tamper") + "}";

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("integrity")))
                .andExpect(jsonPath("$.message", not(containsString("Exception"))));
        org.junit.jupiter.api.Assertions.assertEquals(profilesBefore, profileRepository.count());
    }

    @Test
    void analyzerCandidateCreationIsIdempotentAndDifferentPayloadCannotCreateAnotherVersion() throws Exception {
        JsonNode uploaded = objectMapper.readTree(upload(pptxBytes(1), "analyzer-idempotent.pptx").andReturn().getResponse().getContentAsString()).path("data");
        long templateId = uploaded.path("template").path("id").asLong();
        long sourceId = uploaded.path("sourceVersions").get(0).path("id").asLong();
        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/parse", projectId, templateId, sourceId))
                .andExpect(status().isOk());

        TemplateAnalysisResult analysis = successfulAnalysis(templateId, sourceId, "idempotent");
        String request = analyzerCandidateRequest(sourceId, analysis, profileJson("idempotent"));
        JsonNode first = objectMapper.readTree(mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        long firstId = first.path("id").asLong();

        JsonNode second = objectMapper.readTree(mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(firstId, second.path("id").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(1, profileRepository.count());

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(analyzerCandidateRequest(sourceId, analysis, profileJson("different-client-payload"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("does not match")));
        org.junit.jupiter.api.Assertions.assertEquals(1, profileRepository.count());
    }

    @Test
    void analyzerCandidateIsImmutableButConfirmedAndManualDraftEditSemanticsRemainBounded() throws Exception {
        JsonNode uploaded = objectMapper.readTree(upload(pptxBytes(1), "analyzer-immutable.pptx").andReturn().getResponse().getContentAsString()).path("data");
        long templateId = uploaded.path("template").path("id").asLong();
        long sourceId = uploaded.path("sourceVersions").get(0).path("id").asLong();
        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/parse", projectId, templateId, sourceId))
                .andExpect(status().isOk());
        TemplateAnalysisResult analysis = successfulAnalysis(templateId, sourceId, "immutable");
        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(analyzerCandidateRequest(sourceId, analysis, profileJson("immutable"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        long profileId = created.path("id").asLong();
        TemplateProfileVersion original = profileRepository.findById(profileId).orElseThrow();
        String originalJson = original.getProfileJson();
        String originalChecksum = original.getChecksum();
        String originalAnalysisRunId = original.getAnalysisRunId();
        long reviewsBefore = reviewRepository.count();
        long profilesBefore = profileRepository.count();

        mockMvc.perform(put("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}", projectId, templateId, profileId)
                        .contentType(MediaType.APPLICATION_JSON).content(profileJson("edited-analyzer")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("ANALYZER_CANDIDATE")))
                .andExpect(jsonPath("$.message", not(containsString("Exception"))));

        TemplateProfileVersion unchanged = profileRepository.findById(profileId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(originalJson, unchanged.getProfileJson());
        org.junit.jupiter.api.Assertions.assertEquals(originalChecksum, unchanged.getChecksum());
        org.junit.jupiter.api.Assertions.assertEquals(originalAnalysisRunId, unchanged.getAnalysisRunId());
        org.junit.jupiter.api.Assertions.assertEquals(com.auvdidao.a12teachingagent.domain.template.TemplateProfileOrigin.ANALYZER_CANDIDATE, unchanged.getOrigin());
        org.junit.jupiter.api.Assertions.assertEquals(profilesBefore, profileRepository.count());
        org.junit.jupiter.api.Assertions.assertEquals(reviewsBefore, reviewRepository.count());

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}/review", projectId, templateId, profileId))
                .andExpect(status().isOk());
        String confirmedChecksum = profileRepository.findById(profileId).orElseThrow().getChecksum();
        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}/confirm", projectId, templateId, profileId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checksum\":\"" + confirmedChecksum + "\"}"))
                .andExpect(status().isOk());
        TemplateProfileVersion confirmed = profileRepository.findById(profileId).orElseThrow();
        JsonNode confirmedNative = objectMapper.readTree(confirmed.getEngineNativeProfileJson());
        org.junit.jupiter.api.Assertions.assertEquals("CONFIRMED", confirmedNative.path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(TemplateChecksum.sha256(confirmed.getEngineNativeProfileJson()),
                confirmed.getEngineNativeProfileChecksum());
        mockMvc.perform(put("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}", projectId, templateId, profileId)
                        .contentType(MediaType.APPLICATION_JSON).content(profileJson("edited-confirmed")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("CONFIRMED")));

        JsonNode manualUploaded = objectMapper.readTree(upload(pptxBytes(1), "manual-editable.pptx").andReturn().getResponse().getContentAsString()).path("data");
        long manualTemplateId = manualUploaded.path("template").path("id").asLong();
        long manualSourceId = manualUploaded.path("sourceVersions").get(0).path("id").asLong();
        JsonNode manual = objectMapper.readTree(mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, manualTemplateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceVersionId\":" + manualSourceId + ",\"profile\":" + profileJson("manual-original") + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        mockMvc.perform(put("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}", projectId, manualTemplateId, manual.path("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON).content(profileJson("manual-edited")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.displayName", is("manual-edited")));
    }

    @Test
    void analyzerCandidateUniqueConstraintRaceMapsToConflictWithoutPersistingProfile() throws Exception {
        JsonNode uploaded = objectMapper.readTree(upload(pptxBytes(1), "analyzer-race.pptx").andReturn().getResponse().getContentAsString()).path("data");
        long templateId = uploaded.path("template").path("id").asLong();
        long sourceId = uploaded.path("sourceVersions").get(0).path("id").asLong();
        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/parse", projectId, templateId, sourceId))
                .andExpect(status().isOk());
        TemplateAnalysisResult analysis = successfulAnalysis(templateId, sourceId, "race");
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("duplicate analyzer identity"))
                .when(profileRepository).saveAndFlush(org.mockito.ArgumentMatchers.any(TemplateProfileVersion.class));

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(analyzerCandidateRequest(sourceId, analysis, profileJson("race"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already exists")))
                .andExpect(jsonPath("$.message", not(containsString("duplicate"))))
                .andExpect(jsonPath("$.message", not(containsString("Exception"))));
        org.junit.jupiter.api.Assertions.assertEquals(0, profileRepository.count());
        org.junit.jupiter.api.Assertions.assertEquals(1, analysisResultRepository.count());
    }

    private TemplateAnalysisResult successfulAnalysis(long templateId, long sourceId, String displayName) throws Exception {
        TemplateSourceVersion source = sourceRepository.findById(sourceId).orElseThrow();
        source.setRenderStatus(TemplateProcessingStatus.SUCCEEDED);
        source.setAnalysisStatus(TemplateProcessingStatus.SUCCEEDED);
        sourceRepository.saveAndFlush(source);
        var snapshot = snapshotRepository.findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(sourceId).orElseThrow();

        TemplateRenderedSlideSet rendered = new TemplateRenderedSlideSet();
        rendered.setTemplateId(templateId);
        rendered.setSourceVersionId(sourceId);
        rendered.setStatus(TemplateProcessingStatus.SUCCEEDED);
        rendered.setSlideCount(1);
        rendered.setPreviewReference("template-renders/analyzer-fixture.pdf");
        rendered.setSourceSha256(source.getSha256());
        rendered.setOutputSha256("a".repeat(64));
        rendered.setOutputSizeBytes(128L);
        rendered.setAdapterVersion("fixture-v1");
        rendered = renderedRepository.saveAndFlush(rendered);

        String analysisRunId = "template-analysis-fixture-" + displayName;
        String candidate = TemplateChecksum.canonicalJson(objectMapper, objectMapper.readTree(profileJson(displayName)));
        TemplateAnalysisResult analysis = new TemplateAnalysisResult();
        analysis.setTemplateId(templateId);
        analysis.setProjectId(projectId);
        analysis.setSourceVersionId(sourceId);
        analysis.setOwnerUserId(100L);
        analysis.setAnalysisRunId(analysisRunId);
        analysis.setSourceSha256(source.getSha256());
        analysis.setParserSnapshotChecksum(snapshot.getChecksum());
        analysis.setRenderedSlideSetId(rendered.getId());
        analysis.setRenderedOutputSha256(rendered.getOutputSha256());
        analysis.setRenderedOutputSizeBytes(rendered.getOutputSizeBytes());
        analysis.setStatus(TemplateProcessingStatus.SUCCEEDED);
        analysis.setInputSha256("b".repeat(64));
        analysis.setOutputSha256("c".repeat(64));
        analysis.setCandidateProfileJson(candidate);
        analysis.setCandidateProfileSha256(TemplateChecksum.sha256(candidate));
        return analysisResultRepository.saveAndFlush(analysis);
    }

    private String analyzerCandidateRequest(long sourceId, TemplateAnalysisResult analysis, String profile) {
        return "{\"sourceVersionId\":" + sourceId
                + ",\"origin\":\"ANALYZER_CANDIDATE\",\"parserSnapshotChecksum\":\""
                + analysis.getParserSnapshotChecksum() + "\",\"analysisRunId\":\""
                + analysis.getAnalysisRunId() + "\",\"profile\":" + profile + "}";
    }

    @Test
    void uploadDeduplicatesSameBytesAndParserPersistsSnapshotWhileOtherAdaptersStayHonest() throws Exception {
        byte[] bytes = pptxBytes(2);
        String first = upload(bytes, "school-template.pptx").andReturn().getResponse().getContentAsString();
        long templateId = objectMapper.readTree(first).path("data").path("template").path("id").asLong();
        long sourceId = objectMapper.readTree(first).path("data").path("sourceVersions").get(0).path("id").asLong();

        mockMvc.perform(multipart("/api/projects/{projectId}/templates", projectId)
                        .file(new MockMultipartFile("file", "renamed.pptx", PPTX_MIME, bytes))
                        .param("name", "school-template"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deduplicated", is(true)))
                .andExpect(jsonPath("$.data.sourceVersions", hasSize(1)));

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/parse", projectId, templateId, sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.data.outputReference", containsString("template-structural-snapshots")));

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/render", projectId, templateId, sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("NOT_IMPLEMENTED")))
                .andExpect(jsonPath("$.data.failureReason", containsString("未配置")));

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/analyze", projectId, templateId, sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("NOT_READY")))
                .andExpect(jsonPath("$.data.failureReason", containsString("ANALYZER_NOT_READY")));

        mockMvc.perform(get("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}", projectId, templateId, sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detailsLoaded", is(true)))
                .andExpect(jsonPath("$.data.structuralSnapshot.slideCount", is(2)))
                .andExpect(jsonPath("$.data.renderedSlideSet.status", is("NOT_IMPLEMENTED")))
                .andExpect(jsonPath("$.data.processingRuns", hasSize(3)));
    }

    @Test
    void manualDraftLifecycleKeepsNotReadyBoundaryAndProjectsSemanticCapabilityView() throws Exception {
        byte[] bytes = pptxBytes(1);
        String upload = upload(bytes, "biology.pptx").andReturn().getResponse().getContentAsString();
        JsonNode uploaded = objectMapper.readTree(upload).path("data");
        long templateId = uploaded.path("template").path("id").asLong();
        long sourceId = uploaded.path("sourceVersions").get(0).path("id").asLong();
        String profile = profileJson("标题布局");

        String created = mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceVersionId\":" + sourceId + ",\"profile\":" + profile + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CANDIDATE")))
                .andReturn().getResponse().getContentAsString();
        JsonNode candidate = objectMapper.readTree(created).path("data");
        long profileId = candidate.path("id").asLong();
        String checksum = candidate.path("checksum").asText();

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}/confirm", projectId, templateId, profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checksum\":\"wrong\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}/review", projectId, templateId, profileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("REVIEW")));

        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}/confirm", projectId, templateId, profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checksum\":\"" + checksum + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("NOT_READY")));

        mockMvc.perform(get("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}/capability-view", projectId, templateId, profileId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("shapeRefs"))))
                .andExpect(content().string(not(containsString("OXML"))))
                .andExpect(jsonPath("$.data.displayName", is("标题布局")));

        mockMvc.perform(get("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].status", is("REVIEW")));

        String revision = mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}/revisions", projectId, templateId, profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceVersionId\":" + sourceId + ",\"profile\":" + profileJson("第二版布局") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CANDIDATE")))
                .andExpect(jsonPath("$.data.parentProfileVersionId", is((int) profileId)))
                .andReturn().getResponse().getContentAsString();
        long revisionId = objectMapper.readTree(revision).path("data").path("id").asLong();
        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/profiles/{profileId}/rollback", projectId, templateId, revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetProfileVersionId\":" + profileId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CANDIDATE")))
                .andExpect(jsonPath("$.data.parentProfileVersionId", is((int) revisionId)));

        mockMvc.perform(get("/api/projects/{projectId}/templates/{templateId}/profiles", projectId, templateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)));
        org.junit.jupiter.api.Assertions.assertEquals(
                uploaded.path("sourceVersions").get(0).path("sha256").asText(),
                sourceRepository.findById(sourceId).orElseThrow().getSha256());
    }

    @Test
    void sourceDetailAndAnalyzerFailClosedWhenStructuralSnapshotIsTampered() throws Exception {
        JsonNode uploaded = objectMapper.readTree(upload(pptxBytes(1), "snapshot-tamper.pptx").andReturn().getResponse().getContentAsString()).path("data");
        long templateId = uploaded.path("template").path("id").asLong();
        long sourceId = uploaded.path("sourceVersions").get(0).path("id").asLong();
        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/parse", projectId, templateId, sourceId))
                .andExpect(status().isOk());
        var snapshot = snapshotRepository.findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(sourceId).orElseThrow();
        snapshot.setSnapshotJson("{\"slideCount\":999,\"tampered\":true}");
        snapshotRepository.saveAndFlush(snapshot);

        mockMvc.perform(get("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}", projectId, templateId, sourceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("SNAPSHOT_INTEGRITY_MISMATCH")));

        var source = sourceRepository.findById(sourceId).orElseThrow();
        source.setRenderStatus(com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus.SUCCEEDED);
        sourceRepository.saveAndFlush(source);
        mockMvc.perform(post("/api/projects/{projectId}/templates/{templateId}/source-versions/{sourceId}/analyze", projectId, templateId, sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("NOT_READY")))
                .andExpect(jsonPath("$.data.failureReason", containsString("ANALYZER_NOT_READY")));
    }

    @Test
    void invalidPptxDoesNotStoreAFileOrExposeUnderlyingParserDetails() throws Exception {
        long before = sourceRepository.count();
        mockMvc.perform(multipart("/api/projects/{projectId}/templates", projectId)
                        .file(new MockMultipartFile("file", "bad.pptx", PPTX_MIME, "not-a-pptx".getBytes()))
                        .param("name", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("The uploaded file is not a valid PPTX package")));
        org.junit.jupiter.api.Assertions.assertEquals(before, sourceRepository.count());
    }

    private org.springframework.test.web.servlet.ResultActions upload(byte[] bytes, String filename) throws Exception {
        return mockMvc.perform(multipart("/api/projects/{projectId}/templates", projectId)
                .file(new MockMultipartFile("file", filename, PPTX_MIME, bytes))
                .param("name", "school-template"))
                .andExpect(status().isOk());
    }

    private byte[] pptxBytes(int slides) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < slides; i++) {
                var slide = show.createSlide();
                var textBox = slide.createTextBox();
                // Regression fixture: touching the slide's top/left edge is
                // legal and must remain representable as x=0/y=0 EMU.
                textBox.setAnchor(new java.awt.geom.Rectangle2D.Double(0, 0, 200, 80));
                var connector = slide.createConnector();
                connector.setAnchor(new java.awt.geom.Rectangle2D.Double(20, 20, 200, 0));
                var decoration = slide.createAutoShape();
                decoration.setShapeType(ShapeType.RECT);
                decoration.setAnchor(new java.awt.geom.Rectangle2D.Double(0, 0, 200, 0));
                CTShape decorationXml = (CTShape) decoration.getXmlObject();
                decorationXml.getSpPr().addNewNoFill();
                decorationXml.getSpPr().addNewLn().addNewNoFill();
                var contentPlaceholder = slide.createAutoShape();
                contentPlaceholder.setAnchor(new java.awt.geom.Rectangle2D.Double(-20, 80, 200, 100));
                contentPlaceholder.setText("Verified content placeholder");
                CTShape contentPlaceholderXml = (CTShape) contentPlaceholder.getXmlObject();
                contentPlaceholderXml.getNvSpPr().getNvPr().addNewPh().setIdx(1);
                textBox.setText("Fixture slide " + (i + 1));
            }
            show.write(output);
            return output.toByteArray();
        }
    }

    private String profileJson(String displayName) {
        return "{\"displayName\":\"" + displayName + "\",\"pageRoles\":[\"COVER\",\"CONTENT\"],"
                + "\"semanticLayouts\":[{\"name\":\"左文右图\",\"description\":\"语义布局\",\"minCapacity\":1,\"maxCapacity\":4}],"
                + "\"imageCapability\":{\"supported\":true,\"minCount\":0,\"maxCount\":1,\"notes\":\"需审核\"},"
                + "\"tableCapability\":{\"supported\":false,\"minCount\":0,\"maxCount\":0,\"notes\":\"\"},"
                + "\"chartCapability\":{\"supported\":false,\"minCount\":0,\"maxCount\":0,\"notes\":\"\"},"
                + "\"fixedBrandAreas\":[\"页眉\"],\"limitations\":[\"Analyzer 未运行\"]}";
    }
}
