package de.tum.cit.hestia.teaching.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.teaching.dto.*;
import de.tum.cit.hestia.teaching.service.PdfExportService;
import de.tum.cit.hestia.teaching.usecase.AssemblePptxUseCase;
import de.tum.cit.hestia.teaching.service.WorkshopService;

import de.tum.cit.hestia.teaching.facade.WorkshopSessionFacade;
import de.tum.cit.hestia.teaching.usecase.GenerateSlideBlockUseCase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice tests for {@link WorkshopController} using {@code @WebMvcTest}.
 * Only the web layer is loaded — no DB, no real services.
 * Verifies HTTP status codes, routing, and basic error handling.
 */
@org.springframework.test.context.ContextConfiguration(classes = TeachingWebMvcTestConfig.class)
@org.springframework.context.annotation.Import(WorkshopController.class)
@WebMvcTest(
    value = WorkshopController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.saml2.Saml2RelyingPartyAutoConfiguration.class
    }
)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class WorkshopControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkshopService workshopService;

    @MockitoBean
    private PdfExportService pdfExportService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private de.tum.cit.hestia.teaching.facade.WorkshopSessionFacade facade;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private de.tum.cit.hestia.teaching.usecase.GenerateSlideBlockUseCase generateSlideBlockUseCase;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private de.tum.cit.hestia.teaching.usecase.AssemblePptxUseCase assemblePptxUseCase;


    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private de.tum.cit.hestia.teaching.usecase.RefineLearningGoalUseCase refineLearningGoalUseCase;



    // ── Health ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/workshop/health returns 200 OK")
    void healthReturns200() throws Exception {
        mvc.perform(get("/workshopper/api/workshop/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    @Nested
    @DisplayName("GET /api/workshop/sessions")
    class ListSessionsTests {

        @Test
        @DisplayName("returns 200 with empty list when no sessions exist")
        void returnsEmptyList() throws Exception {
            
            when(workshopService.listSessions(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

            mvc.perform(get("/workshopper/api/workshop/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"));
        }

        @Test
        @DisplayName("returns session summaries")
        void returnsSessionSummaries() throws Exception {
            var summary = new SessionSummaryDto("abc123", "My Session", "draft", "goals", "courseId", null, null);
            
            when(workshopService.listSessions(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(summary));

            mvc.perform(get("/workshopper/api/workshop/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value("abc123"))
                    .andExpect(jsonPath("$[0].title").value("My Session"));
        }
    }

    // ── /sessions/{id} ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/workshop/sessions/{id}")
    class GetSessionTests {

        @Test
        @DisplayName("returns 404 when session not found")
        void returns404WhenNotFound() throws Exception {
            when(workshopService.getSession("nonexistent"))
                    .thenReturn(java.util.Optional.empty());

            mvc.perform(get("/workshopper/api/workshop/sessions/nonexistent"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 200 with session detail when found")
        void returns200WithDetail() throws Exception {
            var detail = new SessionDetailDto("abc123", "My Session", "draft", "goals", "courseId", "{}", false, null, null, null);
            when(workshopService.getSession("abc123"))
                    .thenReturn(java.util.Optional.of(detail));

            mvc.perform(get("/workshopper/api/workshop/sessions/abc123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("abc123"))
                    .andExpect(jsonPath("$.title").value("My Session"));
        }
    }

    // ── DELETE /sessions/{id} ─────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/workshop/sessions/{id} returns 204 on success")
    void deleteSessionReturns204() throws Exception {
        doNothing().when(workshopService).deleteSession("abc123");

        mvc.perform(delete("/workshopper/api/workshop/sessions/abc123"))
                .andExpect(status().isNoContent());

        verify(workshopService).deleteSession("abc123");
    }

    // ── /refine-goal ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/workshop/refine-goal returns 500 when service throws")
    void refineGoalReturns500OnError() throws Exception {
        when(facade.refineGoal(any()))
                .thenThrow(new RuntimeException("LLM error"));

        RefineGoalRequestDto body = new RefineGoalRequestDto("A vague goal", null);

        mvc.perform(post("/workshopper/api/workshop/refine-goal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isInternalServerError());
    }

    // ── /sessions/{id}/rename ─────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /api/workshop/sessions/{id}/rename returns 400 when title is blank")
    void renameSessionBlankTitleReturns400() throws Exception {
        mvc.perform(put("/workshopper/api/workshop/sessions/abc123/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/workshop/sessions/{id}/rename returns 200 on success")
    void renameSessionReturns200() throws Exception {
        doNothing().when(workshopService).renameSession("abc123", "New Title");

        mvc.perform(put("/workshopper/api/workshop/sessions/abc123/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New Title\"}"))
                .andExpect(status().isOk());

        verify(workshopService).renameSession("abc123", "New Title");
    }

    @Nested
    @DisplayName("POST /api/workshop/session")
    class GenerateSession {

        @Test
        @DisplayName("returns 403 when AccessDeniedException is thrown")
        void returns403WhenAccessDenied() throws Exception {
            when(facade.generateAndSaveSession(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("not your session"));

            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/workshopper/api/workshop/session")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{}"))
               .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }

        @Test
        @DisplayName("returns 409 when a generation for the session is already running")
        void returns409WhenLocked() throws Exception {
            when(facade.generateAndSaveSession(any()))
                .thenThrow(new de.tum.cit.hestia.teaching.exception.SessionLockedException("already running"));

            mvc.perform(post("/workshopper/api/workshop/session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.error").value("already running"));
        }
    }

    @Test
    @DisplayName("GET /api/workshop/export/course/{id}/zip returns 404 for an unknown course")
    void courseZipUnknownCourseReturns404() throws Exception {
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Course not found"))
            .when(facade).verifyCourseOwnership("nope");

        mvc.perform(get("/workshopper/api/workshop/export/course/nope/zip"))
            .andExpect(status().isNotFound());
    }

}
