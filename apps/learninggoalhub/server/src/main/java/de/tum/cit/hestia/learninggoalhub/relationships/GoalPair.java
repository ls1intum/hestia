package de.tum.cit.hestia.learninggoalhub.relationships;

/**
 * A candidate goal pair handed to {@link PrerequisiteService} for prerequisite judgement, carrying
 * only the two goal texts (A and B). The pair is unordered — the service decides the direction.
 */
public record GoalPair(String a, String b) {
}
