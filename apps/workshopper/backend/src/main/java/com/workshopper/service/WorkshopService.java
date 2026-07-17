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
import java.util.stream.Collectors;

@Service
public class WorkshopService {

    private static final Logger log = LoggerFactory.getLogger(WorkshopService.class);

    private final LlmService llm;
    private final WorkshopSessionRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.ExecutorService llmExecutor = java.util.concurrent.Executors.newFixedThreadPool(20);

    public WorkshopService(LlmService llm, WorkshopSessionRepository repo) {
        this.llm = llm;
        this.repo = repo;
    }

    // ── Step 1: Generate learning goal plans ──────────────────────

    public List<LearningGoalPlanDto> generatePlan(WorkshopInputDto input) throws Exception {
        String systemPrompt = """
                You are an expert learning designer specializing in constructive alignment.
                Your task is to analyse a workshop input and decompose it into concrete, measurable learning goals.
                Always respond with ONLY valid JSON — no prose, no markdown fences.
                """;

        String sessionTypeLabel = resolveSessionType(input.sessionType(), input.sessionTypeOther());
        String userPrompt = buildPlanPrompt(input, sessionTypeLabel);

        log.debug("Generating plan for goals: {}", input.learningGoals());
        String raw = llm.call(systemPrompt, userPrompt);
        String json = llm.extractJsonArray(raw);

        return mapper.readValue(json, new TypeReference<List<LearningGoalPlanDto>>() {
        });
    }

    private String buildPlanPrompt(WorkshopInputDto input, String sessionTypeLabel) {
        var sb = new StringBuilder();
        sb.append("Workshop details:\n");
        sb.append("- Session type: ").append(sessionTypeLabel).append("\n");
        sb.append("- Duration: ").append(input.duration()).append(" minutes\n");
        sb.append("- Number of participants: ").append(input.participants()).append("\n");

        if (input.studentBackground() != null && !input.studentBackground().isBlank()) {
            sb.append("- Student background: ").append(input.studentBackground()).append("\n");
        }
        if (input.learningGoals() != null && !input.learningGoals().isEmpty()) {
            sb.append("- Provided learning goals:\n");
            for (String g : input.learningGoals()) {
                // Strip any "LG1:", "LG 1:", "LG1 -" style prefixes the user may have typed
                String cleaned = g.replaceAll("(?i)^\\s*LG\\s*\\d+\\s*[:.-]\\s*", "").trim();
                sb.append("  * ").append(cleaned).append("\n");
            }
        }
        if (input.sourceDocument() != null && !input.sourceDocument().isBlank()) {
            sb.append("\nSource document (use to extract learning goals):\n");
            String doc = input.sourceDocument();
            if (doc.length() > 8000)
                doc = doc.substring(0, 8000) + "\n[... truncated ...]";
            sb.append(doc).append("\n");
        }

        sb.append("""

                Return a JSON array of learning goal objects. Each object must follow this exact schema:
                [
                  {
                    "id": "g1",
                    "originalGoal": "The exact text of the inputted goal this relates to (if any)",
                    "goal": "Participants will be able to ...",
                    "prerequisites": [],
                    "achieveActivities": [],
                    "assessActivities": [],
                    "priority": 0
                  }
                ]
                Return 2–5 goals. Use verb-object format for goal statements (Bloom's taxonomy verbs preferred).
                Always set priority to 0. Leave achieveActivities and assessActivities empty.
                IMPORTANT: The "goal" field must NOT contain any "LG1:", "LG2:", or similar numbering prefixes. Start directly with "Participants will be able to ...".
                """);

        return sb.toString();
    }

    // ── Real-time goal refinement (Step 3) ───────────────────────────

