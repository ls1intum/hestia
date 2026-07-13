package com.workshopper.controller;

import com.workshopper.dto.*;
import com.workshopper.service.WorkshopService;
import com.workshopper.service.PdfExportService;
import com.workshopper.service.PptxExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.workshopper.dto.BlockSlidesRequestDto;
import com.workshopper.dto.PptxAssembleRequestDto;

@RestController
@RequestMapping("/api/workshop")
public class WorkshopController {

    private static final Logger log = LoggerFactory.getLogger(WorkshopController.class);

    private final WorkshopService service;
    private final PdfExportService pdfService;
    private final PptxExportService pptxService;

    public WorkshopController(WorkshopService service, PdfExportService pdfService, PptxExportService pptxService) {
        this.service = service;
        this.pdfService = pdfService;
        this.pptxService = pptxService;
    }

    /**
     * POST /api/workshop/plan
     * Generate learning goal plans from workshop input.
     */
    @PostMapping("/plan")
    public ResponseEntity<?> generatePlan(@RequestBody WorkshopInputDto input) {
        try {
            List<LearningGoalPlanDto> plans = service.generatePlan(input);
            return ResponseEntity.ok(plans);
        } catch (Exception e) {
            log.error("Plan generation failed", e);
            return ResponseEntity.internalServerError()
                    .body("Plan generation failed: " + e.getMessage());
        }
    }

