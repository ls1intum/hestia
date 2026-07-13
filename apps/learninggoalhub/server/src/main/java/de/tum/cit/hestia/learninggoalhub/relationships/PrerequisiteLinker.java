package de.tum.cit.hestia.learninggoalhub.relationships;

import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes PREREQUISITE_OF relationships. Rather than dumping every goal of a hierarchy level
 * into one prompt (which does not scale — a course can have hundreds of session-level goals), it
 * pre-selects candidate pairs from embedding similarity: each goal's nearest same-level neighbours
 * become candidate pairs, which are then judged by the LLM in small batches. This keeps prompts short
 * and focused, finds dependencies across sessions (e.g. an early lecture's goal feeding a later one),
 * and avoids the fragile verbatim-text matching of the old approach by referring to pairs by index.
 * Only verdicts at or above the configured confidence threshold are persisted.
 */
@Service
public class PrerequisiteLinker {

    private static final Logger log = LoggerFactory.getLogger(PrerequisiteLinker.class);

    private final EntityManager entityManager;
    private final LearningGoalRepository goalRepository;
    private final GoalRelationshipRepository relationshipRepository;
    private final PrerequisiteService prerequisiteService;
    private final double minConfidence;
    private final int neighbors;
    private final int batchSize;
    private final int parallelism;

    public PrerequisiteLinker(EntityManager entityManager,
                              LearningGoalRepository goalRepository,
                              GoalRelationshipRepository relationshipRepository,
                              PrerequisiteService prerequisiteService,
                              @Value("${hestia.prerequisite.min-confidence:0.8}") double minConfidence,
                              @Value("${hestia.prerequisite.candidate-neighbors:3}") int neighbors,
                              @Value("${hestia.prerequisite.batch-size:20}") int batchSize,
                              @Value("${hestia.extraction.parallelism:8}") int parallelism) {
        this.entityManager = entityManager;
        this.goalRepository = goalRepository;
        this.relationshipRepository = relationshipRepository;
        this.prerequisiteService = prerequisiteService;
        this.minConfidence = minConfidence;
        this.neighbors = neighbors;
        this.batchSize = batchSize;
        this.parallelism = parallelism;
    }

    @Transactional
    public int linkCourse(Long courseId, String modelOverride) {
        List<long[]> candidateIds = candidatePairs(courseId);
        if (candidateIds.isEmpty()) {
            return 0;
        }

        Map<Long, LearningGoal> byId = goalRepository.findByCourseId(courseId).stream()
                .collect(Collectors.toMap(LearningGoal::getId, Function.identity()));

        // Resolve id pairs to goal pairs, dropping any that no longer resolve.
        List<LearningGoal[]> candidates = new ArrayList<>();
        for (long[] ids : candidateIds) {
            LearningGoal a = byId.get(ids[0]);
            LearningGoal b = byId.get(ids[1]);
            if (a != null && b != null) {
                candidates.add(new LearningGoal[] {a, b});
            }
        }
        if (candidates.isEmpty()) {
            return 0;
        }

        List<PrerequisitePairJudgment> judgments = judgeInParallel(candidates, modelOverride);

        int created = 0;
        for (int i = 0; i < candidates.size(); i++) {
            PrerequisitePairJudgment j = judgments.get(i);
            if (j == null || j.direction() == PrerequisiteDirection.NONE || j.confidence() < minConfidence) {
                continue;
            }
            LearningGoal a = candidates.get(i)[0];
            LearningGoal b = candidates.get(i)[1];
            LearningGoal source = j.direction() == PrerequisiteDirection.A_BEFORE_B ? a : b;
            LearningGoal target = j.direction() == PrerequisiteDirection.A_BEFORE_B ? b : a;
            if (source.getId().equals(target.getId())) {
                continue;
            }
            if (relationshipRepository.existsBySourceIdAndTargetIdAndType(
                    source.getId(), target.getId(), RelationshipType.PREREQUISITE_OF)) {
                continue;
            }
            relationshipRepository.save(new GoalRelationship(
                    source, target, RelationshipType.PREREQUISITE_OF, j.confidence(), RelationshipOrigin.LLM));
            created++;
        }
        return created;
    }

    /**
     * Returns candidate goal-id pairs for the course: each goal's nearest {@code neighbors}
     * same-level neighbours by cosine distance, deduplicated to unordered pairs ({@code [min, max]}).
     * Goals without an embedding or a hierarchy level are excluded.
     */
    private List<long[]> candidatePairs(Long courseId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT a.id AS a_id, nbr.b_id AS b_id "
                                + "FROM learning_goal a "
                                + "JOIN hierarchy_node na ON a.hierarchy_node_id = na.id "
                                + "JOIN LATERAL ("
                                + "  SELECT b.id AS b_id "
                                + "  FROM learning_goal b "
                                + "  JOIN hierarchy_node nb ON b.hierarchy_node_id = nb.id "
                                + "  WHERE b.course_id = a.course_id "
                                + "    AND b.id <> a.id "
                                + "    AND b.embedding IS NOT NULL "
                                + "    AND nb.level = na.level "
                                + "  ORDER BY a.embedding <=> b.embedding "
                                + "  LIMIT :k"
                                + ") nbr ON true "
                                + "WHERE a.course_id = :courseId "
                                + "  AND a.embedding IS NOT NULL")
                .setParameter("courseId", courseId)
                .setParameter("k", Math.max(1, neighbors))
                .getResultList();

        Set<List<Long>> seen = new LinkedHashSet<>();
        List<long[]> pairs = new ArrayList<>();
        for (Object[] row : rows) {
            long a = ((Number) row[0]).longValue();
            long b = ((Number) row[1]).longValue();
            long lo = Math.min(a, b);
            long hi = Math.max(a, b);
            if (seen.add(List.of(lo, hi))) {
                pairs.add(new long[] {lo, hi});
            }
        }
        return pairs;
    }

    private List<PrerequisitePairJudgment> judgeInParallel(List<LearningGoal[]> candidates, String modelOverride) {
        int size = Math.max(1, batchSize);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, parallelism));
        try {
            List<CompletableFuture<List<PrerequisitePairJudgment>>> futures = new ArrayList<>();
            for (int start = 0; start < candidates.size(); start += size) {
                int from = start;
                int to = Math.min(start + size, candidates.size());
                List<GoalPair> batch = candidates.subList(from, to).stream()
                        .map(p -> new GoalPair(p[0].getText(), p[1].getText()))
                        .toList();
                futures.add(CompletableFuture.supplyAsync(
                        () -> safeJudge(batch, modelOverride), executor));
            }
            List<PrerequisitePairJudgment> all = new ArrayList<>(candidates.size());
            for (CompletableFuture<List<PrerequisitePairJudgment>> future : futures) {
                all.addAll(future.join());
            }
            return all;
        } finally {
            executor.shutdown();
        }
    }

    /** Judges one batch, falling back to null verdicts (no links) for the whole batch on failure. */
    private List<PrerequisitePairJudgment> safeJudge(List<GoalPair> batch, String modelOverride) {
        try {
            List<PrerequisitePairJudgment> result = prerequisiteService.judge(batch, modelOverride);
            if (result.size() == batch.size()) {
                return result;
            }
            log.warn("Prerequisite batch returned {} verdicts for {} pairs, skipping batch",
                    result.size(), batch.size());
        } catch (RuntimeException ex) {
            log.warn("Prerequisite judgement failed for batch of {} pairs: {}", batch.size(), ex.getMessage());
        }
        return new ArrayList<>(Collections.nCopies(batch.size(), null));
    }
}