    public List<GoalSuggestionDto> refineGoal(RefineGoalRequestDto request) throws Exception {
        String systemPrompt = """
                You are an expert learning designer. Analyse a single learning goal and return
                actionable suggestions. Always respond with ONLY a valid JSON array — no prose.
                """;

        var ctx = request.context() != null ? request.context() : java.util.Map.of();
        String sessionType = ctx.getOrDefault("sessionType", "workshop").toString();
        String background = ctx.getOrDefault("studentBackground", "").toString();

        String userPrompt = String.format(
                """
                        Session type: %s
                        Student background: %s

                        Learning goal text to review:
                        "%s"

                        Task:
                        FIRST, check if the text contains TWO OR MORE distinct learning goals in a single entry. This happens when:
                        - The text contains newlines or line breaks between complete thoughts
                        - Multiple complete goal sentences are separated by a period (e.g. "Students will apply X. Students will also evaluate Y")
                        - The word "and" joins ENTIRELY DIFFERENT skills or competencies
                        - A comma separates distinct measurable outcomes
                        In any of these cases, you MUST return a "split" suggestion with ALL distinct goals clearly stated in the "values" array.

                        THEN, if the text is a single goal:
                        1. If the goal contains MULTIPLE distinct competencies in one sentence, suggest splitting it into separate goals.
                        2. If the goal is vague, unmeasurable, or not phrased as an observable outcome, suggest a refined version.
                        3. CRITICAL RULE: There must NEVER be more than one SOLO/Bloom taxonomy verb in a single learning goal. For example, "explain concept A and concept B" is allowed, but "discuss and explain concept A" is strictly forbidden. If a goal uses multiple verbs, split it into separate goals or simplify it to one core verb.
                        4. If the goal is already clear, well-formed, and strictly uses only one core taxonomy verb, return an EMPTY array [].

                        Return a JSON array with at most 2 suggestions. Each suggestion must follow this schema:
                        [
                          {
                            "type": "split" | "refine",
                            "values": ["first goal text", "second goal text", "etc (only for split, always include ALL split goals)"],
                            "message": "Short explanation of why this suggestion is helpful (max 1 sentence)"
                          }
                        ]
                        IMPORTANT: For a "split" suggestion, "values" MUST contain an array of ALL separated goal strings.
                        """,
                sessionType, background, request.goal());

        String raw = llm.call(systemPrompt, userPrompt);
        String json = llm.extractJsonArray(raw);
        return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<GoalSuggestionDto>>() {
        });
    }

    // ── Extract LGs from uploaded document (Step 3 file upload) ─────

    public List<String> extractGoalsFromDocument(ExtractGoalsRequestDto request) throws Exception {
        String systemPrompt = """
                You are an expert learning designer. Extract explicit or implied learning goals from
                a document. Always respond with ONLY a valid JSON array of strings — no prose.
                """;

        var ctx = request.context() != null ? request.context() : java.util.Map.of();
        String sessionType = ctx.getOrDefault("sessionType", "workshop").toString();
        String background = ctx.getOrDefault("studentBackground", "").toString();

        // Truncate document to avoid exceeding token budget
        String doc = request.documentText();
        if (doc != null && doc.length() > 10000)
            doc = doc.substring(0, 10000) + "\n[... truncated ...]";

        String userPrompt = String.format(
                """
                        Session type: %s
                        Student background: %s

                        Document text:
                        ---
                        %s
                        ---

                        Task:
                        Extract all distinct learning goals or intended learning outcomes from this document.
                        - Include goals stated explicitly (e.g. "After this session, students will be able to...")
                        - Include goals implied by section headings or topic lists
                        - Rephrase each as a measurable outcome using a taxonomy verb.
                        - CRITICAL RULE: There must NEVER be more than one SOLO/Bloom taxonomy verb in a single learning goal. For example, "Participants will be able to explain concept A and concept B" is allowed, but "discuss and explain concept A" is strictly forbidden. Use exactly one core measurable verb per goal.
                        - Return between 2 and 8 goals
                        - Each goal must be a complete sentence starting with "Participants will be able to..."

                        Return a JSON array of strings, e.g.:
                        ["Participants will be able to apply X...", "Participants will be able to evaluate Y..."]
                        """,
                sessionType, background, doc);

        String raw = llm.call(systemPrompt, userPrompt);
        String json = llm.extractJsonArray(raw);
        return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
        });
    }

    // ── Automatic grammar check for LGs ──────────────────────────────────────

    public List<String> fixGoalsGrammar(List<String> goals) throws Exception {
        if (goals == null || goals.isEmpty())
            return goals;
        String systemPrompt = """
                You are a helpful AI assistant. Fix obvious spelling and grammar errors in the provided learning goals (e.g. 'linr regression' -> 'linear regression').
                Do not change the meaning, the structure, or the taxonomy verbs.
                Always respond with ONLY a valid JSON array of strings — no prose.
                """;

        String userPrompt = "Learning goals:\n" + mapper.writeValueAsString(goals)
                + "\n\nReturn the corrected JSON array of strings.";
        String raw = llm.call(systemPrompt, userPrompt);
        String json = llm.extractJsonArray(raw);
        return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
        });
    }

    // ── Step 1b: placeholder (activities are selected by user in Step 2) ─
    public List<LearningGoalPlanDto> generateActivities(List<LearningGoalPlanDto> goals,
            WorkshopInputDto meta,
            String availableMaterials) throws Exception {
        return goals;
    }

    public WorkshopSessionDto generateSession(List<LearningGoalPlanDto> goals,
            WorkshopInputDto meta,
            SessionSkeletonDto skeleton) throws Exception {
        // Filter out 0-duration sections from the skeleton blocks before sending to LLM
        List<SkeletonBlockDto> filteredBlocks = skeleton.blocks().stream()
                .map(block -> {
                    if (block.sections() == null || block.sections().isEmpty())
                        return block;
                    var filteredSections = block.sections().stream()
                            .filter(s -> s.duration() > 0)
                            .collect(Collectors.toList());
                    return new SkeletonBlockDto(
                            block.phase(), block.lgIndex(), block.duration(),
                            block.title(), block.description(), filteredSections);
                })
                .collect(Collectors.toList());
        SessionSkeletonDto filteredSkeleton = new SessionSkeletonDto(
                skeleton.learningGoal(), filteredBlocks,
                skeleton.omittedGoalIndices(), skeleton.sessionId());

        String sessionTypeLabel = resolveSessionType(meta.sessionType(), meta.sessionTypeOther());

        // 1. Generate title concurrently
        java.util.concurrent.CompletableFuture<String> titleFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                return generateSessionTitle(filteredSkeleton, goals, meta, sessionTypeLabel);
            }, llmExecutor);

            // 2. Generate blocks concurrently with concurrency limit of 3
            java.util.concurrent.Semaphore concurrencySemaphore = new java.util.concurrent.Semaphore(3);
            List<java.util.concurrent.CompletableFuture<ActivityBlockDto>> blockFutures = new ArrayList<>();
            
            for (SkeletonBlockDto block : filteredBlocks) {
                java.util.concurrent.CompletableFuture<ActivityBlockDto> future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    concurrencySemaphore.acquire();
                    log.debug("Hydrating block phase={}, lgIndex={}", block.phase(), block.lgIndex());
                    String prompt = buildHydrationPromptForBlock(block, filteredSkeleton, goals, meta, sessionTypeLabel);
                    String sysPrompt = "You are an expert learning designer. Return ONLY valid JSON representing the requested block.";
                    String rawJson = llm.call(sysPrompt, prompt);
                    String cleanedJson = llm.extractJsonObject(rawJson);
                    return mapper.readValue(cleanedJson, ActivityBlockDto.class);
                } catch (Exception e) {
                    log.error("Failed to hydrate block", e);
                    throw new RuntimeException(e);
                } finally {
                    concurrencySemaphore.release();
                }
            }, llmExecutor);
            blockFutures.add(future);
        }

        // Wait for all blocks to finish
        java.util.concurrent.CompletableFuture<Void> allBlocksFuture = java.util.concurrent.CompletableFuture.allOf(blockFutures.toArray(new java.util.concurrent.CompletableFuture[0]));
        allBlocksFuture.join();

        // Collect results in order
        List<ActivityBlockDto> hydratedBlocks = blockFutures.stream()
                .map(java.util.concurrent.CompletableFuture::join)
                .collect(Collectors.toList());

        String sessionTitle = titleFuture.join();

        // Build omitted goals list from skeleton indices
        List<String> omittedGoals = new ArrayList<>();
        if (skeleton.omittedGoalIndices() != null) {
            for (int idx : skeleton.omittedGoalIndices()) {
                if (idx > 0 && idx <= goals.size())
                    omittedGoals.add(goals.get(idx - 1).goal());
            }
        }

        String studentBg = (meta.studentBackground() != null) ? meta.studentBackground() : "";
        String prereqs = (meta.prerequisites() != null) ? meta.prerequisites() : "";

        WorkshopSessionDto session = new WorkshopSessionDto(
                null,
                sessionTitle,
                skeleton.learningGoal(),
                studentBg,
                prereqs,
                hydratedBlocks,
                omittedGoals,
                null);

        // ── Persist / update in DB ────────────────────────────────────
        String json = mapper.writeValueAsString(session);
        try {
            // If caller provided a draft session ID, update it; otherwise create a new
            // record
            WorkshopSessionEntity entity = null;
            if (skeleton.sessionId() != null && !skeleton.sessionId().isBlank()) {
                entity = repo.findById(skeleton.sessionId()).orElse(null);
            }
            if (entity == null) {
                entity = new WorkshopSessionEntity();
            }
            entity.setTitle(sessionTitle);
            entity.setLearningGoal(session.learningGoal());
            entity.setStudentBackground(session.studentBackground());
            entity.setPrerequisites(session.prerequisites());
            entity.setSessionJson(json);
            entity.setStatus("complete");
            entity.setCurrentStep("result");
            WorkshopSessionEntity saved = repo.save(entity);
            session = new WorkshopSessionDto(
                    saved.getId(), session.title(), session.learningGoal(), session.studentBackground(),
                    session.prerequisites(), session.blocks(), session.omittedGoals(), null);
        } catch (Exception e) {
            log.warn("Could not persist session: {}", e.getMessage());
        }

            return session;
    }

    private String generateSessionTitle(SessionSkeletonDto skeleton, List<LearningGoalPlanDto> goals, WorkshopInputDto meta, String sessionTypeLabel) {
        String systemPrompt = "You are an expert learning designer. Create a short, engaging title for this workshop session. Return ONLY the title as a plain string, no quotes, no JSON.";
        
        var sb = new StringBuilder();
        sb.append("SESSION CONTEXT:\n");
        sb.append("- Type: ").append(sessionTypeLabel).append("\n");
        sb.append("- Topic/Main Goal: ").append(skeleton.learningGoal() != null ? skeleton.learningGoal() : "").append("\n");
        sb.append("LEARNING GOALS:\n");
        for (int i = 0; i < goals.size(); i++) {
            sb.append("- ").append(goals.get(i).goal()).append("\n");
        }
        try {
            return llm.call(systemPrompt, sb.toString()).trim().replaceAll("^\"|\"$", "");
        } catch (Exception e) {
            log.warn("Failed to generate title, using fallback", e);
            return "Workshop Session Plan";
        }
    }

    private String buildHydrationPromptForBlock(SkeletonBlockDto targetBlock, SessionSkeletonDto skeleton,
            List<LearningGoalPlanDto> goals,
            WorkshopInputDto meta,
            String sessionTypeLabel) throws Exception {
        var sb = new StringBuilder();
        sb.append("SESSION CONTEXT:\n");
        sb.append("- Type: ").append(sessionTypeLabel).append("\n");
        sb.append("- Total duration: ").append(meta.duration()).append(" minutes\n");
        sb.append("- Participants: ").append(meta.participants()).append("\n");
        if (meta.interactionLevel() != null && !meta.interactionLevel().isBlank())
            sb.append("- Interaction level: ").append(meta.interactionLevel()).append("\n");
        if (meta.studentBackground() != null && !meta.studentBackground().isBlank())
            sb.append("- Student background: ").append(meta.studentBackground()).append("\n");

        sb.append("\nSELECTED ACTIVITIES (prefer these):");
        if (meta.selectedActivities() != null && !meta.selectedActivities().isEmpty()) {
            sb.append(" ").append(String.join(", ", meta.selectedActivities())).append("\n");
        } else {
            sb.append(" None specified, use best pedagogical judgment.\n");
        }

        sb.append("\nLEARNING GOALS:\n");
        for (int i = 0; i < goals.size(); i++) {
            var g = goals.get(i);
            sb.append("LG").append(i + 1).append(": ").append(g.goal()).append("\n");
        }

        sb.append("\nFULL SESSION OUTLINE (For context only):\n");
        sb.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(skeleton.blocks()));

        sb.append("\nTARGET BLOCK TO HYDRATE:\n");
        sb.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(targetBlock));

        sb.append(
                """

                        INSTRUCTIONS:
                        You are an expert learning designer. Your task is to generate a concrete instructor script for the TARGET BLOCK ONLY.
                        The key output is 'sections' — each section is a phase of the block with a step-by-step timed todo list telling the instructor EXACTLY what to do.

                        CRITICAL RULES:
                        1. DO NOT invent or elaborate on the teaching content. Focus entirely on the PROCESS.
                        2. Keep the step descriptions short and precise. Avoid fluff.
                        3. Each step in the todo list should be timed and action-oriented. Do NOT use the word "instructor".
                        4. Within a learning cycle block, the "Participants Practice" section MUST be led by the chosen activity and ideally broken down into these four distinct steps:
                           - Explain (e.g., "1 min - Explain: [provide highly specific, step-by-step instructions]")
                           - Prompt (e.g., "1 min - Prompt: [insert the detailed prompt]")
                           - Activity (e.g., "6 min - Activity: students do the activity in pairs")
                           - Summarize (e.g., "2 min - Summarize: [insert possible answers]")
                        5. Match activities to the SELECTED ACTIVITIES list where appropriate.
                        6. Every section's steps must sum exactly to that section's duration.
                        7. CRITICAL: Do NOT change the `phase`, `lgIndex`, section titles, or section durations from what is provided in the target block.
                        8. For non-learning-cycle blocks (ARRIVE, ACTIVATE, EVALUATE, BREAK, SUMMARY, CUSTOM, BUFFER), generate steps directly under the block in a single section. Important: for BREAK blocks, make sure to give it a proper 'phaseLabel' like "Coffee Break".
                        9. The 'phaseLabel' should be a short, topic-focused title.
                        10. Do NOT list "Lecture" or "Presentation" under 'methods'.

                        OUTPUT FORMAT (return only this JSON object, no markdown):
                        {
                          "phase": "LEARNING_CYCLE",
                          "lgIndex": 1,
                          "duration": 20,
                          "phaseLabel": "Topic Label Here",
                          "methods": ["Think-Pair-Share"],
                          "materials": ["Worksheet"],
                          "sections": [
                            {
                              "title": "You explain",
                              "duration": 10,
                              "steps": [
                                "10 min — Lecture on [topic of LG1] using slides"
                              ],
                              "methods": [],
                              "materials": ["Slides"]
                            },
                            {
                              "title": "Participants Practice",
                              "duration": 10,
                              "steps": [
                                "1 min — Explain: rules for Think-Pair-Share",
                                "1 min — Prompt: [insert prompt]",
                                "6 min — Activity: students discuss in pairs",
                                "2 min — Summarize: cold-call pairs"
                              ],
                              "methods": ["Think-Pair-Share"],
                              "materials": ["Worksheet"]
                            }
                          ]
                        }
                        """);

        return sb.toString();
    }

    // ── Draft management ──────────────────────────────────────────────

    /**
     * Upsert a draft session record. If sessionId is provided and found, updates
     * it;
     * otherwise creates a new entity and returns the assigned ID.
     */
    public String saveDraft(SaveDraftRequestDto req) {
        WorkshopSessionEntity entity = null;
        if (req.sessionId() != null && !req.sessionId().isBlank()) {
            entity = repo.findById(req.sessionId()).orElse(null);
        }
        if (entity == null) {
            entity = new WorkshopSessionEntity();
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
    public List<SessionSummaryDto> listSessions() {
        return repo.findAllOrdered().stream()
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

    /** Delete a session by ID. */
    public void deleteSession(String id) {
        repo.deleteById(id);
    }

    /** Export all child sessions of a lecture into a ZIP file. */
    public byte[] exportLectureZip(String lectureId,
            com.workshopper.service.PdfExportService pdfService,
            com.workshopper.service.PptxExportService pptxService) throws Exception {
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
                    byte[] pptxBytes = pptxService.exportToPptx(requestDto, templateStream);
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
