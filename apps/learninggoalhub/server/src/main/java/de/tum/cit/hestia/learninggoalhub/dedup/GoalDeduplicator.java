package de.tum.cit.hestia.learninggoalhub.dedup;

import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GoalDeduplicator {

    private final EntityManager entityManager;
    private final double similarityThreshold;

    public GoalDeduplicator(EntityManager entityManager,
                            @Value("${hestia.dedup.similarity-threshold:0.92}") double similarityThreshold) {
        this.entityManager = entityManager;
        this.similarityThreshold = similarityThreshold;
    }

    public Optional<LearningGoal> findDuplicate(Long courseId, float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return Optional.empty();
        }
        double maxDistance = 1.0 - similarityThreshold;
        String literal = toVectorLiteral(embedding);

        @SuppressWarnings("unchecked")
        List<LearningGoal> results = entityManager.createNativeQuery(
                        "SELECT * FROM learning_goal "
                                + "WHERE course_id = :courseId "
                                + "AND embedding IS NOT NULL "
                                + "AND (embedding <=> CAST(:vec AS vector)) <= :maxDistance "
                                + "ORDER BY embedding <=> CAST(:vec AS vector) "
                                + "LIMIT 1",
                        LearningGoal.class)
                .setParameter("courseId", courseId)
                .setParameter("vec", literal)
                .setParameter("maxDistance", maxDistance)
                .getResultList();
        return results.stream().findFirst();
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    private static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 8);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.7f", embedding[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
