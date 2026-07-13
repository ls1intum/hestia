package de.tum.cit.hestia.learninggoalhub.goal;

/**
 * Review state of a learning goal.
 *
 * <ul>
 *   <li>{@code PENDING} — produced by extraction and not yet reviewed by an instructor.</li>
 *   <li>{@code APPROVED} — explicitly accepted by an instructor.</li>
 * </ul>
 *
 * <p>Rejection has no state of its own: a goal the instructor does not want is deleted.</p>
 */
public enum GoalStatus {
    PENDING,
    APPROVED
}
