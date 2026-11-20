package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.events.GoalChanged;
import de.tum.cit.hestia.core.events.GoalRemoved;
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

    ReviewMarks(AlignmentLinkRepository links) {
        this.links = links;
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

    private void mark(UUID goalId, String reason) {
        links.findByGoalIdOrderByCreatedAtAscIdAsc(goalId).forEach(l -> l.markForReview(reason));
        links.flush();
    }
}
