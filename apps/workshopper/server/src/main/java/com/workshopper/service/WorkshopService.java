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
import com.workshopper.facade.UsageGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workshopper.repository.CourseRepository;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

@Service
public class WorkshopService {

    private final CourseRepository courseRepository;

    private static final Logger log = LoggerFactory.getLogger(WorkshopService.class);

    private final WorkshopSessionRepository repo;
    private final SlideTemplateRepository templateRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkshopService(WorkshopSessionRepository repo, CourseRepository courseRepository, SlideTemplateRepository templateRepo) {
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
        UsageGuard.checkTitle(req.title());
        UsageGuard.checkStoredSize("The session", req.draftStateJson(), UsageGuard.MAX_DRAFT_CHARS);
        WorkshopSessionEntity entity = null;
        if (req.sessionId() != null && !req.sessionId().isBlank()) {
            // Deleted in the meantime (e.g. in another tab): say so instead of re-creating it
            entity = repo.findById(req.sessionId()).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "This session no longer exists. It may have been deleted."));
        }
        if (entity == null) {
            UsageGuard.checkSessionCount(repo.countByOwnerId(ownerId));
            entity = new WorkshopSessionEntity();
            entity.setOwnerId(ownerId); // Bind the new draft to the creator
        }
        if (req.title() != null)
            entity.setTitle(req.title());
        if (req.currentStep() != null)
            entity.setCurrentStep(req.currentStep());
        if (req.courseId() != null)
            entity.setCourseId(req.courseId());
        if (req.draftStateJson() != null) {
            entity.setDraftStateJson(req.draftStateJson());
            try {
                JsonNode rootNode = mapper.readTree(req.draftStateJson());
                if (rootNode.has("session") && !rootNode.get("session").isNull()) {
                    entity.setSessionJson(mapper.writeValueAsString(rootNode.get("session")));
                }
                // Also promote WorkshopInput scalars into real columns if present
                JsonNode inputNode = rootNode.get("workshopInput");
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

        // Preparation and Final Review work on a generated session, so it stays complete there
        if ("result".equals(req.currentStep()) || "prepare".equals(req.currentStep())
                || "final-review".equals(req.currentStep())
                || "finished".equals(req.currentStep())) {
            entity.setStatus("complete");
        } else {
            entity.setStatus("draft");
        }
        return repo.save(entity).getId();
    }

    public void saveSlides(String id, Map<Integer, List<Map<String, Object>>> slidesCache)
            throws Exception {
        WorkshopSessionEntity entity = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        String json = mapper.writeValueAsString(slidesCache);
        UsageGuard.checkStoredSize("The slides", json, UsageGuard.MAX_SLIDES_CHARS);
        entity.setSlidesJson(json);
        repo.save(entity);
    }

public void saveTemplate(String sessionId, byte[] templateData, String fileName) {
        WorkshopSessionEntity entity = repo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        SlideTemplateEntity template = new SlideTemplateEntity();
        template.setId(sessionId); // Tier 1: 1:1 with session
        template.setOwnerId(entity.getOwnerId());
        template.setFileName(fileName);
        template.setFileData(templateData);
        templateRepo.save(template);
        entity.setTemplateId(sessionId);
        entity.setUseDefaultTemplate(false);
        repo.save(entity);
    }

    /**
     * Removes the template from this session only: its own upload is deleted and it stops
     * inheriting the course template, so it uses the default design. The course template is untouched.
     */
    @Transactional
    public void deleteTemplate(String sessionId) {
        WorkshopSessionEntity entity = repo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        if (entity.getTemplateId() != null) {
            templateRepo.deleteById(entity.getTemplateId());
            entity.setTemplateId(null);
        }
        entity.setUseDefaultTemplate(true);
        repo.save(entity);
    }

    /** The id of the template that applies to a session: its own, else its course's (unless it opted out). */
    private String effectiveTemplateId(WorkshopSessionEntity entity) {
        if (entity.getTemplateId() != null) return entity.getTemplateId();
        if (entity.isUseDefaultTemplate() || entity.getCourseId() == null) return null;
        return courseRepository.findById(entity.getCourseId()).map(CourseEntity::getTemplateId).orElse(null);
    }

    /** Returns the effective template (session's own, else its course's), or null to use the default. */
    public byte[] getTemplate(String sessionId) {
        // Unknown/unsaved session IDs fall back to the default template instead of failing the export.
        WorkshopSessionEntity entity = repo.findById(sessionId).orElse(null);
        if (entity == null) return null;
        String templateId = effectiveTemplateId(entity);
        if (templateId == null) return null;
        return templateRepo.findById(templateId).map(SlideTemplateEntity::getFileData).orElse(null);
    }

    /** The stored file name of a template, or null. */
    public String templateFileName(String templateId) {
        return templateId == null ? null : templateRepo.findFileNameById(templateId).orElse(null);
    }

    public void saveCourseTemplate(String courseId, byte[] templateData, String fileName) {
        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found: " + courseId));
        SlideTemplateEntity template = new SlideTemplateEntity();
        template.setId(courseId);
        template.setOwnerId(course.getOwnerId());
        template.setFileName(fileName);
        template.setFileData(templateData);
        templateRepo.save(template);
        course.setTemplateId(courseId);
        courseRepository.save(course);
    }

    /** Removes a course's template; its sessions fall back to the default design (or their own template). */
    @Transactional
    public void deleteCourseTemplate(String courseId) {
        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found: " + courseId));
        if (course.getTemplateId() != null) {
            String templateId = course.getTemplateId();
            course.setTemplateId(null);
            courseRepository.save(course);
            templateRepo.deleteById(templateId);
        }
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
                        Map<Integer, List<Map<String, Object>>> slides = mapper.readValue(
                                e.getSlidesJson(),
                                new TypeReference<Map<Integer, List<Map<String, Object>>>>() {
                                });
                        session = new WorkshopSessionDto(
                                session.id(), session.title(), session.studentBackground(),
                                session.blocks(), slides);
                    }
                } catch (Exception ex) {
                    log.warn("Could not deserialise session JSON for id={}", id);
                }
            }
            String templateId = effectiveTemplateId(e);
            String templateSource = templateId == null ? null
                    : templateId.equals(e.getTemplateId()) ? "SESSION" : "COURSE";
            return new SessionDetailDto(
                    e.getId(),
                    e.getTitle(),
                    e.getStatus(),
                    e.getCurrentStep(),
                    e.getCourseId(),
                    e.getDraftStateJson(),
                    templateId != null,
                    templateSource,
                    templateFileName(templateId),
                    session);
        });
    }

    /** Lightweight list for the dashboard (no blocks payload). */
    public List<SessionSummaryDto> listSessions(String ownerId) {
        return repo.findByOwnerIdOrdered(ownerId).stream()
                .map(e -> new SessionSummaryDto(
                        e.getId(),
                        e.getTitle() != null ? e.getTitle() : "Workshop Session",
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
    @Transactional
    public void deleteSession(String id) {
        String templateId = repo.findById(id).map(WorkshopSessionEntity::getTemplateId).orElse(null);
        repo.deleteById(id);
        // Templates are stored 1:1 per owner entity; the FK is ON DELETE SET NULL, so clean up explicitly.
        if (templateId != null && templateId.equals(id)) {
            templateRepo.deleteById(templateId);
        }
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
            if (!Objects.equals(entity.getCourseId(), courseId)) {
                entity.setUseDefaultTemplate(false); // a session moved to another course inherits its template
            }
            entity.setCourseId(courseId);
            entity.setDisplayOrder(null);
            repo.save(entity);
        });
    }

    /** Reorder sessions. */
    @Transactional
    public void reorderSessions(List<String> sessionIds) {
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
        UsageGuard.checkTitle(newTitle);
        repo.findById(id).ifPresent(entity -> {
            entity.setTitle(newTitle);

            // Update title inside sessionJson if it exists
            if (entity.getSessionJson() != null && !entity.getSessionJson().isBlank()) {
                try {
                    JsonNode rootNode = mapper.readTree(entity.getSessionJson());
                    if (rootNode.isObject()) {
                        ((ObjectNode) rootNode).put("title", newTitle);
                        entity.setSessionJson(mapper.writeValueAsString(rootNode));
                    }
                } catch (Exception e) {
                    log.warn("Failed to update title in sessionJson for {}", id);
                }
            }

            // Update title inside draftStateJson if it exists
            if (entity.getDraftStateJson() != null && !entity.getDraftStateJson().isBlank()) {
                try {
                    JsonNode rootNode = mapper.readTree(entity.getDraftStateJson());
                    if (rootNode.isObject() && rootNode.has("session") && rootNode.get("session").isObject()) {
                        ((ObjectNode) rootNode.get("session")).put("title", newTitle);
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
        UsageGuard.checkTitle(request.title());
        UsageGuard.checkStoredSize("The course", request.draftStateJson(), UsageGuard.MAX_DRAFT_CHARS);
        CourseEntity course = null;
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            // Deleted in the meantime (e.g. in another tab): say so instead of re-creating it
            course = courseRepository.findById(request.sessionId()).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "This course no longer exists. It may have been deleted."));
            if (!ownerId.equals(course.getOwnerId())) {
                throw new AccessDeniedException("Not authorized");
            }
        }
        if (course == null) {
            UsageGuard.checkCourseCount(courseRepository.countByOwnerId(ownerId));
            course = new CourseEntity();
            course.setOwnerId(ownerId);
            course.setStatus("draft");
            
        }

        // Only overwrite fields that were actually sent, so a partial save can't wipe the stored state.
        if (request.currentStep() != null) {
            course.setCurrentStep(request.currentStep());
        }
        if (request.title() != null && !request.title().isBlank()) {
            course.setTitle(request.title());
        } else if (course.getTitle() == null) {
            course.setTitle("Workshop Course");
        }
        
        if (request.draftStateJson() != null) {
            course.setDraftStateJson(request.draftStateJson());
        }
        

        course = courseRepository.save(course);
        return course.getId();
    }

    /** The user's courses for the dashboard. */
    public List<CourseSummaryDto> listCourses(String ownerId) {
        return courseRepository.findByOwnerIdOrdered(ownerId).stream()
                .map(c -> new CourseSummaryDto(
                        c.getId(),
                        c.getTitle(),
                        c.getStatus(),
                        c.getCurrentStep(),
                        c.getCreatedAt(),
                        c.getUpdatedAt()
                )).toList();
    }

    /** A course with its draft state (settings) and template, in the same shape as a session detail. */
    public Optional<SessionDetailDto> getCourse(String id) {
        return courseRepository.findById(id).map(course -> new SessionDetailDto(
                course.getId(),
                course.getTitle(),
                course.getStatus(),
                course.getCurrentStep(),
                null, // courseId
                course.getDraftStateJson(),
                course.getTemplateId() != null, // hasTemplate
                course.getTemplateId() != null ? "COURSE" : null,
                templateFileName(course.getTemplateId()),
                null // session
        ));
    }

    /** @return false if the course does not exist */
    @Transactional
    public boolean deleteCourse(String id, String ownerId) {
        CourseEntity course = courseRepository.findById(id).orElse(null);
        if (course == null) return false;
        if (!course.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("Not authorized");
        }
        // Collect templates owned by the course and its child sessions (child sessions are removed by
        // ON DELETE CASCADE, but their slide_templates rows would otherwise be orphaned).
        List<String> templateIds = new ArrayList<>();
        if (id.equals(course.getTemplateId())) templateIds.add(id);
        for (WorkshopSessionEntity child : repo.findAllByCourseIdOrdered(id)) {
            if (child.getId().equals(child.getTemplateId())) templateIds.add(child.getId());
        }
        courseRepository.delete(course);
        courseRepository.flush();
        templateRepo.deleteAllById(templateIds);
        return true;
    }

    /** Rename a course, keeping the title inside its draft state in sync so later draft saves don't revert it. */
    @Transactional
    public boolean renameCourse(String id, String newTitle, String ownerId) {
        UsageGuard.checkTitle(newTitle);
        CourseEntity course = courseRepository.findById(id).orElse(null);
        if (course == null) return false;
        if (!course.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("Not authorized");
        }
        course.setTitle(newTitle);
        if (course.getDraftStateJson() != null && !course.getDraftStateJson().isBlank()) {
            try {
                JsonNode root = mapper.readTree(course.getDraftStateJson());
                if (root.isObject()) {
                    for (String key : List.of("workshopInput", "session")) {
                        if (root.has(key) && root.get(key).isObject()) {
                            ((ObjectNode) root.get(key)).put("title", newTitle);
                        }
                    }
                    course.setDraftStateJson(mapper.writeValueAsString(root));
                }
            } catch (Exception e) {
                log.warn("Failed to update title in draftStateJson for course {}", id);
            }
        }
        courseRepository.save(course);
        return true;
    }

}
