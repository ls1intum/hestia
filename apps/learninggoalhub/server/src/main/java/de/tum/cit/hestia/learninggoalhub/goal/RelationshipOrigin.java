package de.tum.cit.hestia.learninggoalhub.goal;

public enum RelationshipOrigin {
    HIERARCHY,
    /** Legacy: origin of {@link RelationshipType#OVERLAPS_WITH} edges; nothing produces it any more. */
    EMBEDDING,
    /** Legacy: origin of {@link RelationshipType#PREREQUISITE_OF} edges; nothing produces it any more. */
    LLM,
    /**
     * Built by competency-tree synthesis rather than by extraction or by hand. These edges are fully
     * reproducible, so a tree rebuild deletes every one of them and lays the tree down again. Without
     * this marker a rebuild cannot tell a gathered tree edge from the extraction edge it sits beside,
     * and re-running would silently accumulate duplicates.
     */
    SYNTHESIS
}
