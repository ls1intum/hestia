package de.tum.cit.hestia.teaching.facade;

import de.tum.cit.hestia.teaching.dto.*;
import de.tum.cit.hestia.teaching.usecase.*;
import de.tum.cit.hestia.teaching.repository.WorkshopSessionRepository;
import de.tum.cit.hestia.teaching.repository.TeachingPlanRepository;
import de.tum.cit.hestia.teaching.repository.SlideTemplateRepository;
import de.tum.cit.hestia.teaching.model.TeachingPlanEntity;
import de.tum.cit.hestia.teaching.model.WorkshopSessionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.teaching.exception.SessionLockedException;
import de.tum.cit.hestia.teaching.model.SlideTemplateEntity;
import de.tum.cit.hestia.teaching.service.PdfExportService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import de.tum.cit.hestia.teaching.util.FileNames;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkshopSessionFacade {

    private final GenerateTimetableUseCase generateTimetableUseCase;
    private final RefineLearningGoalUseCase refineLearningGoalUseCase;
    private final FixGoalsGrammarUseCase fixGoalsGrammarUseCase;
    private final GenerateSlideBlockUseCase generateSlideBlockUseCase;
    private final AssemblePptxUseCase assemblePptxUseCase;
    
    private final WorkshopSessionRepository repo;
    private final TeachingPlanRepository plans;
    private final SlideTemplateRepository slideTemplateRepo;
    private final ObjectMapper mapper;
    private final PdfExportService pdfService;
    private final UsageGuard usageGuard;
    private final CourseAccess courseAccess;
    private final CurrentUser currentUser;
    private final CourseApi courseApi;

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
            TeachingPlanRepository plans,
            SlideTemplateRepository slideTemplateRepo, 
            ObjectMapper mapper,
            PdfExportService pdfService,
            UsageGuard usageGuard,
            CourseAccess courseAccess,
            CurrentUser currentUser,
            CourseApi courseApi) {
        this.generateTimetableUseCase = generateTimetableUseCase;
        this.refineLearningGoalUseCase = refineLearningGoalUseCase;
        this.fixGoalsGrammarUseCase = fixGoalsGrammarUseCase;
        this.generateSlideBlockUseCase = generateSlideBlockUseCase;
        this.assemblePptxUseCase = assemblePptxUseCase;
        this.repo = repo;
        this.plans = plans;
        this.slideTemplateRepo = slideTemplateRepo;
        this.mapper = mapper;
        this.pdfService = pdfService;
        this.usageGuard = usageGuard;
        this.courseAccess = courseAccess;
        this.currentUser = currentUser;
        this.courseApi = courseApi;
    }

    /**
     * Entry point for generation.
     * Intentionally NOT @Transactional to avoid holding a DB connection during slow LLM calls.
     */
    public WorkshopSessionDto generateAndSaveSession(GenerateSessionRequestDto request) throws Exception {
        String sessionId = request.skeleton() != null ? request.skeleton().sessionId() : null;
        
        if (sessionId != null) {
            verifyWrite(sessionId);
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

    private static final String GONE = "This session no longer exists. It may have been deleted.";

    /**
     * Checks that the current user may read the session (or course) with this id: 404 if it does not
     * exist or the user has no access to it at all, 403 if a course role is missing. Unknown ids are
     * a 404 too, so saves never quietly create a new row in their place.
     */
    public void verifyRead(String id) {
        verify(id, false);
    }

    /** Like {@link #verifyRead}, for changing: needs EDITOR in the session's course. */
    public void verifyWrite(String id) {
        verify(id, true);
    }

    private void verify(String id, boolean write) {
        WorkshopSessionEntity entity = repo.findById(id).orElse(null);
        if (entity != null) {
            checkCourse(entity.getCourseId(), write);
            return;
        }
        // Not a session: it may be a course (a core course the user can see), otherwise it is gone
        UUID courseId = parseUuid(id);
        if (courseId != null && courseAccess.canRead(courseId)) {
            checkCourse(courseId, write);
            return;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, GONE);
    }

    /** Strict check for ids used as a course reference (courseId on drafts and moves, course template). */
    public void verifyCourseRead(String courseId) {
        checkCourse(course(courseId), false);
    }

    public void verifyCourseWrite(String courseId) {
        checkCourse(course(courseId), true);
    }

    public void verifyCourseOwner(String courseId) {
        courseAccess.requireOwner(course(courseId));
    }

    private UUID course(String courseId) {
        UUID id = parseUuid(courseId);
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This course no longer exists. It may have been deleted.");
        }
        return id;
    }

    private static UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void checkCourse(UUID courseId, boolean write) {
        if (write) courseAccess.requireWrite(courseId);
        else courseAccess.requireRead(courseId);
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
            UsageGuard.checkSessionCount(repo.countByCreatedBy(currentUser.userId()));
            entity = new WorkshopSessionEntity();
            entity.setCreatedBy(currentUser.userId());
            entity.setCourseId(courseApi.personalCourse().id());
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
        UUID course = course(courseId);
        String courseTemplateId = plans.findById(course)
                .map(TeachingPlanEntity::getTemplateId).orElse(null);
        List<WorkshopSessionEntity> children = repo.findAllByCourseIdOrdered(course);

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
