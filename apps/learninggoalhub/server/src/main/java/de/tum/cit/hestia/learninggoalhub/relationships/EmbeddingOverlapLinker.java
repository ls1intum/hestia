package de.tum.cit.hestia.learninggoalhub.relationships;

import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes OVERLAPS_WITH relationships from pairwise cosine similarity of goal embeddings within
 * a course. Operates in the band {@code minSimilarity ≤ sim < dedupThreshold} — pairs at or above
 * the dedup threshold are merged by {@link de.tum.cit.hestia.learninggoalhub.dedup.GoalDeduplicator}
 * during extraction and never reach this stage.
 *
 * <p>On a homogeneous corpus (e.g. one ML course) the band alone still yields a hairball, so each
 * goal's overlap degree is capped at {@code maxPerGoal}: candidate pairs are taken strongest-first
 * and a pair is kept only while neither of its goals has reached the cap. Every goal therefore
 * retains its strongest few overlaps and the dense uniform middle is dropped.
 */
@Service
public class EmbeddingOverlapLinker {

    private final EntityManager entityManager;
    private final GoalRelationshipRepository relationshipRepository;
    private final double minSimilarity;
    private final double dedupThreshold;
    private final int maxPerGoal;

    public EmbeddingOverlapLinker(EntityManager entityManager,
                                  GoalRelationshipRepository relationshipRepository,
                                  @Value("${hestia.overlap.min-similarity:0.85}") double minSimilarity,
                                  @Value("${hestia.dedup.similarity-threshold:0.92}") double dedupThreshold,
                                  @Value("${hestia.overlap.max-per-goal:3}") int maxPerGoal) {
        this.entityManager = entityManager;
        this.relationshipRepository = relationshipRepository;
        this.minSimilarity = minSimilarity;
        this.dedupThreshold = dedupThreshold;
        this.maxPerGoal = maxPerGoal;
    }

    @Transactional
    public int linkCourse(Long courseId) {
        double minDistance = 1.0 - dedupThreshold;
        double maxDistance = 1.0 - minSimilarity;

        // Each unordered in-band pair once (a.id < b.id), strongest first so the greedy degree cap
        // below keeps the strongest overlaps and drops the weaker middle.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT a.id, b.id, 1.0 - (a.embedding <=> b.embedding) AS similarity "
                                + "FROM learning_goal a "
                                + "JOIN learning_goal b "
                                + "  ON a.course_id = b.course_id AND a.id < b.id "
                                + "WHERE a.course_id = :courseId "
                                + "AND a.embedding IS NOT NULL "
                                + "AND b.embedding IS NOT NULL "
                                + "AND (a.embedding <=> b.embedding) > :minDistance "
                                + "AND (a.embedding <=> b.embedding) <= :maxDistance "
                                + "ORDER BY similarity DESC, a.id, b.id")
                .setParameter("courseId", courseId)
                .setParameter("minDistance", minDistance)
                .setParameter("maxDistance", maxDistance)
                .getResultList();

        int cap = Math.max(1, maxPerGoal);
        Map<Long, Integer> degree = new HashMap<>();
        int created = 0;
        for (Object[] row : rows) {
            Long sourceId = ((Number) row[0]).longValue();
            Long targetId = ((Number) row[1]).longValue();
            double similarity = ((Number) row[2]).doubleValue();

            if (degree.getOrDefault(sourceId, 0) >= cap || degree.getOrDefault(targetId, 0) >= cap) {
                continue;
            }
            // Count both new and pre-existing edges toward the cap so a re-run reproduces the same
            // strongest-first selection (and stays idempotent) instead of filling the freed slots.
            if (!relationshipRepository.existsBySourceIdAndTargetIdAndType(
                    sourceId, targetId, RelationshipType.OVERLAPS_WITH)) {
                LearningGoal source = entityManager.getReference(LearningGoal.class, sourceId);
                LearningGoal target = entityManager.getReference(LearningGoal.class, targetId);
                relationshipRepository.save(new GoalRelationship(
                        source, target, RelationshipType.OVERLAPS_WITH, similarity, RelationshipOrigin.EMBEDDING));
                created++;
            }
            degree.merge(sourceId, 1, Integer::sum);
            degree.merge(targetId, 1, Integer::sum);
        }
        return created;
    }
}
