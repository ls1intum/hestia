package de.tum.cit.hestia.core;

import java.util.UUID;

/** The goal does not exist or is not visible to the current user; answered with 404. */
public class GoalNotFoundException extends RuntimeException {

    public GoalNotFoundException(UUID goalId) {
        super("Learning goal not found: " + goalId);
    }
}
