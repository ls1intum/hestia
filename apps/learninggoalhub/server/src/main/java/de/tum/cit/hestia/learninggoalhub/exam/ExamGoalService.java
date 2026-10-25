package de.tum.cit.hestia.learninggoalhub.exam;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.document.LanguageDetectionService;
import de.tum.cit.hestia.learninggoalhub.document.LanguageUtils;
import de.tum.cit.hestia.learninggoalhub.extraction.ExamGoalPlacer;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.taxonomy.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Turns a consumer-submitted exam (an ordered list of context/task blocks) into persisted learning
 * goals of origin {@link GoalOrigin#EXAM}, attached to the course's lazily created EXAM hierarchy
 * root. Context blocks accumulate: each task is generated with every CONTEXT block that precedes it
 * in the exam. The submitted blocks are stored as an {@link ExamSubmission}, and every goal points at
 * the task block it came from. When the course already has a competency tree, the new goals are then
 * placed under its topics. Bloom/SOLO levels are best-effort (one batch call); their failure does not
 * fail the request, matching the extraction pipeline's behaviour.
 *
 * <p>Deliberately NOT transactional across the LLM calls — generation can take seconds per task.
 * Every call runs first; the submission, the exam root, the goals, and their placement edges are then
 * stored in one short transaction, so a failed request leaves nothing behind.
 *
 * <p>A consumer gives up on a slow request and the user submits it again while the first is still
 * generating. An identical request for the same course therefore joins the one in flight and gets
 * its goals instead of generating a second copy. Once a request has finished, resubmitting it
 * generates afresh.
 */
@Service
public class ExamGoalService {

    /** The label of a course's lazily created EXAM hierarchy root. */
    static final String EXAM_ROOT_LABEL = "Exam";

    private static final Logger log = LoggerFactory.getLogger(ExamGoalService.class);

    /** Bloom levels that make an outcome a skill; the extraction pipeline splits tiers the same way. */
    private static final Set<BloomLevel> SKILL_BLOOM =
            EnumSet.of(BloomLevel.APPLY, BloomLevel.ANALYZE, BloomLevel.EVALUATE, BloomLevel.CREATE);

    private final ExamGoalGenerator generator;
    private final TaxonomyService taxonomyService;
    private final LearningGoalRepository goalRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final LanguageDetectionService languageDetectionService;
    private final ExamSubmissionRepository submissionRepository;
    private final ExamGoalPlacer placer;
    private final TransactionOperations transactions;
    private final Map<RequestKey, CompletableFuture<List<TaskGoals>>> inFlight = new ConcurrentHashMap<>();

    public ExamGoalService(ExamGoalGenerator generator,
                           TaxonomyService taxonomyService,
                           LearningGoalRepository goalRepository,
                           HierarchyNodeRepository hierarchyNodeRepository,
                           LanguageDetectionService languageDetectionService,
                           ExamSubmissionRepository submissionRepository,
                           ExamGoalPlacer placer,
                           TransactionOperations transactions) {
        this.generator = generator;
        this.taxonomyService = taxonomyService;
        this.goalRepository = goalRepository;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
        this.languageDetectionService = languageDetectionService;
        this.submissionRepository = submissionRepository;
        this.placer = placer;
        this.transactions = transactions;
    }

    /** The persisted goals of one TASK block, keyed by the consumer's {@code blockId}. */
    public record TaskGoals(String blockId, List<LearningGoal> goals) {
    }

    /** What makes two requests identical: the course, the blocks in order, and the model. */
    private record RequestKey(Long courseId, List<ExamBlock> blocks, String modelOverride) {
    }

    public List<TaskGoals> generateForBlocks(Course course, List<ExamBlock> blocks, String modelOverride) {
        RequestKey key = new RequestKey(course.getId(), List.copyOf(blocks), modelOverride);
        CompletableFuture<List<TaskGoals>> own = new CompletableFuture<>();
        CompletableFuture<List<TaskGoals>> running = inFlight.putIfAbsent(key, own);
        if (running != null) {
            log.info("Exam-goal request for course {} is already running, joining it", course.getId());
            try {
                return running.join();
            } catch (CompletionException ex) {
                if (ex.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw ex;
            }
        }
        try {
            List<TaskGoals> result = generate(course, blocks, modelOverride);
            own.complete(result);
            return result;
        } catch (RuntimeException | Error ex) {
            own.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(key, own);
        }
    }

    private List<TaskGoals> generate(Course course, List<ExamBlock> blocks, String modelOverride) {
        record TaskGeneration(SubmittedExamBlock block, List<GeneratedExamGoal> goals) {
        }

        ExamSubmission submission = new ExamSubmission(course);
        List<TaskGeneration> generations = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (ExamBlock block : blocks) {
            if (block.blockType() == ExamBlockType.CONTEXT) {
                submission.addBlock(block, null, null);
                if (block.description() != null && !block.description().isBlank()) {
                    if (!context.isEmpty()) {
                        context.append("\n\n");
                    }
                    context.append(block.description().strip());
                }
            } else {
                SubmittedExamBlock stored = submission.addBlock(block, generations.size() + 1,
                        context.isEmpty() ? null : context.toString());
                String languageName = course.getOutputLanguage() != null
                        ? LanguageUtils.englishName(course.getOutputLanguage())
                        : LanguageUtils.englishName(languageDetectionService.detect(
                                (context == null ? "" : context + "\n\n")
                                        + (block.description() == null ? "" : block.description())));
                List<GeneratedExamGoal> generated = generator
                        .generate(context.toString(), block.taskType(), block.description(),
                                languageName, modelOverride)
                        .stream()
                        .filter(g -> g.text() != null && !g.text().isBlank())
                        .map(ExamGoalService::tidy)
                        .toList();
                generations.add(new TaskGeneration(stored, generated));
            }
        }

        List<String> allTexts = generations.stream()
                .flatMap(g -> g.goals().stream())
                .map(GeneratedExamGoal::text)
                .toList();
        List<TaxonomyClassification> classifications = safeClassifyBatch(allTexts, modelOverride);

        List<LearningGoal> goals = new ArrayList<>(allTexts.size());
        int i = 0;
        for (TaskGeneration generation : generations) {
            for (GeneratedExamGoal generated : generation.goals()) {
                LearningGoal goal = new LearningGoal(course, generated.text(), GoalKind.IMPLICIT);
                goal.setShortLabel(generated.shortLabel());
                goal.setOrigin(GoalOrigin.EXAM);
                goal.setExamBlock(generation.block());
                TaxonomyClassification classification = classifications.get(i);
                if (classification != null) {
                    goal.setBloomLevel(classification.bloom());
                    goal.setSoloLevel(classification.solo());
                    goal.setRole(tierOf(classification.bloom()));
                }
                goals.add(goal);
                i++;
            }
        }
        ExamGoalPlacer.PlannedPlacement placement = placer.plan(course, goals, modelOverride);

        transactions.executeWithoutResult(status -> {
            submissionRepository.save(submission);
            HierarchyNode examRoot = examRoot(course);
            goals.forEach(goal -> goal.setHierarchyNode(examRoot));
            goalRepository.saveAll(goals);
            placer.link(placement);
        });

        List<TaskGoals> result = new ArrayList<>(generations.size());
        int from = 0;
        for (TaskGeneration generation : generations) {
            result.add(new TaskGoals(generation.block().getBlockId(), goals.subList(from, from + generation.goals().size())));
            from += generation.goals().size();
        }
        return result;
    }

    /**
     * A generated goal as it is stored: trimmed, without a closing period, and with a label only when
     * it is one — a blank label, or one that merely repeats the text, is dropped and the text shown.
     */
    static GeneratedExamGoal tidy(GeneratedExamGoal generated) {
        String text = withoutClosingPeriod(generated.text());
        String label = generated.shortLabel() == null ? null : withoutClosingPeriod(generated.shortLabel());
        if (label != null && (label.isEmpty() || label.equalsIgnoreCase(text))) {
            label = null;
        }
        return new GeneratedExamGoal(text, label);
    }

    private static String withoutClosingPeriod(String value) {
        String stripped = value.strip();
        return stripped.endsWith(".") ? stripped.substring(0, stripped.length() - 1).strip() : stripped;
    }

    /**
     * An exam goal's tier, read off its Bloom level like every other outcome's: doing or judging is a
     * skill, remembering or understanding is knowledge. Null when there is no level to read.
     */
    static GoalRole tierOf(BloomLevel bloom) {
        if (bloom == null) {
            return null;
        }
        return SKILL_BLOOM.contains(bloom) ? GoalRole.SKILL : GoalRole.KNOWLEDGE;
    }

    /** All exam goals of a course share one EXAM root node, created on first use. */
    private HierarchyNode examRoot(Course course) {
        return hierarchyNodeRepository
                .findFirstByCourseIdAndLevelOrderByIdAsc(course.getId(), HierarchyLevel.EXAM)
                .orElseGet(() -> hierarchyNodeRepository.save(
                        new HierarchyNode(course, null, HierarchyLevel.EXAM, EXAM_ROOT_LABEL)));
    }

    private List<TaxonomyClassification> safeClassifyBatch(List<String> texts, String modelOverride) {
        if (texts.isEmpty()) {
            return List.of();
        }
        try {
            List<TaxonomyClassification> result = taxonomyService.classifyBatch(texts, modelOverride);
            if (result.size() == texts.size()) {
                return result;
            }
            log.warn("Taxonomy batch returned {} results for {} exam goals, persisting without levels",
                    result.size(), texts.size());
        } catch (RuntimeException ex) {
            log.warn("Taxonomy classification failed for exam goals, persisting without levels: {}", ex.getMessage());
        }
        return Collections.nCopies(texts.size(), null);
    }
}
