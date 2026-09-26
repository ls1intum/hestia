package com.workshopper.facade;

import com.workshopper.dto.*;
import com.workshopper.usecase.*;
import com.workshopper.repository.WorkshopSessionRepository;
import com.workshopper.model.WorkshopSessionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class WorkshopSessionFacade {

    private final GenerateTimetableUseCase generateTimetableUseCase;
    private final GenerateLearningGoalsUseCase generateLearningGoalsUseCase;
    private final RefineLearningGoalUseCase refineLearningGoalUseCase;
    private final ExtractGoalsUseCase extractGoalsUseCase;
    private final FixGoalsGrammarUseCase fixGoalsGrammarUseCase;
    private final GenerateSlideBlockUseCase generateSlideBlockUseCase;
    private final AssemblePptxUseCase assemblePptxUseCase;
    
    private final WorkshopSessionRepository repo;
    private final ObjectMapper mapper;
    private final com.workshopper.service.PdfExportService pdfService;
    
    // In-flight lock for idempotency: mapping sessionId to a lock
    private final java.util.concurrent.ConcurrentHashMap<String, Object> inFlightLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkshopSessionFacade.class);

    public WorkshopSessionFacade(
            GenerateTimetableUseCase generateTimetableUseCase,
            GenerateLearningGoalsUseCase generateLearningGoalsUseCase,
            RefineLearningGoalUseCase refineLearningGoalUseCase,
            ExtractGoalsUseCase extractGoalsUseCase,
            FixGoalsGrammarUseCase fixGoalsGrammarUseCase,
            GenerateSlideBlockUseCase generateSlideBlockUseCase,
            AssemblePptxUseCase assemblePptxUseCase,
            WorkshopSessionRepository repo, 
            ObjectMapper mapper,
            com.workshopper.service.PdfExportService pdfService) {
        this.generateTimetableUseCase = generateTimetableUseCase;
        this.generateLearningGoalsUseCase = generateLearningGoalsUseCase;
        this.refineLearningGoalUseCase = refineLearningGoalUseCase;
        this.extractGoalsUseCase = extractGoalsUseCase;
        this.fixGoalsGrammarUseCase = fixGoalsGrammarUseCase;
        this.generateSlideBlockUseCase = generateSlideBlockUseCase;
        this.assemblePptxUseCase = assemblePptxUseCase;
        this.repo = repo;
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
        String lockKey = sessionId != null ? sessionId : "NEW_SESSION_LOCK_" + java.util.UUID.randomUUID().toString();
        Object lock = new Object();
        Object existingLock = inFlightLocks.putIfAbsent(lockKey, lock);
        
        if (existingLock != null) {
            throw new com.workshopper.exception.SessionLockedException("Session generation is already in progress for this session.");
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
    
    public void verifyOwnership(String sessionId) {
        WorkshopSessionEntity entity = repo.findById(sessionId).orElse(null);
        if (entity != null) {
            String currentUser = com.workshopper.config.AuthContext.getCurrentUserId();
            if (entity.getOwnerId() != null && !entity.getOwnerId().equals(currentUser)) {
                throw new org.springframework.security.access.AccessDeniedException("User " + currentUser + " is not allowed to modify session " + sessionId);
            }
        }
    }

    @Transactional
    protected WorkshopSessionDto persistGeneratedSession(WorkshopSessionDto session, String draftId) throws Exception {
        String json = mapper.writeValueAsString(session);
        
        WorkshopSessionEntity entity = null;
        if (draftId != null && !draftId.isBlank()) {
            entity = repo.findById(draftId).orElse(null);
        }
        if (entity == null) {
            entity = new WorkshopSessionEntity();
            entity.setOwnerId(com.workshopper.config.AuthContext.getCurrentUserId());
        }
        
        entity.setTitle(session.title());
        entity.setLearningGoal(session.learningGoal());
        entity.setStudentBackground(session.studentBackground());
        entity.setPrerequisites(session.prerequisites());
        entity.setSessionJson(json);
        entity.setStatus("complete");
        entity.setCurrentStep("result");
        
        WorkshopSessionEntity saved = repo.save(entity);
        
        return new WorkshopSessionDto(
                saved.getId(),
                session.title(),
                session.learningGoal(),
                session.studentBackground(),
                session.prerequisites(),
                session.blocks(),
                session.omittedGoals(),
                session.slides()
        );
    }

    public List<LearningGoalPlanDto> generatePlan(WorkshopInputDto input) throws Exception {
        return generateLearningGoalsUseCase.execute(input);
    }

    public List<GoalSuggestionDto> refineGoal(RefineGoalRequestDto request) throws Exception {
        return refineLearningGoalUseCase.execute(request);
    }

    public List<String> extractGoalsFromDocument(ExtractGoalsRequestDto request) throws Exception {
        return extractGoalsUseCase.execute(request);
    }

    public List<String> fixGoalsGrammar(List<String> goals) throws Exception {
        return fixGoalsGrammarUseCase.execute(goals);
    }

    public List<Map<String, Object>> generateBlockSlides(com.workshopper.dto.WorkshopBlockDto block, WorkshopInputDto meta, List<String> goals) throws Exception {
        return generateSlideBlockUseCase.execute(block, meta, goals);
    }

    public byte[] assemblePptx(WorkshopSessionDto session, WorkshopInputDto meta, List<Map<String, Object>> prebuiltSlides, java.io.InputStream templateStream) throws Exception {
        return assemblePptxUseCase.execute(session, meta, prebuiltSlides, templateStream);
    }

    public List<String> renderAllSlidePreviews(WorkshopSessionDto session, WorkshopInputDto meta, List<Map<String, Object>> prebuiltSlides, java.io.InputStream templateStream) throws Exception {
        return assemblePptxUseCase.renderAllSlidePreviews(session, meta, prebuiltSlides, templateStream);
    }

    public byte[] exportLectureZip(String lectureId) throws Exception {
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

                if (session != null && session.title() == null && e.getTitle() != null) {
                    session = new com.workshopper.dto.WorkshopSessionDto(
                            session.id(), e.getTitle(), session.learningGoal(), session.studentBackground(),
                            session.prerequisites(), session.blocks(), session.omittedGoals(), session.slides());
                }

                if (session != null) {
                    com.workshopper.dto.PdfExportRequestDto requestDto = new com.workshopper.dto.PdfExportRequestDto(
                            session, meta, java.util.List.of());
                    String safeTitle = (session.title() != null ? session.title() : "Session_" + counter)
                            .replaceAll("[^a-zA-Z0-9.-]", "_");

                    byte[] pdfBytes = pdfService.exportToPdf(requestDto);
                    zos.putNextEntry(new java.util.zip.ZipEntry(safeTitle + "/timetable.pdf"));
                    zos.write(pdfBytes);
                    zos.closeEntry();

                    byte[] templateData = e.getTemplateData();
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
}
