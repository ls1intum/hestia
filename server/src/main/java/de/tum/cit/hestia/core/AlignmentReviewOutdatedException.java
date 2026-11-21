package de.tum.cit.hestia.core;

import java.util.UUID;

/**
 * A review was confirmed for a goal version that is no longer the current one (the goal changed
 * again after the teacher looked at it); answered with 409, the client reloads the review.
 */
public class AlignmentReviewOutdatedException extends RuntimeException {

    public AlignmentReviewOutdatedException(UUID linkId, int confirmedVersion, int currentVersion) {
        super("The goal of link " + linkId + " is at version " + currentVersion + ", not " + confirmedVersion);
    }
}
