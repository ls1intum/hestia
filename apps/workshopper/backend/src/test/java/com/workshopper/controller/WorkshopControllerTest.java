package com.workshopper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workshopper.dto.*;
import com.workshopper.service.PdfExportService;
import com.workshopper.service.PptxExportService;
import com.workshopper.service.WorkshopService;
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
@WebMvcTest(WorkshopController.class)
class WorkshopControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkshopService workshopService;

    @MockitoBean
    private PdfExportService pdfExportService;

    @MockitoBean
    private PptxExportService pptxExportService;

    // ── Health ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/workshop/health returns 200 OK")
    void healthReturns200() throws Exception {
        mvc.perform(get("/api/workshop/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    // ── /plan ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/workshop/plan")
    class PlanEndpointTests {

        @Test
        @DisplayName("returns 200 with list of plans on success")
        void returnsPlansOnSuccess() throws Exception {
            var plan = new LearningGoalPlanDto("g1", "original", "Participants will apply X",
                    List.of(), List.of(), List.of(), 0);
            when(workshopService.generatePlan(any())).thenReturn(List.of(plan));

            WorkshopInputDto body = new WorkshopInputDto(
                    List.of("Understand X"), 60, 20, "workshop",
                    null, null, null, null, null, null, null);

            mvc.perform(post("/api/workshop/plan")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value("g1"))
                    .andExpect(jsonPath("$[0].goal").value("Participants will apply X"));
        }

        @Test
        @DisplayName("returns 500 when service throws")
        void returns500OnServiceException() throws Exception {
            when(workshopService.generatePlan(any()))
                    .thenThrow(new RuntimeException("LLM unreachable"));

            WorkshopInputDto body = new WorkshopInputDto(
                    List.of("A goal"), 60, 20, "workshop",
                    null, null, null, null, null, null, null);

            mvc.perform(post("/api/workshop/plan")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isInternalServerError());
        }
    }

    // ── /sessions ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/workshop/sessions")
    class ListSessionsTests {

        @Test
        @DisplayName("returns 200 with empty list when no sessions exist")
        void returnsEmptyList() throws Exception {
            when(workshopService.listSessions()).thenReturn(List.of());

            mvc.perform(get("/api/workshop/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"));
        }

        @Test
        @DisplayName("returns session summaries")
        void returnsSessionSummaries() throws Exception {
            var summary = new SessionSummaryDto(
                    "abc123", "My Session", "Participants will learn X",
                    "complete", "result", "SESSION", null, null, null);
            when(workshopService.listSessions()).thenReturn(List.of(summary));

            mvc.perform(get("/api/workshop/sessions"))
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

            mvc.perform(get("/api/workshop/sessions/nonexistent"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 200 with session detail when found")
        void returns200WithDetail() throws Exception {
            var detail = new SessionDetailDto(
                    "abc123", "My Session", "complete", "result",
                    "SESSION", null, null, null);
            when(workshopService.getSession("abc123"))
                    .thenReturn(java.util.Optional.of(detail));

            mvc.perform(get("/api/workshop/sessions/abc123"))
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

        mvc.perform(delete("/api/workshop/sessions/abc123"))
                .andExpect(status().isNoContent());

        verify(workshopService).deleteSession("abc123");
    }

    // ── /refine-goal ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/workshop/refine-goal returns 500 when service throws")
    void refineGoalReturns500OnError() throws Exception {
        when(workshopService.refineGoal(any()))
                .thenThrow(new RuntimeException("LLM error"));

        RefineGoalRequestDto body = new RefineGoalRequestDto("A vague goal", null);

        mvc.perform(post("/api/workshop/refine-goal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isInternalServerError());
    }

    // ── /sessions/{id}/rename ─────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /api/workshop/sessions/{id}/rename returns 400 when title is blank")
    void renameSessionBlankTitleReturns400() throws Exception {
        mvc.perform(put("/api/workshop/sessions/abc123/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/workshop/sessions/{id}/rename returns 200 on success")
    void renameSessionReturns200() throws Exception {
        doNothing().when(workshopService).renameSession("abc123", "New Title");

        mvc.perform(put("/api/workshop/sessions/abc123/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New Title\"}"))
                .andExpect(status().isOk());

        verify(workshopService).renameSession("abc123", "New Title");
    }
}
