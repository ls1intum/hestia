package de.tum.cit.hestia.learninggoalhub.goal;

/**
 * The type of an edge between two goals. Only {@code CONTRIBUTES_TO} is still produced; the other
 * values are legacy, kept because courses extracted by earlier prototypes still store them.
 */
public enum RelationshipType {
    CONTRIBUTES_TO,
    /** Legacy: a tree edge of the competency trees built before edges carried {@code SYNTHESIS}. */
    SUPPORTS,
    /** Legacy: written by the prerequisite linker, which left with the competency tree. */
    PREREQUISITE_OF,
    /** Legacy: written by the embedding-based overlap linker, which left with the competency tree. */
    OVERLAPS_WITH
}
