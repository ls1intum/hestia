package de.tum.cit.hestia.learninggoalhub.relationships;

/**
 * The prerequisite direction the LLM assigns to a candidate goal pair (A, B). The pair is presented
 * unordered; the model decides which goal must be achieved first, or that neither depends on the
 * other.
 */
public enum PrerequisiteDirection {
    /** A must be achieved before B — A is a prerequisite of B. */
    A_BEFORE_B,
    /** B must be achieved before A — B is a prerequisite of A. */
    B_BEFORE_A,
    /** No prerequisite dependency between the two goals. */
    NONE
}
