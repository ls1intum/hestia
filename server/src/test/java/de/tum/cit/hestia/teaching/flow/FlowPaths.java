package de.tum.cit.hestia.teaching.flow;

/** The ONLY place that knows the URL layout of the Workshopper API (e.g. a later module prefix). */
final class FlowPaths {

    /** Prefix of every API path; the teaching module is served under /workshopper. */
    static final String API = "/workshopper/api";

    static final String COURSES = API + "/workshop/courses";
    static final String COURSE_DRAFT = COURSES + "/draft";
    static final String SESSIONS = API + "/workshop/sessions";
    static final String SESSION_DRAFT = SESSIONS + "/draft";
    static final String REFINE_GOAL = API + "/workshop/refine-goal";
    static final String FIX_GOALS_GRAMMAR = API + "/workshop/fix-goals-grammar";
    static final String LGH_COURSES = API + "/workshop/lgh/courses";

    private FlowPaths() {}

    static String course(String id) { return COURSES + "/" + id; }

    static String session(String id) { return SESSIONS + "/" + id; }

    static String sessionAction(String id, String action) { return SESSIONS + "/" + id + "/" + action; }
}
