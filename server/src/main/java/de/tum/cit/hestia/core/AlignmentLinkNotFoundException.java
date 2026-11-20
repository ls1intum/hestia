package de.tum.cit.hestia.core;

import java.util.UUID;

/** The alignment link does not exist or is not visible to the current user; answered with 404. */
public class AlignmentLinkNotFoundException extends RuntimeException {

    public AlignmentLinkNotFoundException(UUID linkId) {
        super("Alignment link not found: " + linkId);
    }
}
