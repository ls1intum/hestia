package de.tum.cit.hestia.learninggoalhub.exam;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.document.LanguageDetectionService;
import de.tum.cit.hestia.learninggoalhub.document.LanguageUtils;
import de.tum.cit.hestia.learninggoalhub.embedding.EmbeddingService;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a consumer-submitted exam (an ordered list of context/task blocks) into persisted learning
 * goals of origin {@link GoalOrigin#EXAM}, attached to the course's lazily created EXAM hierarchy
 * root. Context blocks accumulate: each task is generated with every CONTEXT block that precedes it
 * in the exam. Bloom/SOLO levels and embeddings are best-effort (one batch call each); their failure
 * does not fail the request, matching the extraction pipeline's behaviour.
 *
 * <p>Deliberately NOT transactional across the LLM calls — generation can take seconds per task.
 * Persistence happens in one {@code saveAll} at the end.
 */
@Service
public class ExamGoalService {

    /** The label of a course's lazily created EXAM hierarchy root. */
    static final String EXAM_ROOT_LABEL = "Exam";

    private static final Logger log = LoggerFactory.getLogger(ExamGoalService.class);

    private final ExamGoalGenerator generator;
    private final TaxonomyService taxonomyService;
    private final EmbeddingService embeddingService;
    private final LearningGoalRepository goalRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final LanguageDetectionService languageDetectionService;

    public ExamGoalService(ExamGoalGenerator generator,
                           TaxonomyService taxonomyService,
                           EmbeddingService embeddingService,
                           LearningGoalRepository goalRepository,
                           HierarchyNodeRepository hierarchyNodeRepository,
                           LanguageDetectionService languageDetectionService) {
        this.generator = generator;
        this.taxonomyService = taxonomyService;
        this.embeddingService = embeddingService;
        this.goalRepository = goalRepository;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
        this.languageDetectionService = languageDetectionService;
    }

    /** The persisted goals of one TASK block, keyed by the consumer's {@code blockId}. */
    public record TaskGoals(String blockId, List<LearningGoal> goals) {
    }

    public List<TaskGoals> generateForBlocks(Course course, List<ExamBlock> blocks, String modelOverride) {
        record TaskGeneration(String blockId, List<String> texts) {
        }

        List<TaskGeneration> generations = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (ExamBlock block : blocks) {
            if (block.blockType() == ExamBlockType.CONTEXT) {
                if (block.description() != null && !block.description().isBlank()) {
                    if (!context.isEmpty()) {
                        context.append("\n\n");
                    }
                    context.append(block.description().strip());
                }
            } else {
                String languageName = course.getOutputLanguage() != null
                        ? LanguageUtils.englishName(course.getOutputLanguage())
                        : LanguageUtils.englishName(languageDetectionService.detect(
                                (context == null ? "" : context + "\n\n")
                                        + (block.description() == null ? "" : block.description())));
                List<String> texts = generator
                        .generate(context.toString(), block.taskType(), block.description(),
                                languageName, modelOverride)
                        .stream()
                        .map(GeneratedExamGoal::text)
                        .filter(t -> t != null && !t.isBlank())
                        .map(String::strip)
                        .toList();
                generations.add(new TaskGeneration(block.blockId(), texts));
            }
        }

        List<String> allTexts = generations.stream().flatMap(g -> g.texts().stream()).toList();
        List<TaxonomyClassification> classifications = safeClassifyBatch(allTexts, modelOverride);
        List<float[]> embeddings = safeEmbedBatch(allTexts);

        HierarchyNode examRoot = examRoot(course);
        List<LearningGoal> goals = new ArrayList<>(allTexts.size());
        int i = 0;
        for (TaskGeneration generation : generations) {
            for (String text : generation.texts()) {
                LearningGoal goal = new LearningGoal(course, text, GoalKind.IMPLICIT);
                goal.setOrigin(GoalOrigin.EXAM);
                goal.setHierarchyNode(examRoot);
                TaxonomyClassification classification = classifications.get(i);
                if (classification != null) {
                    goal.setBloomLevel(classification.bloom());
                    goal.setSoloLevel(classification.solo());
                }
                goal.setEmbedding(embeddings.get(i));
                goals.add(goal);
                i++;
            }
        }
        goalRepository.saveAll(goals);

        List<TaskGoals> result = new ArrayList<>(generations.size());
        int from = 0;
        for (TaskGeneration generation : generations) {
            result.add(new TaskGoals(generation.blockId(), goals.subList(from, from + generation.texts().size())));
            from += generation.texts().size();
        }
        return result;
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

    private List<float[]> safeEmbedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        try {
            List<float[]> result = embeddingService.embedAll(texts);
            if (result.size() == texts.size()) {
                return result;
            }
            log.warn("Embedding batch returned {} vectors for {} exam goals, persisting without vectors",
                    result.size(), texts.size());
        } catch (RuntimeException ex) {
            log.warn("Embedding failed for exam goals, persisting without vectors: {}", ex.getMessage());
        }
        return Collections.nCopies(texts.size(), null);
    }
}
