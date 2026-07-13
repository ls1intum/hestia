package de.tum.cit.hestia.learninggoalhub.hierarchy;

public enum HierarchyLevel {
    MODULE,
    SESSION,
    EXERCISE,
    /** Root holding a course's exam-task goals (ExamLens integration), alongside the MODULE root. */
    EXAM,
    /** Root holding a course's terminal competencies (the competency-tree view, alongside MODULE). */
    COMPETENCY
}
