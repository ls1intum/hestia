package de.tum.cit.hestia.teaching.service;

import de.tum.cit.hestia.teaching.model.TeachingPlanEntity;
import org.springframework.transaction.annotation.Transactional;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.teaching.dto.*;
import de.tum.cit.hestia.teaching.model.WorkshopSessionEntity;
import de.tum.cit.hestia.teaching.repository.WorkshopSessionRepository;
import de.tum.cit.hestia.teaching.repository.SlideTemplateRepository;
import de.tum.cit.hestia.teaching.model.SlideTemplateEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import de.tum.cit.hestia.teaching.facade.UsageGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.tum.cit.hestia.teaching.repository.TeachingPlanRepository;
import de.tum.cit.hestia.core.CourseInfo;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Map;
import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CourseRole;
import de.tum.cit.hestia.core.CurrentUser;
import java.util.UUID;

@Service
public class WorkshopService {

    private final TeachingPlanRepository plans;
    private final SessionGoals goals;

    private static final Logger log = LoggerFactory.getLogger(WorkshopService.class);

    private final WorkshopSessionRepository repo;
    private final SlideTemplateRepository templateRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    private final CourseApi courseApi;
    private final CourseAccess courseAccess;
    private final CurrentUser currentUser;

    public WorkshopService(WorkshopSessionRepository repo, TeachingPlanRepository plans,
                           SlideTemplateRepository templateRepo, CourseApi courseApi,
                           CourseAccess courseAccess, CurrentUser currentUser, SessionGoals goals) {
        this.goals = goals;
        this.courseApi = courseApi;
        this.courseAccess = courseAccess;
        this.currentUser = currentUser;
        this.repo = repo;
        this.plans = plans;
        this.templateRepo = templateRepo;
    }

    // ── Draft management ──────────────────────────────────────────────

    /**
     * Upsert a draft session record. If sessionId is provided and found, updates
     * it; otherwise creates a new entity and returns the assigned ID.
     */
    private static final java.util.Set<String> STEPS_WITH_TIMETABLE =
            java.util.Set.of("result", "timeline", "prepare", "final-review", "finished");

