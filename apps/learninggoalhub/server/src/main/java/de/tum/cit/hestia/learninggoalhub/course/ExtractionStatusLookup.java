package de.tum.cit.hestia.learninggoalhub.course;

import java.util.List;
import java.util.Map;

/**
 * The status of each course's latest extraction run, for the course summaries. Implemented by the
 * extraction package, which owns the runs, so course does not depend on it.
 */
public interface ExtractionStatusLookup {

    /** The latest run's status per course; a course that never ran is missing from the map. */
    Map<Long, ExtractionStatus> latestByCourse(List<Long> courseIds);
}
