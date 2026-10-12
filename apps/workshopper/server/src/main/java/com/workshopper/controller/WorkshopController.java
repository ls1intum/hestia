package com.workshopper.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workshopper.config.AuthContext;
import com.workshopper.dto.*;
import com.workshopper.facade.WorkshopSessionFacade;
import com.workshopper.service.PdfExportService;
import com.workshopper.service.WorkshopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Sessions, generation and exports. Errors are turned into HTTP answers by {@link ApiExceptionHandler};
 * every endpoint that takes a session or course id checks ownership first.
 */
@RestController
@RequestMapping("/api/workshop")
public class WorkshopController {

    private static final Logger log = LoggerFactory.getLogger(WorkshopController.class);
    private static final MediaType PPTX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final String DEFAULT_TEMPLATE = "templates/workshopper-default.pptx";

    private final WorkshopService service;
    private final PdfExportService pdfService;
    private final WorkshopSessionFacade facade;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkshopController(WorkshopService service, PdfExportService pdfService, WorkshopSessionFacade facade) {
        this.service = service;
        this.pdfService = pdfService;
        this.facade = facade;
    }

    // ── Generation ──────────────────────────────────────────────────────────

    /** Generates the timetable (or a single block of it) from the goals and the client-built skeleton. */
    @PostMapping("/session")
    public WorkshopSessionDto generateSession(@RequestBody GenerateSessionRequestDto request) throws Exception {
        return facade.generateAndSaveSession(request);
    }

    /** Generates the slides of one block (preview in the slide workstation). */
    @PostMapping("/export/block-slides")
    public List<Map<String, Object>> exportBlockSlides(@RequestBody BlockSlidesRequestDto request) throws Exception {
        return facade.generateBlockSlides(request.block(), request.meta(), request.goals());
    }

    /** Checks one learning goal; returns suggestions to split or refine it (empty if it looks fine). */
    @PostMapping("/refine-goal")
    public List<GoalSuggestionDto> refineGoal(@RequestBody RefineGoalRequestDto request) throws Exception {
        return facade.refineGoal(request);
    }

    /** Fixes grammar and typos in the goals without changing their meaning. */
    @PostMapping("/fix-goals-grammar")
    public List<String> fixGoalsGrammar(@RequestBody List<String> goals) {
        try {
            return facade.fixGoalsGrammar(goals);
        } catch (Exception e) {
            // Optional polish: if the LLM call fails, the wizard continues with the goals as entered
            log.warn("Grammar fix failed, returning the original goals: {}", e.getMessage());
            return goals;
        }
    }

    // ── Exports ─────────────────────────────────────────────────────────────

    /** Renders the session timetable as a PDF (no LLM call). */
    @PostMapping(value = "/export/pdf", produces = "application/pdf")
    public ResponseEntity<Resource> exportPdf(@RequestBody PdfExportRequestDto request) throws Exception {
        return file(pdfService.exportToPdf(request), "session.pdf", MediaType.APPLICATION_PDF);
    }

    /** Generates the slide deck with the LLM, in the session's effective template. */
    @PostMapping(value = "/export/pptx", produces = "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    public ResponseEntity<Resource> exportPptx(@RequestBody PdfExportRequestDto request) throws Exception {
        return file(buildDeck(request.session(), request.meta(), null, null), "slides.pptx", PPTX);
    }

    /** Builds the slide deck from slides that were already generated (no LLM call). */
    @PostMapping(value = "/export/pptx-assemble", produces = "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    public ResponseEntity<Resource> exportPptxAssemble(@RequestBody PptxAssembleRequestDto request) throws Exception {
        return file(buildDeck(request.session(), request.meta(), request.prebuiltSlides(), null), "slides.pptx", PPTX);
    }

