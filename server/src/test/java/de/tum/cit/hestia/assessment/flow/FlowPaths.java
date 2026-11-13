package de.tum.cit.hestia.assessment.flow;

/**
 * The ONE place that knows where the HTTP API lives. When the module prefix
 * arrives (e.g. {@code /examlense/api}), change {@link #BASE} and nothing else.
 */
final class FlowPaths {

    static final String BASE = "/api";

    private FlowPaths() {}

    /** {@code FlowPaths.api("/exams")} gives the path below the base, e.g. {@code /api/exams}. */
    static String api(String path) {
        return BASE + path;
    }
}