    /**
     * POST /api/workshop/activities
     * Step 2.2: Given refined learning goals, generate teaching & assessment activities.
     */
    @PostMapping("/activities")
    public ResponseEntity<?> generateActivities(@RequestBody GenerateActivitiesRequestDto request) {
        try {
            List<LearningGoalPlanDto> goals = service.generateActivities(
                    request.goals(), request.meta(), request.availableMaterials());
            return ResponseEntity.ok(goals);
        } catch (Exception e) {
            log.error("Activity generation failed", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "Activity generation failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/workshop/session
     * Step 3: Generate the full timetable from goals with activities.
     */
    @PostMapping("/session")
    public ResponseEntity<?> generateSession(@RequestBody GenerateSessionRequestDto request) {
        try {
            WorkshopSessionDto session = service.generateSession(request.goals(), request.meta(), request.skeleton());
            return ResponseEntity.ok(session);
        } catch (Exception e) {
            log.error("Session generation failed", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "Session generation failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/workshop/export/pdf
     * Generate a beautiful PDF document from the session using the secondary LLM.
     */
    @PostMapping(value = "/export/pdf", produces = "application/pdf")
    public ResponseEntity<Resource> exportPdf(@RequestBody PdfExportRequestDto request) {
        try {
            byte[] pdfBytes = pdfService.exportToPdf(request);
            ByteArrayResource resource = new ByteArrayResource(pdfBytes);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"session.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(pdfBytes.length)
                    .body(resource);
        } catch (Exception e) {
            log.error("PDF export failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * POST /api/workshop/export/pptx
     * Generates a lecture slides PPTX file based on the session and metadata.
     */
    @PostMapping(value = "/export/pptx", produces = "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    public ResponseEntity<Resource> exportPptx(@RequestBody PdfExportRequestDto request) {
        try {
            byte[] pptxBytes = pptxService.exportToPptx(request);
            ByteArrayResource resource = new ByteArrayResource(pptxBytes);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"slides.pptx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                    .contentLength(pptxBytes.length)
                    .body(resource);
        } catch (Exception e) {
            log.error("PPTX export failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * POST /api/workshop/export/pptx-assemble
     * Assembles a PPTX from pre-built slides (frontend cache) — no LLM call needed.
     */
    @PostMapping(value = "/export/pptx-assemble", produces = "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    public ResponseEntity<Resource> exportPptxAssemble(@RequestBody PptxAssembleRequestDto request) {
        try {
            byte[] pptxBytes = pptxService.assembleFromSlides(request.session(), request.meta(), request.prebuiltSlides(), null);
            ByteArrayResource resource = new ByteArrayResource(pptxBytes);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"slides.pptx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                    .contentLength(pptxBytes.length)
                    .body(resource);
        } catch (Exception e) {
            log.error("PPTX assemble failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * POST /api/workshop/export/block-slides
     * Generates slide JSON for a single block (used for in-page preview + caching).
     */
    @PostMapping("/export/block-slides")
    public ResponseEntity<?> exportBlockSlides(@RequestBody BlockSlidesRequestDto request) {
        try {
            List<Map<String, Object>> slides = pptxService.generateBlockSlides(request.block(), request.meta());
            return ResponseEntity.ok(slides);
        } catch (Exception e) {
            log.error("Block slides generation failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Block slides generation failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/workshop/export/pptx-with-template
     * Exports full session to PPTX, optionally using an uploaded PPTX template and cached slides.
     */
    @PostMapping(value = "/export/pptx-with-template", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> exportPptxWithTemplate(
            @org.springframework.web.bind.annotation.RequestParam("session") String sessionJson,
            @org.springframework.web.bind.annotation.RequestParam("meta") String metaJson,
            @org.springframework.web.bind.annotation.RequestParam(value = "slides", required = false) String slidesJson,
            @org.springframework.web.bind.annotation.RequestPart(value = "template", required = false) org.springframework.web.multipart.MultipartFile template) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.workshopper.dto.WorkshopSessionDto session = mapper.readValue(sessionJson, com.workshopper.dto.WorkshopSessionDto.class);
            com.workshopper.dto.WorkshopInputDto meta = mapper.readValue(metaJson, com.workshopper.dto.WorkshopInputDto.class);
            
            List<Map<String, Object>> prebuiltSlides = null;
            if (slidesJson != null && !slidesJson.isBlank()) {
                prebuiltSlides = mapper.readValue(slidesJson, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            }

            java.io.InputStream templateStream = null;
            if (template != null && !template.isEmpty()) {
                templateStream = template.getInputStream();
            }

            byte[] pptxBytes = pptxService.assembleFromSlides(session, meta, prebuiltSlides, templateStream);
            
            org.springframework.core.io.ByteArrayResource resource = new org.springframework.core.io.ByteArrayResource(pptxBytes);
            String safeTitle = (session.title() != null ? session.title() : "Workshop").replaceAll("[^a-zA-Z0-9.-]", "_");
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeTitle + "_slides.pptx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                    .contentLength(pptxBytes.length)
                    .body(resource);
        } catch (Exception e) {
            log.error("PPTX template export failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/workshop/export/lecture/{id}/zip
     * Export all child sessions of a lecture into a ZIP file containing their PDFs and PPTXs.
     */
    @GetMapping(value = "/export/lecture/{id}/zip", produces = "application/zip")
    public ResponseEntity<Resource> exportLectureZip(@PathVariable String id) {
        try {
            byte[] zipBytes = service.exportLectureZip(id, pdfService, pptxService);
            ByteArrayResource resource = new ByteArrayResource(zipBytes);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lecture-materials.zip\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(zipBytes.length)
                    .body(resource);
        } catch (Exception e) {
            log.error("Lecture ZIP export failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * POST /api/workshop/refine-goal
     * Real-time feedback: given a single LG text, suggest refinements or splits.
     * Returns a JSON array of SuggestionDto objects (may be empty if the goal looks fine).
     */
    @PostMapping("/refine-goal")
    public ResponseEntity<?> refineGoal(@RequestBody RefineGoalRequestDto request) {
        try {
            var suggestions = service.refineGoal(request);
            return ResponseEntity.ok(suggestions);
        } catch (Exception e) {
            log.error("Goal refinement failed", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "Refinement failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/workshop/extract-goals
     * Extract learning goal strings from raw document text (e.g. uploaded PDF).
     * Returns a JSON array of plain goal strings (not full LearningGoalPlanDto).
     */
    @PostMapping("/extract-goals")
    public ResponseEntity<?> extractGoals(@RequestBody ExtractGoalsRequestDto request) {
        try {
            var goals = service.extractGoalsFromDocument(request);
            return ResponseEntity.ok(goals);
        } catch (Exception e) {
            log.error("Goal extraction failed", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "Extraction failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/workshop/fix-goals-grammar
     * Automatically fix grammar and obvious typos in learning goals without changing meaning.
     */
    @PostMapping("/fix-goals-grammar")
    public ResponseEntity<?> fixGoalsGrammar(@RequestBody List<String> goals) {
        try {
            var fixed = service.fixGoalsGrammar(goals);
            return ResponseEntity.ok(fixed);
        } catch (Exception e) {
            log.error("Grammar fix failed", e);
            return ResponseEntity.internalServerError().body(goals); // Fallback to original
        }
    }

    /**
     * POST /api/workshop/sessions/draft
     * Create or update a draft session (called at each step transition).
     * Returns {"id": "<session-id>"} so the frontend can store the id.
     */
    @PostMapping("/sessions/draft")
    public ResponseEntity<?> saveDraft(@RequestBody SaveDraftRequestDto request) {
        try {
            String id = service.saveDraft(request);
            return ResponseEntity.ok(Map.of("id", id));
        } catch (Exception e) {
            log.error("Draft save failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Draft save failed: " + e.getMessage()));
        }
    }

    /**
     * GET /api/workshop/sessions/{id}
     * Fetch full session detail including draft state for resumption.
     */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id) {
        return service.getSession(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/workshop/sessions
     * List all sessions (lightweight summary, no full blocks payload).
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionSummaryDto>> listSessions() {
        return ResponseEntity.ok(service.listSessions());
    }

    /**
     * POST /api/workshop/sessions/{id}/slides
     * Explicitly save the generated slide cache for a session.
     */
    @PostMapping("/sessions/{id}/slides")
    public ResponseEntity<?> saveSlides(@PathVariable String id, @RequestBody Map<Integer, List<Map<String, Object>>> slidesCache) {
        try {
            service.saveSlides(id, slidesCache);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Failed to save slides for session {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/workshop/sessions/{id}/template
     * Upload and save a PPTX template for the session.
     */
    @PostMapping(value = "/sessions/{id}/template", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadTemplate(@PathVariable String id, @org.springframework.web.bind.annotation.RequestPart("template") org.springframework.web.multipart.MultipartFile template) {
        try {
            service.saveTemplate(id, template.getBytes());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Failed to upload template for session {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/workshop/sessions/{id}/slides/preview
     * Returns an array of base64 encoded PNG renderings of the slides.
     */
    @GetMapping("/sessions/{id}/slides/preview")
    public ResponseEntity<Map<String, List<String>>> getSlidePreviews(@PathVariable String id) {
        try {
            com.workshopper.dto.SessionDetailDto detail = service.getSession(id)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
            byte[] templateData = service.getTemplate(id);
            java.io.InputStream templateStream = (templateData != null) ? new java.io.ByteArrayInputStream(templateData) : null;
            
            // Flatten the slides map into a single list
            List<Map<String, Object>> allSlides = new java.util.ArrayList<>();
            if (detail.session() != null && detail.session().slides() != null) {
                // Sorting by block index to ensure correct order
                detail.session().slides().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .forEach(e -> allSlides.addAll(e.getValue()));
            }

            if (allSlides.isEmpty()) {
                return ResponseEntity.ok(Map.of("images", List.of()));
            }

            List<String> base64Images = pptxService.renderAllSlidePreviews(detail.session(), null, allSlides, templateStream);
            
            return ResponseEntity.ok(Map.of("images", base64Images));
        } catch (Exception e) {
            log.error("Failed to render slide previews for session {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Delete a session completely.
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable String id) {
        try {
            service.deleteSession(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to delete session {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to delete session: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/workshop/sessions/{id}/finish
     * Mark a session as fully finished (user clicked Finish & Save on the Preparation step).
     */
    @PutMapping("/sessions/{id}/finish")
    public ResponseEntity<?> finishSession(@PathVariable String id) {
        try {
            service.finishSession(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Failed to finish session {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to finish session: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/workshop/sessions/{id}/rename
     * Rename a session.
     */
    @PutMapping("/sessions/{id}/rename")
    public ResponseEntity<?> renameSession(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            String newTitle = body.get("title");
            if (newTitle == null || newTitle.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));
            }
            service.renameSession(id, newTitle);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Failed to rename session {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to rename session: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/workshop/sessions/reorder
     * Reorder sessions.
     */
    @PutMapping("/sessions/reorder")
    public ResponseEntity<?> reorderSessions(@RequestBody java.util.List<String> sessionIds) {
        try {
            service.reorderSessions(sessionIds);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Failed to reorder sessions", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to reorder sessions: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/workshop/sessions/{id}/move
     * Move a session into a lecture.
     */
    @PutMapping("/sessions/{id}/move")
    public ResponseEntity<?> moveSession(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            String lectureId = body.get("lectureId");
            service.moveSession(id, lectureId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Failed to move session {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to move session: " + e.getMessage()));
        }
    }

    /**
     * GET /api/workshop/health
     * Simple health check.
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
