package com.workshopper.service;

import com.workshopper.model.CourseEntity;
import org.springframework.transaction.annotation.Transactional;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.workshopper.dto.*;
import com.workshopper.model.WorkshopSessionEntity;
import com.workshopper.repository.WorkshopSessionRepository;
import com.workshopper.repository.SlideTemplateRepository;
import com.workshopper.model.SlideTemplateEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class WorkshopService {

    private final com.workshopper.repository.CourseRepository courseRepository;

    private static final Logger log = LoggerFactory.getLogger(WorkshopService.class);

    private final LlmService llm;
    private final WorkshopSessionRepository repo;
    private final SlideTemplateRepository templateRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkshopService(LlmService llm, WorkshopSessionRepository repo, com.workshopper.repository.CourseRepository courseRepository, SlideTemplateRepository templateRepo) {
        this.llm = llm;
        this.repo = repo;
        this.courseRepository = courseRepository;
        this.templateRepo = templateRepo;
    }

    // ── Draft management ──────────────────────────────────────────────

    /**
     * Upsert a draft session record. If sessionId is provided and found, updates
     * it; otherwise creates a new entity and returns the assigned ID.
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
        if (req.courseId() != null)
            entity.setCourseId(req.courseId());
        if (req.draftStateJson() != null) {
            entity.setDraftStateJson(req.draftStateJson());
            try {
                com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(req.draftStateJson());
                if (rootNode.has("session") && !rootNode.get("session").isNull()) {
                    entity.setSessionJson(mapper.writeValueAsString(rootNode.get("session")));
                }
                // Also promote WorkshopInput scalars into real columns if present
                com.fasterxml.jackson.databind.JsonNode inputNode = rootNode.get("workshopInput");
                if (inputNode != null && !inputNode.isNull()) {
                    if (inputNode.has("duration")) entity.setDuration(inputNode.get("duration").asInt());
                    if (inputNode.has("participants")) entity.setParticipants(inputNode.get("participants").asInt());
                    if (inputNode.has("sessionType")) entity.setSessionType(inputNode.get("sessionType").asText());
                    if (inputNode.has("sessionTypeOther")) entity.setSessionTypeOther(inputNode.get("sessionTypeOther").asText());
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

    public void saveTemplate(String sessionId, byte[] templateData) {
        WorkshopSessionEntity entity = repo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        SlideTemplateEntity template = new SlideTemplateEntity();
        template.setId(sessionId); // Tier 1: 1:1 with session
        template.setOwnerId(entity.getOwnerId());
        template.setFileData(templateData);
        templateRepo.save(template);
        entity.setTemplateId(sessionId);
        repo.save(entity);
    }

    public byte[] getTemplate(String sessionId) {
        WorkshopSessionEntity entity = repo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        String templateId = entity.getTemplateId();
        if (templateId == null && entity.getCourseId() != null) {
            templateId = courseRepository.findById(entity.getCourseId())
                    .map(CourseEntity::getTemplateId)
                    .orElse(null);
        }
        if (templateId == null) return null;
        return templateRepo.findById(templateId).map(SlideTemplateEntity::getFileData).orElse(null);
    }

    public void saveCourseTemplate(String courseId, byte[] templateData) {
        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found: " + courseId));
        SlideTemplateEntity template = new SlideTemplateEntity();
        template.setId(courseId);
        template.setOwnerId(course.getOwnerId());
        template.setFileData(templateData);
        templateRepo.save(template);
        course.setTemplateId(courseId);
        courseRepository.save(course);
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
                                session.blocks(), session.omittedGoals(), slides);
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
                    e.getCourseId(),
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
                        e.getCourseId(),
                        e.getCreatedAt(),
                        e.getUpdatedAt()))
                .toList();
    }

    /**
     * Delete a session by ID.
     * Cascade deletion of child sessions when a Course (formerly Lecture) container
     * is deleted is now handled by the DB FK ON DELETE CASCADE — no manual loop needed.
     */
    public void deleteSession(String id) {
        repo.deleteById(id);
    }

    // exportCourseZip moved to Facade

    /**
     * Mark a session as fully finished (user clicked Finish & Save on Preparation step).
     */
    public void finishSession(String id) {
        repo.findById(id).ifPresent(entity -> {
            entity.setCurrentStep("finished");
            entity.setStatus("complete");
            repo.save(entity);
        });
    }

    /** Move a session to a course. */
    public void moveSession(String id, String courseId) {
        repo.findById(id).ifPresent(entity -> {
            entity.setCourseId(courseId);
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
                        ((com.fasterxml.jackson.databind.node.ObjectNode) rootNode.get("session")).put("title", newTitle);
                        entity.setDraftStateJson(mapper.writeValueAsString(rootNode));
                    }
                } catch (Exception e) {
                    log.warn("Failed to update title in draftStateJson for {}", id);
                }
            }

            repo.save(entity);
        });
    }

    @Transactional
    public String saveCourseDraft(SaveDraftRequestDto request, String ownerId) {
        CourseEntity course = null;
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            course = courseRepository.findById(request.sessionId()).orElse(null);
            if (course != null && !course.getOwnerId().equals(ownerId)) {
                throw new org.springframework.security.access.AccessDeniedException("Not authorized");
            }
        }
        if (course == null) {
            course = new CourseEntity();
            course.setOwnerId(ownerId);
            course.setStatus("draft");
            
        }

        course.setCurrentStep(request.currentStep());
        if (request.title() != null && !request.title().isBlank()) {
            course.setTitle(request.title());
        } else if (course.getTitle() == null) {
            course.setTitle("Workshop Course");
        }
        
        course.setDraftStateJson(request.draftStateJson());
        

        course = courseRepository.save(course);
        return course.getId();
    }

    @Transactional
    public void deleteCourse(String id, String ownerId) {
        CourseEntity course = courseRepository.findById(id).orElseThrow();
        if (!course.getOwnerId().equals(ownerId)) {
            throw new org.springframework.security.access.AccessDeniedException("Not authorized");
        }
        courseRepository.delete(course);
    }

}
