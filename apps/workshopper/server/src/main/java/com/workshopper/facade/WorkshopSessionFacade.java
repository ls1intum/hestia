package com.workshopper.facade;

import com.workshopper.dto.*;
import com.workshopper.usecase.*;
import com.workshopper.repository.WorkshopSessionRepository;
import com.workshopper.repository.CourseRepository;
import com.workshopper.repository.SlideTemplateRepository;
import com.workshopper.model.CourseEntity;
import com.workshopper.model.WorkshopSessionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.workshopper.config.AuthContext;
import com.workshopper.exception.SessionLockedException;
import com.workshopper.model.SlideTemplateEntity;
import com.workshopper.service.PdfExportService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkshopSessionFacade {

    private final GenerateTimetableUseCase generateTimetableUseCase;
    private final RefineLearningGoalUseCase refineLearningGoalUseCase;
    private final FixGoalsGrammarUseCase fixGoalsGrammarUseCase;
    private final GenerateSlideBlockUseCase generateSlideBlockUseCase;
    private final AssemblePptxUseCase assemblePptxUseCase;
    
    private final WorkshopSessionRepository repo;
    private final CourseRepository courseRepo;
    private final SlideTemplateRepository slideTemplateRepo;
    private final ObjectMapper mapper;
    private final PdfExportService pdfService;
    
    // In-flight lock for idempotency: mapping sessionId to a lock
    private final ConcurrentHashMap<String, Object> inFlightLocks = new ConcurrentHashMap<>();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkshopSessionFacade.class);

    public WorkshopSessionFacade(
            GenerateTimetableUseCase generateTimetableUseCase,
            RefineLearningGoalUseCase refineLearningGoalUseCase,
            FixGoalsGrammarUseCase fixGoalsGrammarUseCase,
            GenerateSlideBlockUseCase generateSlideBlockUseCase,
            AssemblePptxUseCase assemblePptxUseCase,
            WorkshopSessionRepository repo,
            CourseRepository courseRepo,
            SlideTemplateRepository slideTemplateRepo, 
            ObjectMapper mapper,
            PdfExportService pdfService) {
        this.generateTimetableUseCase = generateTimetableUseCase;
        this.refineLearningGoalUseCase = refineLearningGoalUseCase;
        this.fixGoalsGrammarUseCase = fixGoalsGrammarUseCase;
        this.generateSlideBlockUseCase = generateSlideBlockUseCase;
        this.assemblePptxUseCase = assemblePptxUseCase;
        this.repo = repo;
        this.courseRepo = courseRepo;
        this.slideTemplateRepo = slideTemplateRepo;
        this.mapper = mapper;
        this.pdfService = pdfService;
    }

    /**
     * Entry point for generation.
     * Intentionally NOT @Transactional to avoid holding a DB connection during slow LLM calls.
     */
    public WorkshopSessionDto generateAndSaveSession(GenerateSessionRequestDto request) throws Exception {
        String sessionId = request.skeleton() != null ? request.skeleton().sessionId() : null;
        
        // Authz check: In a real app, verify ownership here!
        if (sessionId != null) {
            verifyOwnership(sessionId);
        }
        
        // Idempotency / In-flight lock guard
        String lockKey = sessionId != null ? sessionId : "NEW_SESSION_LOCK_" + UUID.randomUUID().toString();
        Object lock = new Object();
        Object existingLock = inFlightLocks.putIfAbsent(lockKey, lock);
        
        if (existingLock != null) {
            throw new SessionLockedException("Session generation is already in progress for this session.");
        }
        
        try {
            // 1. LLM Generation (slow, network-bound, OUTSIDE transaction)
            WorkshopSessionDto generatedDto = generateTimetableUseCase.execute(
                request.meta(), 
                request.skeleton(), 
                request.goals(),
                request.availableMaterials()
            );
            
            // 2. Fast DB Persist (INSIDE short transaction scope)
            return persistGeneratedSession(generatedDto, sessionId);
            
        } finally {
            inFlightLocks.remove(lockKey);
        }
    }
    
    public void verifyOwnership(String id) {
        WorkshopSessionEntity entity = repo.findById(id).orElse(null);
        if (entity != null) {
            checkOwner(entity.getOwnerId(), id);
            return;
        }
        CourseEntity course = courseRepo.findById(id).orElse(null);
        if (course != null) {
            checkOwner(course.getOwnerId(), id);
        }
    }

    /**
     * Strict check for IDs used as a course reference (courseId on drafts/moves, course template).
     * Unlike verifyOwnership, an unknown ID (or a session ID) is rejected, because it would
     * otherwise be written into workshop_sessions.course_id and violate the FK.
     */
    public void verifyCourseOwnership(String courseId) {
        CourseEntity course = courseRepo.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Course not found: " + courseId));
        checkOwner(course.getOwnerId(), courseId);
    }

    private void checkOwner(String ownerId, String resourceId) {
        String currentUser = AuthContext.getCurrentUserId();
        if (ownerId != null && !ownerId.equals(currentUser)) {
            throw new AccessDeniedException(
                "User " + currentUser + " is not allowed to access resource " + resourceId);
        }
    }

    // Note: no @Transactional here — this is a self-invocation from generateAndSaveSession, so the
    // annotation would be ignored by the Spring proxy anyway. repo.save() is transactional on its own.
    protected WorkshopSessionDto persistGeneratedSession(WorkshopSessionDto session, String draftId) throws Exception {
        String json = mapper.writeValueAsString(session);
        
        WorkshopSessionEntity entity = null;
        if (draftId != null && !draftId.isBlank()) {
            entity = repo.findById(draftId).orElse(null);
        }
        if (entity == null) {
            entity = new WorkshopSessionEntity();
            entity.setOwnerId(AuthContext.getCurrentUserId());
        }
        
        entity.setTitle(session.title());
        entity.setStudentBackground(session.studentBackground());
        entity.setSessionJson(json);
        entity.setStatus("complete");
        entity.setCurrentStep("result");
        
        WorkshopSessionEntity saved = repo.save(entity);
        
        return new WorkshopSessionDto(
                saved.getId(),
                session.title(),
                session.studentBackground(),
                session.blocks(),
                session.slides()
        );
    }

    public List<GoalSuggestionDto> refineGoal(RefineGoalRequestDto request) throws Exception {
        return refineLearningGoalUseCase.execute(request);
    }

    public List<String> fixGoalsGrammar(List<String> goals) throws Exception {
        return fixGoalsGrammarUseCase.execute(goals);
    }

    public List<Map<String, Object>> generateBlockSlides(ActivityBlockDto block, WorkshopInputDto meta, List<LearningGoalPlanDto> goals) throws Exception {
        return generateSlideBlockUseCase.execute(block, meta, goals);
    }

    public byte[] assemblePptx(WorkshopSessionDto session, WorkshopInputDto meta, List<Map<String, Object>> prebuiltSlides, InputStream templateStream, boolean isCustomTemplate) throws Exception {
        return assemblePptxUseCase.execute(session, meta, prebuiltSlides, templateStream, isCustomTemplate);
    }

    public byte[] exportCourseZip(String courseId) throws Exception {
        String courseTemplateId = courseRepo.findById(courseId)
                .map(CourseEntity::getTemplateId).orElse(null);
        List<WorkshopSessionEntity> children = repo.findAllByCourseIdOrdered(courseId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(baos);

        int counter = 1;
        for (WorkshopSessionEntity e : children) {
            if (!"complete".equals(e.getStatus()) || e.getDraftStateJson() == null)
                continue;
            try {
                JsonNode root = mapper.readTree(e.getDraftStateJson());
                WorkshopSessionDto session = null;
                if (root.has("session") && !root.get("session").isNull()) {
                    session = mapper.treeToValue(root.get("session"), WorkshopSessionDto.class);
                }
                WorkshopInputDto meta = null;
                if (root.has("workshopInput") && !root.get("workshopInput").isNull()) {
                    meta = mapper.treeToValue(root.get("workshopInput"), WorkshopInputDto.class);
                }

                if (session != null) {
                    Map<Integer, List<Map<String, Object>>> finalSlides = session.slides();
                    if ((finalSlides == null || finalSlides.isEmpty()) && e.getSlidesJson() != null && !e.getSlidesJson().isBlank()) {
                        finalSlides = mapper.readValue(
                                e.getSlidesJson(),
                                new TypeReference<Map<Integer, List<Map<String, Object>>>>() {}
                        );
                    }
                    
                    session = new WorkshopSessionDto(
                            session.id(),
                            session.title() == null && e.getTitle() != null ? e.getTitle() : session.title(),
                            session.studentBackground(),
                            session.blocks(),
                            finalSlides
                    );
                }

                if (session != null) {
                    PdfExportRequestDto requestDto = new PdfExportRequestDto(
                            session, meta, List.of());
                    String safeTitle = (session.title() != null ? session.title() : "Session")
                            .replaceAll("[^a-zA-Z0-9.-]", "_");
                    safeTitle = String.format("%02d_%s", counter, safeTitle);

                    byte[] pdfBytes = pdfService.exportToPdf(requestDto);
                    zos.putNextEntry(new ZipEntry(safeTitle + "/timetable.pdf"));
                    zos.write(pdfBytes);
                    zos.closeEntry();

                    String effectiveTemplateId = e.getTemplateId() != null ? e.getTemplateId() : courseTemplateId;
                    byte[] templateData = effectiveTemplateId == null ? null
                            : slideTemplateRepo.findById(effectiveTemplateId)
                                    .map(SlideTemplateEntity::getFileData).orElse(null);
                    InputStream templateStream;
                    if (templateData != null) {
                        templateStream = new ByteArrayInputStream(templateData);
                    } else {
                        templateStream = new ClassPathResource("templates/workshopper-default.pptx").getInputStream();
                    }
                    byte[] pptxBytes = assemblePptxUseCase.execute(requestDto.session(), requestDto.meta(), null, templateStream, templateData != null);
                    zos.putNextEntry(new ZipEntry(safeTitle + "/slides.pptx"));
                    zos.write(pptxBytes);
                    zos.closeEntry();

                    counter++;
                }
            } catch (Exception ex) {
                log.warn("Failed to export child session {} in course {}", e.getId(), courseId, ex);
            }
        }
        zos.close();
        return baos.toByteArray();
    }
}
