package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.agent.model.ModelCredentialResolver;
import com.auvdidao.a12teachingagent.agent.model.ModelGateway;
import com.auvdidao.a12teachingagent.agent.model.ModelProvider;
import com.auvdidao.a12teachingagent.agent.model.ModelRequest;
import com.auvdidao.a12teachingagent.agent.model.MultimodalModelResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class TemplateAnalyzerPreviewSourceTest {
    private static final String SOURCE_SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void rejectsAbsolutePreviewEvenWhenItHasAHost() {
        UnavailableTemplateAnalyzerAdapter adapter = adapter(true, "https://preview.example.test/base/");

        TemplateAnalyzer.AnalysisResult result = adapter.analyze(request("https://attacker.example.test/template-renders/1.pdf"));

        assertFalse(result.implemented());
        assertTrue(result.adapter().contains("preview-unavailable"));
    }

    @Test
    void rejectsFileAndTraversalReferencesWithoutCallingGateway() {
        ModelGateway gateway = mock(ModelGateway.class);
        ModelCredentialResolver credentials = mock(ModelCredentialResolver.class);
        UnavailableTemplateAnalyzerAdapter adapter = new UnavailableTemplateAnalyzerAdapter(
                new ObjectMapper(), gateway, credentials, true, "https://preview.example.test/base/",
                "KIMI", "kimi-reviewed-model", 100, 1_000
        );

        assertFalse(adapter.analyze(request("file:///tmp/template-renders/1.pdf")).implemented());
        assertFalse(adapter.analyze(request("template-renders/../secret.pdf")).implemented());
        verifyNoInteractions(gateway, credentials);
    }

    @Test
    void disabledAdapterNeverCreatesCandidate() {
        UnavailableTemplateAnalyzerAdapter adapter = adapter(false, "https://preview.example.test/base/");

        TemplateAnalyzer.AnalysisResult result = adapter.analyze(request("template-renders/1.pdf"));

        assertFalse(result.implemented());
        assertTrue(result.candidateProfile() == null);
    }

    @Test
    void developmentFixtureCreatesOnlyBoundedSemanticCandidateInDev() throws Exception {
        UnavailableTemplateAnalyzerAdapter adapter = new UnavailableTemplateAnalyzerAdapter(
                new ObjectMapper(), mock(ModelGateway.class), mock(ModelCredentialResolver.class),
                false, "", "KIMI", "", 100, 1_000, true, "dev"
        );

        TemplateAnalyzer.AnalysisRequest request = new TemplateAnalyzer.AnalysisRequest(
                new ObjectMapper().readTree("{\"slideCount\":2,\"slides\":[{\"pageNumber\":1},{\"pageNumber\":2}]}"),
                "template-renders/fixture.pdf", 1, 2, 3, SOURCE_SHA, "template-analysis-fixture"
        );

        TemplateAnalyzer.AnalysisResult result = adapter.analyze(request);

        assertTrue(result.implemented());
        assertEquals("DEVELOPMENT_FIXTURE", result.provider());
        assertEquals("semantic-profile-fixture-v1", result.model());
        assertEquals(request.analysisRunId(), result.analysisRunId());
        assertNotNull(result.candidateProfile());
        assertEquals(2, result.candidateProfile().path("pageRoles").size());
        assertTrue(result.candidateProfile().path("limitations").toString().contains("AUTO_RENDERER=NOT_READY"));
        assertFalse(result.candidateProfile().toString().matches("(?i).*(shape|group|slot|coordinate|ooxml|xml).*"));
    }

    @Test
    void developmentFixtureSummarizesLargeSnapshotWithoutExceedingPageRoleContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var snapshot = mapper.createObjectNode().put("slideCount", 104);
        var slides = snapshot.putArray("slides");
        for (int page = 1; page <= 104; page++) {
            slides.addObject().put("pageNumber", page);
        }
        UnavailableTemplateAnalyzerAdapter adapter = new UnavailableTemplateAnalyzerAdapter(
                mapper, mock(ModelGateway.class), mock(ModelCredentialResolver.class),
                false, "", "KIMI", "", 100, 1_000, true, "dev"
        );

        TemplateAnalyzer.AnalysisResult result = adapter.analyze(new TemplateAnalyzer.AnalysisRequest(
                snapshot, "template-renders/fixture.pdf", 1, 2, 3, SOURCE_SHA, "template-analysis-large"
        ));

        assertTrue(result.implemented());
        assertEquals(2, result.candidateProfile().path("pageRoles").size());
        assertTrue(result.candidateProfile().path("pageRoles").size() <= 20);
        assertEquals("COVER", result.candidateProfile().path("pageRoles").get(0).asText());
        assertEquals("CONTENT", result.candidateProfile().path("pageRoles").get(1).asText());
    }

    @Test
    void realAnalyzerProjectsLargeNativeSnapshotBeforeGatewayCall() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var snapshot = mapper.createObjectNode().put("slideCount", 24).put("pageWidth", 960).put("pageHeight", 540);
        var slides = snapshot.putArray("slides");
        for (int page = 1; page <= 24; page++) {
            var slide = slides.addObject().put("pageNumber", page).put("shapeCount", 120);
            var shapes = slide.putArray("shapes");
            for (int shape = 1; shape <= 120; shape++) {
                shapes.addObject()
                        .put("reference", "slide-" + page + "/shape-" + shape)
                        .put("type", shape % 2 == 0 ? "XSLFTextBox" : "XSLFPictureShape")
                        .put("x", shape * 1.0).put("y", shape * 2.0)
                        .put("width", 400).put("height", 200)
                        .put("text", shape % 2 == 0).put("textContent", shape % 2 == 0)
                        .put("picture", shape % 2 != 0).put("table", false).put("chart", false);
            }
        }
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.completeMultimodal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    ModelRequest request = invocation.getArgument(1);
                    assertTrue(request.messages().get(0).content().length() < 50_000);
                    assertTrue(request.messages().get(0).content().contains("shapeTypeCounts"));
                    assertFalse(request.messages().get(0).content().contains("slide-1/shape-1"));
                    return new MultimodalModelResult(ModelProvider.DEEPSEEK, "reviewed-model", "request-1",
                            "{\"displayName\":\"Reviewed template\",\"pageRoles\":[\"CONTENT\"],\"semanticLayouts\":[{\"name\":\"CONTENT\",\"description\":\"bounded\",\"minCapacity\":1,\"maxCapacity\":20}],\"imageCapability\":null,\"tableCapability\":null,\"chartCapability\":null,\"fixedBrandAreas\":[],\"limitations\":[]}",
                            "stop", null, 1);
                });
        UnavailableTemplateAnalyzerAdapter adapter = new UnavailableTemplateAnalyzerAdapter(
                mapper, gateway, mock(ModelCredentialResolver.class), true,
                "https://preview.example.test/base/", "KIMI", "reviewed-model", 100, 1_000
        );

        TemplateAnalyzer.AnalysisResult result = adapter.analyze(new TemplateAnalyzer.AnalysisRequest(
                snapshot, "template-renders/fixture.pdf", 1, 2, 3, SOURCE_SHA, "template-analysis-projection"
        ));

        assertTrue(result.implemented(), result.adapter() + ":" + result.message());
        assertEquals("DEEPSEEK", result.provider());
    }

    @Test
    void developmentFixtureStillFailsClosedOutsideDevProfile() {
        UnavailableTemplateAnalyzerAdapter adapter = new UnavailableTemplateAnalyzerAdapter(
                new ObjectMapper(), mock(ModelGateway.class), mock(ModelCredentialResolver.class),
                false, "", "KIMI", "", 100, 1_000, true, "test"
        );

        TemplateAnalyzer.AnalysisResult result = adapter.analyze(request("template-renders/fixture.pdf"));

        assertFalse(result.implemented());
        assertTrue(result.candidateProfile() == null);
    }

    private UnavailableTemplateAnalyzerAdapter adapter(boolean enabled, String base) {
        return new UnavailableTemplateAnalyzerAdapter(
                new ObjectMapper(), mock(ModelGateway.class), mock(ModelCredentialResolver.class),
                enabled, base, "KIMI", "kimi-reviewed-model", 100, 1_000
        );
    }

    private TemplateAnalyzer.AnalysisRequest request(String previewReference) {
        return new TemplateAnalyzer.AnalysisRequest(
                new ObjectMapper().createObjectNode().put("slides", 1), previewReference,
                1, 2, 3, SOURCE_SHA, "template-analysis-1"
        );
    }
}
