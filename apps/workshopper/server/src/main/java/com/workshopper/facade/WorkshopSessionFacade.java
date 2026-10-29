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
import com.workshopper.util.FileNames;
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
    private final UsageGuard usageGuard;
    
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
            PdfExportService pdfService,
            UsageGuard usageGuard) {
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
        this.usageGuard = usageGuard;
    }

    /**
     * Entry point for generation.
     * Intentionally NOT @Transactional to avoid holding a DB connection during slow LLM calls.
     */
    public WorkshopSessionDto generateAndSaveSession(GenerateSessionRequestDto request) throws Exception {
        String sessionId = request.skeleton() != null ? request.skeleton().sessionId() : null;
        
        if (sessionId != null) {
            verifyOwnership(sessionId);
        }
        usageGuard.checkGeneration(request);
        
        // Idempotency / In-flight lock guard
        String lockKey = sessionId != null ? sessionId : "NEW_SESSION_LOCK_" + UUID.randomUUID().toString();
        Object lock = new Object();
        Object existingLock = inFlightLocks.putIfAbsent(lockKey, lock);
        
        if (existingLock != null) {
            throw new SessionLockedException("Session generation is already in progress for this session.");
        }
        
        try {
            // 1. LLM Generation (slow, network-bound, OUTSIDE transaction)
            WorkshopSessionDto generatedDto = usageGuard.runAi(() -> generateTimetableUseCase.execute(
                request.meta(),
                request.skeleton(),
                request.goals(),
                request.availableMaterials()
            ));
            
            // A single block regenerated for an existing session (switching or adding an activity)
            // is not a timetable: the client merges it into its blocks and saves the whole timetable
            // as a draft. Saving it here would replace the session's timetable with one block.
            if (isSingleBlockRegeneration(request, sessionId)) {
                return generatedDto;
            }

            // 2. Fast DB Persist (INSIDE short transaction scope)
            return persistGeneratedSession(generatedDto, sessionId);
            
        } finally {
            inFlightLocks.remove(lockKey);
        }
    }
    
    static boolean isSingleBlockRegeneration(GenerateSessionRequestDto request, String sessionId) {
        return sessionId != null && request.skeleton() != null
                && request.skeleton().blocks() != null && request.skeleton().blocks().size() == 1;
    }

    /**
     * Checks that the session (or course) with this id belongs to the current user: 403 if it
     * belongs to someone else, 404 if it doesn't exist (e.g. deleted in another tab), so saves
     * never quietly create a new row in its place.
     */
    public void verifyOwnership(String id) {
        WorkshopSessionEntity entity = repo.findById(id).orElse(null);
        if (entity != null) {
            checkOwner(entity.getOwnerId(), id);
            return;
        }
        CourseEntity course = courseRepo.findById(id).orElse(null);
        if (course != null) {
            checkOwner(course.getOwnerId(), id);
            return;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This session no longer exists. It may have been deleted.");
    }

    /**
     * Strict check for IDs used as a course reference (courseId on drafts/moves, course template):
     * unlike verifyOwnership, a session ID is rejected too, because it would otherwise be written
     * into workshop_sessions.course_id and violate the FK.
     */
    public void verifyCourseOwnership(String courseId) {
        CourseEntity course = courseRepo.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This course no longer exists. It may have been deleted."));
        checkOwner(course.getOwnerId(), courseId);
    }

    private void checkOwner(String ownerId, String resourceId) {
        String currentUser = AuthContext.getCurrentUserId();
        // A row without an owner belongs to nobody, so nobody may touch it
        if (ownerId == null || !ownerId.equals(currentUser)) {
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
            UsageGuard.checkSessionCount(repo.countByOwnerId(AuthContext.getCurrentUserId()));
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
        usageGuard.checkGoalCheck(request);
        return usageGuard.runAi(() -> refineLearningGoalUseCase.execute(request));
    }

    public List<String> fixGoalsGrammar(List<String> goals) throws Exception {
        usageGuard.checkGoalTexts(goals);
        return usageGuard.runAi(() -> fixGoalsGrammarUseCase.execute(goals));
    }

    public List<Map<String, Object>> generateBlockSlides(ActivityBlockDto block, WorkshopInputDto meta, List<LearningGoalPlanDto> goals) throws Exception {
        usageGuard.checkBlockSlides(block, meta, goals);
        return usageGuard.runAi(() -> generateSlideBlockUseCase.execute(block, meta, goals));
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
                    // The dashboard title is the session's name; the one inside the timetable may be older
                    String title = e.getTitle() != null ? e.getTitle() : session.title();
                    String folder = String.format("%02d - %s", counter, FileNames.safe(title, "Session"));

                    byte[] pdfBytes = pdfService.exportToPdf(requestDto);
                    zos.putNextEntry(new ZipEntry(folder + "/" + FileNames.timetable(title)));
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
                    zos.putNextEntry(new ZipEntry(folder + "/" + FileNames.slides(title)));
                    zos.write(pptxBytes);
                    zos.closeEntry();

                    counter++;
                }
            } catch (Exception ex) {
                log.warn("Failed to export child session {} in course {}", e.getId(), courseId, ex);
            }
        }
        zos.close();
        if (counter == 1) {
            // Nothing to put in it: say so instead of downloading an empty archive
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This course has no sessions ready to export yet. Generate a session's timetable first.");
        }
        return baos.toByteArray();
    }
}
