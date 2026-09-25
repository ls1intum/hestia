package com.workshopper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workshopper.dto.*;
import com.workshopper.model.WorkshopSessionEntity;
import com.workshopper.repository.WorkshopSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class WorkshopService {

    private static final Logger log = LoggerFactory.getLogger(WorkshopService.class);

    private final LlmService llm;
    private final WorkshopSessionRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkshopService(LlmService llm, WorkshopSessionRepository repo) {
        this.llm = llm;
        this.repo = repo;
    }

    // ── Step 1: Generate learning goal plans ──────────────────────

    public List<LearningGoalPlanDto> generatePlan(WorkshopInputDto input) throws Exception {
        String sessionTypeLabel = resolveSessionType(input.sessionType(), input.sessionTypeOther());
        
        StringBuilder goalsList = new StringBuilder();
        if (input.learningGoals() != null && !input.learningGoals().isEmpty()) {
            for (String g : input.learningGoals()) {
                String cleaned = g.replaceAll("(?i)^\\s*LG\\s*\\d+\\s*[:.-]\\s*", "").trim();
                goalsList.append("  * ").append(cleaned).append("\n");
            }
        }
        
        String doc = input.sourceDocument();
        if (doc != null && doc.length() > 8000) {
            doc = doc.substring(0, 8000) + "\n[... truncated ...]";
        }
        
        log.debug("Generating plan for goals: {}", input.learningGoals());
        return llm.generateLearningGoals(input, sessionTypeLabel, goalsList.toString(), doc);
    }


    // ── Real-time goal refinement (Step 3) ───────────────────────────

    public List<GoalSuggestionDto> refineGoal(RefineGoalRequestDto request) throws Exception {
        var ctx = request.context() != null ? request.context() : java.util.Map.of();
        String sessionType = ctx.getOrDefault("sessionType", "workshop").toString();
        String background = ctx.getOrDefault("studentBackground", "").toString();

        @SuppressWarnings("unchecked")
        java.util.List<String> subSkills = (java.util.List<String>) ctx.getOrDefault("subSkills", java.util.Collections.emptyList());
        String subSkillsContext = "";
        if (!subSkills.isEmpty()) {
            subSkillsContext = "\nAdditionally, this goal is supported by the following granular sub-skills:\n- " 
                    + String.join("\n- ", subSkills)
                    + "\n\nIf the learning goal text above is a broad 'Terminal Competency' that combines many concepts, use these sub-skills as a strong hint for how to split it into distinct, actionable Workshop goals. You can combine closely related sub-skills into a single goal, or elevate major sub-skills into their own goals.";
        }

        return llm.refineGoal(request, sessionType, background, subSkillsContext);
    }

    // ── Extract LGs from uploaded document (Step 3 file upload) ─────

    public List<String> extractGoalsFromDocument(ExtractGoalsRequestDto request) throws Exception {
        var ctx = request.context() != null ? request.context() : java.util.Map.of();
        String sessionType = ctx.getOrDefault("sessionType", "workshop").toString();
        String background = ctx.getOrDefault("studentBackground", "").toString();

        // Truncate document to avoid exceeding token budget
        String doc = request.documentText();
        if (doc != null && doc.length() > 10000)
            doc = doc.substring(0, 10000) + "\n[... truncated ...]";

        return llm.extractGoalsFromDocument(sessionType, background, doc);
    }

    // ── Automatic grammar check for LGs ──────────────────────────────────────

    public List<String> fixGoalsGrammar(List<String> goals) throws Exception {
        if (goals == null || goals.isEmpty())
            return goals;
        return llm.fixGoalsGrammar(goals);
    }

    // ── Step 1b: placeholder (activities are selected by user in Step 2) ─


    // ── Draft management ──────────────────────────────────────────────

    /**
     * Upsert a draft session record. If sessionId is provided and found, updates
     * it;
     * otherwise creates a new entity and returns the assigned ID.
     */
    public String saveDraft(SaveDraftRequestDto req, String ownerId) {
        WorkshopSessionEntity entity = null;
        if (req.sessionId() != null && !req.sessionId().isBlank()) {
            entity = repo.findById(req.sessionId()).orElse(null);
        }
        if (entity == null) {
            entity = new WorkshopSessionEntity();
            entity.setOwnerId(ownerId); // Bind the new draft to the creator
        }
        if (req.title() != null)
            entity.setTitle(req.title());
        if (req.learningGoal() != null)
            entity.setLearningGoal(req.learningGoal());
        if (req.currentStep() != null)
            entity.setCurrentStep(req.currentStep());
        if (req.type() != null)
            entity.setType(req.type());
        if (req.lectureId() != null)
            entity.setLectureId(req.lectureId());
        if (req.draftStateJson() != null) {
            entity.setDraftStateJson(req.draftStateJson());
            try {
                com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(req.draftStateJson());
                if (rootNode.has("session") && !rootNode.get("session").isNull()) {
                    entity.setSessionJson(mapper.writeValueAsString(rootNode.get("session")));
                }
            } catch (Exception e) {
                log.warn("Failed to extract session from draftStateJson", e);
            }
        }

        if ("result".equals(req.currentStep()) || "prepare".equals(req.currentStep())
                || "finished".equals(req.currentStep())) {
            entity.setStatus("complete");
        } else {
            entity.setStatus("draft");
        }
        return repo.save(entity).getId();
    }

    public void saveSlides(String id, java.util.Map<Integer, List<java.util.Map<String, Object>>> slidesCache)
            throws Exception {
        WorkshopSessionEntity entity = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        entity.setSlidesJson(mapper.writeValueAsString(slidesCache));
        repo.save(entity);
    }

    public void saveTemplate(String id, byte[] templateData) {
        WorkshopSessionEntity entity = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        entity.setTemplateData(templateData);
        repo.save(entity);
    }

    public byte[] getTemplate(String id) {
        return repo.findById(id).map(WorkshopSessionEntity::getTemplateData).orElse(null);
    }

    /** Fetch a single session by ID, returning its full detail + draft state. */
    public Optional<SessionDetailDto> getSession(String id) {
        return repo.findById(id).map(e -> {
            WorkshopSessionDto session = null;
            if (e.getSessionJson() != null) {
                try {
                    session = mapper.readValue(e.getSessionJson(), WorkshopSessionDto.class);
                    // Also parse slides cache if present
                    if (e.getSlidesJson() != null && !e.getSlidesJson().isBlank()) {
                        java.util.Map<Integer, List<java.util.Map<String, Object>>> slides = mapper.readValue(
                                e.getSlidesJson(),
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<Integer, List<java.util.Map<String, Object>>>>() {
                                });
                        session = new WorkshopSessionDto(
                                session.id(), session.title(), session.learningGoal(), session.studentBackground(),
                                session.prerequisites(), session.blocks(), session.omittedGoals(), slides);
                    }
                } catch (Exception ex) {
                    log.warn("Could not deserialise session JSON for id={}", id);
                }
            }
            return new SessionDetailDto(
                    e.getId(),
                    e.getTitle(),
                    e.getStatus(),
                    e.getCurrentStep(),
                    e.getType(),
                    e.getLectureId(),
                    e.getDraftStateJson(),
                    session);
        });
    }

    /** Lightweight list for the dashboard (no blocks payload). */
    public List<SessionSummaryDto> listSessions(String ownerId) {
        return repo.findByOwnerIdOrdered(ownerId).stream()
                .map(e -> new SessionSummaryDto(
                        e.getId(),
                        e.getTitle() != null ? e.getTitle() : "Workshop Session",
                        e.getLearningGoal(),
                        e.getStatus(),
                        e.getCurrentStep(),
                        e.getType(),
                        e.getLectureId(),
                        e.getCreatedAt(),
                        e.getUpdatedAt()))
                .toList();
    }

    /** Delete a session by ID. If it's a Lecture, cascade-delete its child sessions too —
     *  matches the frontend's confirmation dialog, which already warns
     *  "This will permanently delete the lecture and its N sessions: ...". */
    public void deleteSession(String id) {
        WorkshopSessionEntity entity = repo.findById(id).orElse(null);
        if (entity != null && "LECTURE".equals(entity.getType())) {
            List<WorkshopSessionEntity> children = repo.findAllByLectureIdOrdered(id);
            if (!children.isEmpty()) {
                repo.deleteAll(children);
            }
        }
        repo.deleteById(id);
    }

    /** Export all child sessions of a lecture into a ZIP file. */
    public byte[] exportLectureZip(String lectureId,
            com.workshopper.service.PdfExportService pdfService,
            com.workshopper.usecase.AssemblePptxUseCase assemblePptxUseCase) throws Exception {
        List<WorkshopSessionEntity> children = repo.findAllByLectureIdOrdered(lectureId);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos);

        int counter = 1;
        for (WorkshopSessionEntity e : children) {
            if (!"complete".equals(e.getStatus()) || e.getDraftStateJson() == null)
                continue;
            try {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(e.getDraftStateJson());
                com.workshopper.dto.WorkshopSessionDto session = null;
                if (root.has("session") && !root.get("session").isNull()) {
                    session = mapper.treeToValue(root.get("session"), com.workshopper.dto.WorkshopSessionDto.class);
                }
                com.workshopper.dto.WorkshopInputDto meta = null;
                if (root.has("workshopInput") && !root.get("workshopInput").isNull()) {
                    meta = mapper.treeToValue(root.get("workshopInput"), com.workshopper.dto.WorkshopInputDto.class);
                }

                // fallback to extract title if needed
                if (session != null && session.title() == null && e.getTitle() != null) {
                    session = new com.workshopper.dto.WorkshopSessionDto(
                            session.id(),
                            e.getTitle(),
                            session.learningGoal(),
                            session.studentBackground(),
                            session.prerequisites(),
                            session.blocks(),
                            session.omittedGoals(),
                            session.slides());
                }

                if (session != null) {
                    com.workshopper.dto.PdfExportRequestDto requestDto = new com.workshopper.dto.PdfExportRequestDto(
                            session, meta, java.util.List.of());
                    String safeTitle = (session.title() != null ? session.title() : "Session_" + counter)
                            .replaceAll("[^a-zA-Z0-9.-]", "_");

                    // PDF
                    byte[] pdfBytes = pdfService.exportToPdf(requestDto);
                    zos.putNextEntry(new java.util.zip.ZipEntry(safeTitle + "/timetable.pdf"));
                    zos.write(pdfBytes);
                    zos.closeEntry();

                    // PPTX
                    byte[] templateData = getTemplate(e.getId());
                    java.io.InputStream templateStream = (templateData != null) ? new java.io.ByteArrayInputStream(templateData) : null;
                    byte[] pptxBytes = assemblePptxUseCase.execute(requestDto.session(), requestDto.meta(), null, templateStream);
                    zos.putNextEntry(new java.util.zip.ZipEntry(safeTitle + "/slides.pptx"));
                    zos.write(pptxBytes);
                    zos.closeEntry();

                    counter++;
                }
            } catch (Exception ex) {
                log.warn("Failed to export child session {} in lecture {}", e.getId(), lectureId, ex);
            }
        }

        zos.close();
        return baos.toByteArray();
    }

    /**
     * Mark a session as fully finished (user clicked Finish & Save on Preparation
     * step).
     */
    public void finishSession(String id) {
        repo.findById(id).ifPresent(entity -> {
            entity.setCurrentStep("finished");
            entity.setStatus("complete");
            repo.save(entity);
        });
    }

    /** Move a session to a lecture. */
    public void moveSession(String id, String lectureId) {
        repo.findById(id).ifPresent(entity -> {
            entity.setLectureId(lectureId);
            entity.setDisplayOrder(null);
            repo.save(entity);
        });
    }

    /** Reorder sessions. */
    @org.springframework.transaction.annotation.Transactional
    public void reorderSessions(java.util.List<String> sessionIds) {
        for (int i = 0; i < sessionIds.size(); i++) {
            int order = i;
            repo.findById(sessionIds.get(i)).ifPresent(entity -> {
                entity.setDisplayOrder(order);
                repo.save(entity);
            });
        }
    }

    /** Rename a session. */
    public void renameSession(String id, String newTitle) {
        repo.findById(id).ifPresent(entity -> {
            entity.setTitle(newTitle);

            // Update title inside sessionJson if it exists
            if (entity.getSessionJson() != null && !entity.getSessionJson().isBlank()) {
                try {
                    com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(entity.getSessionJson());
                    if (rootNode.isObject()) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) rootNode).put("title", newTitle);
                        entity.setSessionJson(mapper.writeValueAsString(rootNode));
                    }
                } catch (Exception e) {
                    log.warn("Failed to update title in sessionJson for {}", id);
                }
            }

            // Update title inside draftStateJson if it exists
            if (entity.getDraftStateJson() != null && !entity.getDraftStateJson().isBlank()) {
                try {
                    com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(entity.getDraftStateJson());
                    if (rootNode.isObject() && rootNode.has("session") && rootNode.get("session").isObject()) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) rootNode.get("session")).put("title",
                                newTitle);
                        entity.setDraftStateJson(mapper.writeValueAsString(rootNode));
                    }
                } catch (Exception e) {
                    log.warn("Failed to update title in draftStateJson for {}", id);
                }
            }

            repo.save(entity);
        });
    }

    private String resolveSessionType(String type, String other) {
        return switch (type == null ? "workshop" : type) {
            case "lecture" -> "Lecture";
            case "exercise" -> "Exercise session";
            case "seminar" -> "Seminar";
            case "practical" -> "Practical course";
            case "other" -> (other != null && !other.isBlank()) ? other : "Other";
            default -> "Workshop";
        };
    }
}