    /**
     * Builds the slide deck from the given slides, in an uploaded template if one is sent,
     * otherwise in the template stored for the session (or its course).
     */
    @PostMapping(value = "/export/pptx-with-template", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> exportPptxWithTemplate(
            @RequestParam("session") String sessionJson,
            @RequestParam("meta") String metaJson,
            @RequestParam(value = "slides", required = false) String slidesJson,
            @RequestPart(value = "template", required = false) MultipartFile template) throws Exception {
        WorkshopSessionDto session = mapper.readValue(sessionJson, WorkshopSessionDto.class);
        WorkshopInputDto meta = mapper.readValue(metaJson, WorkshopInputDto.class);
        List<Map<String, Object>> slides = slidesJson == null || slidesJson.isBlank() ? null
                : mapper.readValue(slidesJson, new TypeReference<List<Map<String, Object>>>() {});
        byte[] pptx = buildDeck(session, meta, slides, template != null && !template.isEmpty() ? template : null);
        String safeTitle = (session.title() != null ? session.title() : "Workshop").replaceAll("[^a-zA-Z0-9.-]", "_");
        return file(pptx, safeTitle + "_slides.pptx", PPTX);
    }

    /** All finished sessions of a course as one ZIP of PDFs and PPTXs. */
    @GetMapping(value = "/export/course/{id}/zip", produces = "application/zip")
    public ResponseEntity<Resource> exportCourseZip(@PathVariable String id) throws Exception {
        facade.verifyCourseOwnership(id);
        return file(facade.exportCourseZip(id), "course-materials.zip", MediaType.parseMediaType("application/zip"));
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    /** Lists the user's sessions (summaries, no blocks). */
    @GetMapping("/sessions")
    public List<SessionSummaryDto> listSessions() {
        return service.listSessions(AuthContext.getCurrentUserId());
    }

    /** The full session including its draft state, for resuming it. */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<SessionDetailDto> getSession(@PathVariable String id) {
        facade.verifyOwnership(id);
        return ResponseEntity.of(service.getSession(id));
    }

    /** Creates or updates a draft (called while the user moves through the wizard); returns {"id": ...}. */
    @PostMapping("/sessions/draft")
    public Map<String, String> saveDraft(@RequestBody SaveDraftRequestDto request) {
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            facade.verifyOwnership(request.sessionId());
        }
        if (request.courseId() != null && !request.courseId().isBlank()) {
            facade.verifyCourseOwnership(request.courseId());
        }
        return Map.of("id", service.saveDraft(request, AuthContext.getCurrentUserId()));
    }

    /** Saves the generated slides of a session, keyed by block index. */
    @PostMapping("/sessions/{id}/slides")
    public Map<String, Boolean> saveSlides(@PathVariable String id,
                                           @RequestBody Map<Integer, List<Map<String, Object>>> slidesCache) throws Exception {
        facade.verifyOwnership(id);
        service.saveSlides(id, slidesCache);
        return Map.of("success", true);
    }

    /** Marks a session as finished. */
    @PutMapping("/sessions/{id}/finish")
    public Map<String, Boolean> finishSession(@PathVariable String id) {
        facade.verifyOwnership(id);
        service.finishSession(id);
        return Map.of("success", true);
    }

    @PutMapping("/sessions/{id}/rename")
    public ResponseEntity<Map<String, Object>> renameSession(@PathVariable String id, @RequestBody Map<String, String> body) {
        facade.verifyOwnership(id);
        String newTitle = body.get("title");
        if (newTitle == null || newTitle.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));
        }
        service.renameSession(id, newTitle);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Saves the dashboard order of the given sessions. */
    @PutMapping("/sessions/reorder")
    public Map<String, Boolean> reorderSessions(@RequestBody List<String> sessionIds) {
        sessionIds.forEach(facade::verifyOwnership);
        service.reorderSessions(sessionIds);
        return Map.of("success", true);
    }

    /** Moves a session into a course ({"courseId": "..."}), or back to the top level with an empty courseId. */
    @PutMapping("/sessions/{id}/move")
    public Map<String, Boolean> moveSession(@PathVariable String id, @RequestBody Map<String, String> body) {
        facade.verifyOwnership(id);
        String courseId = body.get("courseId");
        if (courseId != null && courseId.isBlank()) {
            courseId = null; // "" = move back to top level (an empty FK value would violate fk_workshop_sessions_course)
        }
        if (courseId != null) {
            facade.verifyCourseOwnership(courseId);
        }
        service.moveSession(id, courseId);
        return Map.of("success", true);
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        facade.verifyOwnership(id);
        service.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    // ── Slide templates ─────────────────────────────────────────────────────

    @PostMapping(value = "/sessions/{id}/template", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Boolean> uploadTemplate(@PathVariable String id, @RequestPart("template") MultipartFile template) throws IOException {
        facade.verifyOwnership(id);
        service.saveTemplate(id, template.getBytes(), template.getOriginalFilename());
        return Map.of("success", true);
    }

    /** Removes the template from this session only (its own upload, and inheriting the course template). */
    @DeleteMapping("/sessions/{id}/template")
    public Map<String, Boolean> deleteTemplate(@PathVariable String id) {
        facade.verifyOwnership(id);
        service.deleteTemplate(id);
        return Map.of("success", true);
    }

    @PostMapping("/courses/{id}/template")
    public ResponseEntity<Void> uploadCourseTemplate(@PathVariable String id, @RequestPart("template") MultipartFile template) throws IOException {
        facade.verifyCourseOwnership(id);
        service.saveCourseTemplate(id, template.getBytes(), template.getOriginalFilename());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/courses/{id}/template")
    public ResponseEntity<Void> deleteCourseTemplate(@PathVariable String id) {
        facade.verifyCourseOwnership(id);
        service.deleteCourseTemplate(id);
        return ResponseEntity.noContent().build();
    }

    /** Simple health check; the only endpoint that needs no login. */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds the PPTX for a session. Uses the uploaded template if given, otherwise the session's
     * effective template (own upload, then course template), otherwise the default design.
     * Slides are generated with the LLM when {@code prebuiltSlides} is null.
     */
    private byte[] buildDeck(WorkshopSessionDto session, WorkshopInputDto meta,
                             List<Map<String, Object>> prebuiltSlides, MultipartFile uploadedTemplate) throws Exception {
        String sessionId = session != null ? session.id() : null;
        if (sessionId != null) {
            facade.verifyOwnership(sessionId);
        }
        if (uploadedTemplate != null) {
            return facade.assemblePptx(session, meta, prebuiltSlides, uploadedTemplate.getInputStream(), true);
        }
        byte[] stored = sessionId != null ? service.getTemplate(sessionId) : null;
        InputStream template = stored != null ? new ByteArrayInputStream(stored) : defaultTemplate();
        return facade.assemblePptx(session, meta, prebuiltSlides, template, stored != null);
    }

    private static InputStream defaultTemplate() {
        try {
            return new ClassPathResource(DEFAULT_TEMPLATE).getInputStream();
        } catch (IOException e) {
            log.warn("Failed to load default template", e);
            return null;
        }
    }

    private static ResponseEntity<Resource> file(byte[] bytes, String fileName, MediaType type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(type)
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }
}
