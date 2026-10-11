package com.workshopper.controller;

import com.workshopper.facade.WorkshopSessionFacade;
import com.workshopper.service.PdfExportService;
import com.workshopper.service.WorkshopService;
import com.workshopper.usecase.AssemblePptxUseCase;
import com.workshopper.usecase.GenerateLearningGoalsUseCase;
import com.workshopper.usecase.GenerateSlideBlockUseCase;
import com.workshopper.usecase.RefineLearningGoalUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the SecurityConfig correctly gates write endpoints behind
 * authentication. Specifically: anonymous POST/PUT/DELETE must be rejected
 * (4xx) — NOT 200 — which would mean the "system" fallback in AuthContext
 * is reachable and ownership enforcement is silently bypassed.
 *
 * Security auto-configuration is deliberately LEFT ENABLED (no blanket
 * excludeAutoConfiguration) so these tests exercise the real filter chain.
 *
 * Spring returns 403 (not 401) for CSRF-disabled, sessionless SAML contexts
 * when no credentials are present — both are "not authenticated/not authorised"
 * and either confirms the endpoint is not publicly reachable.
 */
@org.springframework.context.annotation.Import(com.workshopper.config.SecurityConfig.class)@WebMvcTest(
    value = WorkshopController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.saml2.Saml2RelyingPartyAutoConfiguration.class
    }
)
@org.springframework.test.context.ActiveProfiles("test")
@DisplayName("SecurityConfig — write endpoints require authentication")
class WorkshopControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private WorkshopService workshopService;
    @MockitoBean private PdfExportService pdfExportService;
    @MockitoBean private WorkshopSessionFacade facade;
    @MockitoBean private GenerateSlideBlockUseCase generateSlideBlockUseCase;
    @MockitoBean private AssemblePptxUseCase assemblePptxUseCase;
    @MockitoBean private GenerateLearningGoalsUseCase generateLearningGoalsUseCase;
    @MockitoBean private RefineLearningGoalUseCase refineLearningGoalUseCase;
    @MockitoBean private org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Test
    @DisplayName("Anonymous POST /api/workshop/session → 4xx (not 200: system-fallback bypass is closed)")
    void anonymousPostToSessionIsRejected() throws Exception {
        mvc.perform(post("/api/workshop/session")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
           .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
           .andExpect(status().is4xxClientError()); // 401 or 403 — either confirms the endpoint is gated
    }

    @Test
    @DisplayName("Anonymous PUT /api/workshop/sessions/{id}/rename → 4xx")
    void anonymousPutIsRejected() throws Exception {
        mvc.perform(put("/api/workshop/sessions/some-id/rename")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"title\":\"hacked\"}"))
           .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Anonymous DELETE /api/workshop/sessions/{id} → 4xx")
    void anonymousDeleteIsRejected() throws Exception {
        mvc.perform(delete("/api/workshop/sessions/some-id"))
           .andExpect(status().is4xxClientError());
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    @DisplayName("Anonymous POST /api/workshop/session → 401 (HttpStatusEntryPoint returns UNAUTHORIZED, not redirect)")
    void anonymousPostReturnsUnauthorized() throws Exception {
        mvc.perform(post("/api/workshop/session")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
           .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print()).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/workshop/sessions/{id} by non-owner → 403 Forbidden")
    @WithMockUser(username = "other-user")
    void getSessionByNonOwnerReturnsForbidden() throws Exception {
        // Simulate the facade throwing AccessDeniedException (wrong owner)
        doThrow(new AccessDeniedException("User other-user is not allowed to access this session"))
                .when(facade).verifyOwnership(anyString());

        mvc.perform(get("/api/workshop/sessions/some-session-id"))
           .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    @DisplayName("Anonymous GET /api/workshop/me → 401 (not 200 with anonymous identity)")
    void anonymousGetMeReturnsUnauthorized() throws Exception {
        mvc.perform(get("/api/workshop/me"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "test-saml-user")
    @DisplayName("Authenticated GET /api/workshop/me → 200 with correct user id")
    void authenticatedGetMeReturnsUserId() throws Exception {
        mvc.perform(get("/api/workshop/me"))
           .andExpect(status().isOk())
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
               .jsonPath("$.id").value("test-saml-user"));
    }
}