    public String saveDraft(SaveDraftRequestDto req) {
        UsageGuard.checkTitle(req.title());
        UsageGuard.checkStoredSize("The session", req.draftStateJson(), UsageGuard.MAX_DRAFT_CHARS);
        WorkshopSessionEntity entity = null;
        if (req.sessionId() != null && !req.sessionId().isBlank()) {
            // Deleted in the meantime (e.g. in another tab): say so instead of re-creating it
            entity = repo.findById(req.sessionId()).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "This session no longer exists. It may have been deleted."));
        }
        if (entity == null) {
            UsageGuard.checkSessionCount(repo.countByCreatedBy(currentUser.userId()));
            entity = new WorkshopSessionEntity();
            entity.setCreatedBy(currentUser.userId());
            // Without a chosen course a session goes into the user's personal course
            entity.setCourseId(req.courseId() != null && !req.courseId().isBlank()
                    ? uuid(req.courseId()) : courseApi.personalCourse().id());
        }
        // An existing session keeps its course here; moving it is a separate action (moveSession)
        String draftJson = req.draftStateJson() == null ? null
                : goals.toStored(req.draftStateJson(), entity.getCourseId());
        List<UUID> planGoalIds = draftJson == null ? null : goals.planGoalIds(draftJson);
        if (req.title() != null)
            entity.setTitle(req.title());
        if (req.currentStep() != null)
            entity.setCurrentStep(req.currentStep());
        if (draftJson != null) {
            entity.setDraftStateJson(draftJson);
            try {
                JsonNode rootNode = mapper.readTree(draftJson);
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

        // A session is complete once it has a generated timetable, on whichever step from the
        // timetable on it is saved (editing the timetable later must not hide it from the exports);
        // before that, or without a timetable, it is a draft.
        if (req.currentStep() != null) {
            boolean fromTimetableOn = STEPS_WITH_TIMETABLE.contains(req.currentStep());
            entity.setStatus(fromTimetableOn && entity.getSessionJson() != null ? "complete" : "draft");
        }
        String id = repo.save(entity).getId();
        // One alignment link per goal of the plan
        if (planGoalIds != null) goals.syncLinks(id, planGoalIds);
        return id;
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
        template.setOwnerId(currentUser.userId());
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
        if (entity.isUseDefaultTemplate()) return null;
        return plans.findById(entity.getCourseId()).map(TeachingPlanEntity::getTemplateId).orElse(null);
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
        TeachingPlanEntity plan = planForWrite(uuid(courseId));
        SlideTemplateEntity template = new SlideTemplateEntity();
        template.setId(courseId);
        template.setOwnerId(currentUser.userId());
        template.setFileName(fileName);
        template.setFileData(templateData);
        templateRepo.save(template);
        plan.setTemplateId(courseId);
        plans.save(plan);
    }

    /** Removes a course's template; its sessions fall back to the default design (or their own template). */
    @Transactional
    public void deleteCourseTemplate(String courseId) {
        TeachingPlanEntity plan = plans.findById(uuid(courseId)).orElse(null);
        if (plan != null && plan.getTemplateId() != null) {
            String templateId = plan.getTemplateId();
            plan.setTemplateId(null);
            plans.save(plan);
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
                    e.getCourseId().toString(),
                    courseApi.get(e.getCourseId()).personal(),
                    e.getDraftStateJson() == null ? null : goals.toResolved(e.getDraftStateJson(), e.getId()),
                    templateId != null,
                    templateSource,
                    templateFileName(templateId),
                    session);
        });
    }

    /** Lightweight list for the dashboard (no blocks payload). */
    public List<SessionSummaryDto> listSessions() {
        List<UUID> courses = courseApi.idsMine(CourseRole.VIEWER);
        if (courses.isEmpty()) return List.of();
        Set<UUID> personal = courseApi.listMine(0, 1000).stream()
                .filter(CourseInfo::personal).map(CourseInfo::id).collect(Collectors.toSet());
        List<WorkshopSessionEntity> visible = repo.findVisible(courses);
        Map<String, Integer> reviews = goals.openReviews(visible.stream().map(WorkshopSessionEntity::getId).toList());
        return visible.stream()
                .map(e -> new SessionSummaryDto(
                        e.getId(),
                        e.getTitle() != null ? e.getTitle() : "Workshop Session",
                        e.getStatus(),
                        e.getCurrentStep(),
                        e.getCourseId().toString(),
                        personal.contains(e.getCourseId()),
                        e.getCreatedAt(),
                        e.getUpdatedAt(),
                        reviews.getOrDefault(e.getId(), 0)))
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
        goals.unlinkSession(id);
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

    /**
     * Moves a session to another course (the caller checked EDITOR on both). Its alignment links to
     * goals of the old course are removed, and so are its plan entries for those goals; the session
     * inherits the template of the new course.
     */
    @Transactional
    public void moveSession(String id, UUID courseId) {
        repo.findById(id).ifPresent(entity -> {
            UUID oldCourseId = entity.getCourseId();
            if (oldCourseId.equals(courseId)) return;
            goals.unlinkSessionInCourse(id, oldCourseId);
            if (entity.getDraftStateJson() != null) {
                entity.setDraftStateJson(goals.withoutGoalsOfCourse(entity.getDraftStateJson(), oldCourseId));
            }
            entity.setUseDefaultTemplate(false); // a session moved to another course inherits its template
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

    /** A course id from a path or body; anything that is no UUID is an unknown course (404). */
    public static UUID uuid(String courseId) {
        try {
            return UUID.fromString(courseId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This course no longer exists. It may have been deleted.");
        }
    }

    /** The Workshopper data of a course; created on first write. */
    private TeachingPlanEntity planForWrite(UUID courseId) {
        return plans.findById(courseId).orElseGet(() -> plans.save(new TeachingPlanEntity(courseId)));
    }

    @Transactional
    public String saveCourseDraft(SaveDraftRequestDto request) {
        UsageGuard.checkTitle(request.title());
        UsageGuard.checkStoredSize("The course", request.draftStateJson(), UsageGuard.MAX_DRAFT_CHARS);
        UUID courseId;
        TeachingPlanEntity plan;
        String title = request.title() != null && !request.title().isBlank() ? request.title() : null;
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            courseId = uuid(request.sessionId());
            courseAccess.requireWrite(courseId);
            plan = planForWrite(courseId);
            if (title != null) courseApi.rename(courseId, title);
        } else {
            UsageGuard.checkCourseCount(courseApi.idsMine(CourseRole.OWNER).size());
            // The creator becomes the OWNER of the core course
            courseId = courseApi.create(title != null ? title : "Workshop Course").id();
            plan = new TeachingPlanEntity(courseId);
            plan.setStatus("draft");
        }

        // Only overwrite fields that were actually sent, so a partial save can't wipe the stored state.
        if (request.currentStep() != null) {
            plan.setCurrentStep(request.currentStep());
        }
        if (request.draftStateJson() != null) {
            plan.setDraftStateJson(request.draftStateJson());
        }
        return plans.save(plan).getCourseId().toString();
    }

    /** The user's courses for the dashboard, with their Workshopper data where there is some. */
    public List<CourseSummaryDto> listCourses() {
        List<CourseInfo> infos = courseApi.listMine(0, 1000);
        if (infos.isEmpty()) return List.of();
        Map<UUID, TeachingPlanEntity> byCourse = new HashMap<>();
        plans.findAllById(infos.stream().map(CourseInfo::id).toList()).forEach(p -> byCourse.put(p.getCourseId(), p));
        return infos.stream()
                .sorted(Comparator
                        .comparing((CourseInfo c) -> byCourse.containsKey(c.id()) ? byCourse.get(c.id()).getDisplayOrder() : null,
                                Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                        .thenComparing(CourseInfo::createdAt, Comparator.reverseOrder()))
                .map(c -> {
                    TeachingPlanEntity plan = byCourse.get(c.id());
                    return new CourseSummaryDto(
                            c.id().toString(),
                            c.title(),
                            plan != null ? plan.getStatus() : "complete",
                            plan != null ? plan.getCurrentStep() : null,
                            c.personal(),
                            c.role().name(),
                            plan != null ? plan.getCreatedAt() : null,
                            plan != null ? plan.getUpdatedAt() : null);
                }).toList();
    }

    /** A course with its draft state (settings) and template, in the same shape as a session detail. */
    public Optional<SessionDetailDto> getCourse(String id) {
        UUID courseId = uuid(id);
        CourseInfo info = courseApi.get(courseId);
        TeachingPlanEntity plan = plans.findById(courseId).orElse(null);
        String templateId = plan != null ? plan.getTemplateId() : null;
        return Optional.of(new SessionDetailDto(
                id,
                info.title(),
                plan != null ? plan.getStatus() : "complete",
                plan != null ? plan.getCurrentStep() : null,
                null, // courseId
                info.personal(),
                plan != null ? plan.getDraftStateJson() : null,
                templateId != null, // hasTemplate
                templateId != null ? "COURSE" : null,
                templateFileName(templateId),
                null // session
        ));
    }

    /** Deletes the course with its sessions (OWNER; the personal course cannot be deleted). */
    @Transactional
    public boolean deleteCourse(String id) {
        UUID courseId = uuid(id);
        courseAccess.requireOwner(courseId);
        if (courseApi.get(courseId).personal()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The personal course cannot be deleted.");
        }
        // Collect templates owned by the course and its child sessions (child sessions are removed by
        // ON DELETE CASCADE, but their slide_templates rows would otherwise be orphaned).
        List<String> templateIds = new ArrayList<>();
        if (plans.findById(courseId).map(p -> id.equals(p.getTemplateId())).orElse(false)) templateIds.add(id);
        for (WorkshopSessionEntity child : repo.findAllByCourseIdOrdered(courseId)) {
            if (child.getId().equals(child.getTemplateId())) templateIds.add(child.getId());
        }
        // The core course takes its sessions, plan and alignment links with it
        courseApi.delete(courseId);
        repo.flush();
        templateRepo.deleteAllById(templateIds);
        return true;
    }

    /** Rename a course, keeping the title inside its draft state in sync so later draft saves don't revert it. */
    @Transactional
    public boolean renameCourse(String id, String newTitle) {
        UsageGuard.checkTitle(newTitle);
        UUID courseId = uuid(id);
        courseAccess.requireWrite(courseId);
        courseApi.rename(courseId, newTitle);
        TeachingPlanEntity plan = plans.findById(courseId).orElse(null);
        if (plan != null && plan.getDraftStateJson() != null && !plan.getDraftStateJson().isBlank()) {
            try {
                JsonNode root = mapper.readTree(plan.getDraftStateJson());
                if (root.isObject()) {
                    for (String key : List.of("workshopInput", "session")) {
                        if (root.has(key) && root.get(key).isObject()) {
                            ((ObjectNode) root.get(key)).put("title", newTitle);
                        }
                    }
                    plan.setDraftStateJson(mapper.writeValueAsString(root));
                    plans.save(plan);
                }
            } catch (Exception e) {
                log.warn("Failed to update title in draftStateJson for course {}", id);
            }
        }
        return true;
    }

}
