package com.auvdidao.a12teachingagent.planning;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.common.exception.GlobalExceptionHandler;
import com.auvdidao.a12teachingagent.common.exception.UnauthorizedException;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningResponse;
import com.auvdidao.a12teachingagent.security.A12SecurityProperties;
import com.auvdidao.a12teachingagent.security.ApiSecurityErrorHandler;
import com.auvdidao.a12teachingagent.security.TokenAuthenticationService;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.TraceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlanningAgentController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PlanningAgentControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlanningAgentService service;

    @MockBean
    private TokenAuthenticationService tokenAuthenticationService;

    @MockBean
    private A12SecurityProperties securityProperties;

    @MockBean
    private ApiSecurityErrorHandler apiSecurityErrorHandler;

    @Test
    void unauthenticatedServiceBoundaryIsReturnedAsStructured401() throws Exception {
        when(service.createProposal(eq(9L), any())).thenThrow(new UnauthorizedException("Authentication is required"));

        mockMvc.perform(post("/api/v1/projects/9/planning/proposals")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(401)))
                .andExpect(jsonPath("$.data", nullValue()));
    }

    @Test
    void nonTeacherOwnerAndCrossProjectFailuresAreStructured403() throws Exception {
        when(service.createProposal(eq(9L), any())).thenThrow(new ForbiddenException("The active role is not allowed"));

        mockMvc.perform(post("/api/v1/projects/9/planning/proposals")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(403)));

        when(service.traces(eq(77L), anyInt())).thenThrow(new ForbiddenException("Project belongs to another teacher"));
        mockMvc.perform(get("/api/v1/projects/77/planning/traces"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(403)))
                .andExpect(jsonPath("$.message", containsString("another teacher")));
    }

    @Test
    void malformedNestedRequestIs400AndNeverReachesPlanningService() throws Exception {
        mockMvc.perform(post("/api/v1/projects/9/planning/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedNestedRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.data", nullValue()));
        verifyNoInteractions(service);
    }

    @Test
    void sourceAssetAndProviderMismatchFailuresAreStable4xxAtHttpBoundary() throws Exception {
        when(service.createProposal(eq(9L), any())).thenThrow(new BadRequestException("CONTENT_SOURCE_MISMATCH"));
        mockMvc.perform(post("/api/v1/projects/9/planning/proposals")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.message", is("CONTENT_SOURCE_MISMATCH")));

        when(service.createProposal(eq(9L), any())).thenThrow(new BadRequestException("ASSET_SOURCE_MISMATCH"));
        mockMvc.perform(post("/api/v1/projects/9/planning/proposals")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("ASSET_SOURCE_MISMATCH")));

        when(service.createProposal(eq(9L), any())).thenThrow(new BadRequestException("MODEL_OUTPUT_PROVIDER_MISMATCH"));
        mockMvc.perform(post("/api/v1/projects/9/planning/proposals")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("MODEL_OUTPUT_PROVIDER_MISMATCH")));
    }

    @Test
    void httpResponseSerializesActualProviderAndModelAuditFields() throws Exception {
        when(service.createProposal(eq(9L), any())).thenReturn(new PlanningResponse(
                "run-1", "trace-1", "COMPLETED", "KIMI", "KIMI", "kimi-k2", null,
                null, null, null, null));

        mockMvc.perform(post("/api/v1/projects/9/planning/proposals")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedProvider", is("KIMI")))
                .andExpect(jsonPath("$.data.usedProvider", is("KIMI")))
                .andExpect(jsonPath("$.data.usedModel", is("kimi-k2")))
                .andExpect(jsonPath("$.data.executionStatus", is("COMPLETED")));
        verify(service).createProposal(eq(9L), any());
    }

    @Test
    void tracesEndpointUsesTheRealHttpRoute() throws Exception {
        when(service.traces(eq(9L), eq(20))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/9/planning/traces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void confirmedContextEndpointReturnsOnlyServerOwnedReference() throws Exception {
        when(service.confirmedContextReference(eq(9L)))
                .thenReturn(new ConfirmedTeachingContextService.ConfirmedContextReference(
                        "intent:12:" + "a".repeat(64), "a".repeat(64)));

        mockMvc.perform(get("/api/v1/projects/9/planning/confirmed-context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.revision", is("intent:12:" + "a".repeat(64))))
                .andExpect(jsonPath("$.data.checksum", is("a".repeat(64))))
                .andExpect(jsonPath("$.data.canonicalJson").doesNotExist());
        verify(service).confirmedContextReference(eq(9L));
    }

    private String validRequest() {
        return """
                {
                  "templateId": 7,
                  "templateProfileVersionId": 9,
                  "operation": "INITIAL_PROPOSAL",
                  "confirmedContextVersion": "intent:1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "targetSlideCount": 2,
                  "slideCountTolerance": 0,
                  "locale": "zh-CN",
                  "teachingContext": {
                    "courseName": "client",
                    "topic": "client",
                    "teachingObjectives": ["client"],
                    "outline": ["client"],
                    "lessonPlan": [],
                    "teacherConfirmed": false
                  },
                  "evidence": [],
                  "explicitTeacherTrigger": true
                }
                """;
    }

    private String malformedNestedRequest() {
        return """
                {
                  "templateId": 7,
                  "templateProfileVersionId": 9,
                  "operation": "INITIAL_PROPOSAL",
                  "confirmedContextVersion": "intent:1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "targetSlideCount": 2,
                  "slideCountTolerance": 0,
                  "locale": "zh-CN",
                  "teachingContext": {},
                  "evidence": [],
                  "explicitTeacherTrigger": true
                }
                """;
    }
}
