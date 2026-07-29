package com.auvdidao.a12teachingagent.pptskill;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KimiPptOutlineProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PptGeneratorProperties properties = new PptGeneratorProperties();
    private HttpClient httpClient;
    private TeachingIntentRepository intentRepository;
    private GenerationPlanRepository planRepository;
    private KimiPptOutlineProvider provider;

    @BeforeEach
    void setUp() {
        properties.setKimiApiKey("test-key");
        properties.setKimiBaseUrl("https://kimi.test/v1");
        properties.setKimiModel("kimi-k2.6");
        httpClient = mock(HttpClient.class);
        intentRepository = mock(TeachingIntentRepository.class);
        planRepository = mock(GenerationPlanRepository.class);
        when(intentRepository.findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(any(), any())).thenReturn(Optional.empty());
        when(planRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(any())).thenReturn(Optional.empty());
        provider = new KimiPptOutlineProvider(objectMapper, properties, new PptOutlineSchemaValidator(objectMapper),
                intentRepository, planRepository, httpClient);
    }

    @Test
    void submitsSchemaConstrainedRequestAndAcceptsValidOutline() throws Exception {
        whenResponse(fixture());

        JsonNode outline = provider.getOutline(project());

        assertEquals("KIMI", provider.providerId());
        assertTrue(outline.path("slides").isArray());
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        JsonNode body = objectMapper.readTree(requestBody(request.getValue()));
        assertTrue(body.path("thinking").isMissingNode());
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertTrue(body.path("response_format").path("json_schema").isMissingNode());
        assertTrue(body.path("temperature").isMissingNode());
        assertEquals(properties.getKimiMaxCompletionTokens(), body.path("max_tokens").asInt());
    }

    @Test
    void retriesExactlyOnceAfterInvalidJson() throws Exception {
        whenResponses("not-json", fixture());

        provider.getOutline(project());

        verify(httpClient, org.mockito.Mockito.times(2)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void rejectsMissingKeyBeforeCallingKimi() throws Exception {
        properties.setKimiApiKey(" ");

        assertEquals("KIMI_CONFIG_MISSING", assertThrows(PptSkillGenerationException.class,
                () -> provider.getOutline(project())).getCode());
        verify(httpClient, never()).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void mapsNonSuccessResponseToSafeError() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(502);
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);

        assertEquals("KIMI_REQUEST_FAILED", assertThrows(PptSkillGenerationException.class,
                () -> provider.getOutline(project())).getCode());
    }

    private void whenResponse(String content) throws Exception {
        whenResponses(content);
    }

    @SuppressWarnings("unchecked")
    private void whenResponses(String... contents) throws Exception {
        org.mockito.stubbing.OngoingStubbing<HttpResponse<String>> stubbing = null;
        for (String content : contents) {
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            var responseJson = objectMapper.createObjectNode();
            responseJson.putArray("choices").addObject().putObject("message").put("content", content);
            when(response.body()).thenReturn(objectMapper.writeValueAsString(responseJson));
            if (stubbing == null) {
                stubbing = when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
            } else {
                stubbing = stubbing.thenReturn(response);
            }
        }
    }

    private Project project() {
        Project project = new Project();
        project.setId(7L);
        project.setProjectName("Photosynthesis");
        project.setCourseName("Biology");
        project.setChapterTopic("Photosynthesis");
        project.setTargetAudience("Grade 8");
        return project;
    }

    private String fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/pptskill/grade-8-biology-photosynthesis-outline.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String requestBody(HttpRequest request) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> subscriber = new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(java.nio.ByteBuffer item) { byte[] bytes = new byte[item.remaining()]; item.get(bytes); output.writeBytes(bytes); }
            @Override public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
            @Override public void onComplete() { result.complete(output.toString(StandardCharsets.UTF_8)); }
        };
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        return result.get();
    }
}
