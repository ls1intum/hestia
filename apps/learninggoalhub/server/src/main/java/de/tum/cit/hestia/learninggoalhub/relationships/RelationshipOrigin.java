package de.tum.cit.hestia.learninggoalhub.relationships;

public enum RelationshipOrigin {
    HIERARCHY,
    EMBEDDING,
    LLM,
    /**
     * Built by competency-tree synthesis rather than by extraction or by hand. These edges are fully
     * reproducible, so a tree rebuild deletes every one of them and lays the tree down again. Without
     * this marker a rebuild cannot tell a gathered tree edge from the extraction edge it sits beside,
     * and re-running would silently accumulate duplicates.
     */
    SYNTHESIS
}
