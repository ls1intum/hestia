package de.tum.cit.hestia.assessment.flow;

/**
 * The ONE place that knows where the HTTP API lives: below the module prefix {@code /examlense}.
 */
final class FlowPaths {

    static final String BASE = "/examlense/api";

    private FlowPaths() {}

    /** {@code FlowPaths.api("/exams")} gives the path below the base, e.g. {@code /examlense/api/exams}. */
    static String api(String path) {
        return BASE + path;
    }
}
