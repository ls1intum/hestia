package com.workshopper.facade;

import com.workshopper.dto.*;
import com.workshopper.usecase.GenerateTimetableUseCase;
import com.workshopper.repository.WorkshopSessionRepository;
import com.workshopper.model.WorkshopSessionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class WorkshopSessionFacade {

    private final GenerateTimetableUseCase generateTimetableUseCase;
    private final WorkshopSessionRepository repo;
    private final ObjectMapper mapper;
    
    // In-flight lock for idempotency: mapping sessionId to a lock
    private final java.util.concurrent.ConcurrentHashMap<String, Object> inFlightLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public WorkshopSessionFacade(GenerateTimetableUseCase generateTimetableUseCase, WorkshopSessionRepository repo, ObjectMapper mapper) {
        this.generateTimetableUseCase = generateTimetableUseCase;
        this.repo = repo;
        this.mapper = mapper;
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
                request.goals()
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
}
