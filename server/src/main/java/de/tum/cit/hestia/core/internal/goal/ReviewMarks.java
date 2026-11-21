package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.events.GoalChanged;
import de.tum.cit.hestia.core.events.GoalRemoved;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks every alignment link of a goal for review when the goal changes or is removed. A plain
 * (synchronous) listener, so the marks are written in the transaction that changed the goal and
 * roll back with it. Nothing else is rewritten: the linked objects keep what they have until the
 * teacher confirms the link ({@code AlignmentApi.confirm}) or changes the object.
 */
@Component
class ReviewMarks {

    private final AlignmentLinkRepository links;
    private final EntityManager entityManager;

    ReviewMarks(AlignmentLinkRepository links, EntityManager entityManager) {
        this.links = links;
        this.entityManager = entityManager;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(GoalChanged event) {
        mark(event.goalId(), CoreAlignmentLink.GOAL_CHANGED);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(GoalRemoved event) {
        mark(event.goalId(), CoreAlignmentLink.GOAL_REMOVED);
    }

    /**
     * One update of the review columns only, so a link loaded earlier in this transaction cannot
     * write an old seen version back; links already loaded are refreshed to the new state.
     */
    private void mark(UUID goalId, String reason) {
        if (links.markForReview(goalId, reason) > 0) {
            links.findByGoalIdOrderByCreatedAtAscIdAsc(goalId).forEach(entityManager::refresh);
        }
    }
}
