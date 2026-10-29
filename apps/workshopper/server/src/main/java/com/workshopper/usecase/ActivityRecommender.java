package com.workshopper.usecase;

import com.workshopper.dto.LearningGoalPlanDto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the best-fit activity for a learning goal from its Bloom's and SOLO levels.
 *
 * <p>The best-fit activities of both levels are combined, Bloom's first, each list in order of
 * preference. If the user selected activities, only those count: when none of the best-fit
 * activities for the goal's level is selected, the search moves down one level in both
 * taxonomies at a time until a selected activity is found. If the user selected nothing,
 * the first best-fit activity is used directly.
 *
 * <p>Keep in sync with {@code client/src/lib/activity-taxonomy.ts}.
 */
public final class ActivityRecommender {

    /** Bloom's levels from lowest to highest, each with its best-fit activities in order of preference. */
    private static final List<List<String>> BLOOM = List.of(
            List.of("Quiz / Polls"),                                              // Remember
            List.of("Q&A Session", "Think-Pair-Share"),                           // Understand
            List.of("Worked Problem", "Hands-on Practice"),                       // Apply
            List.of("Case Study", "Group Discussion", "Concept Mapping"),         // Analyze
            List.of("Debate", "Peer Review"),                                     // Evaluate
            List.of("Design Sprint / Prototype Challenge", "Role Play")           // Create
    );
    private static final List<String> BLOOM_NAMES = List.of("remember", "understand", "apply", "analyze", "evaluate", "create");

    /** SOLO levels from lowest to highest. */
    private static final List<List<String>> SOLO = List.of(
            List.of("Quiz / Polls"),                                                             // Unistructural
            List.of("Q&A Session", "Brainstorming", "Worked Problem"),                           // Multistructural
            List.of("Concept Mapping", "Think-Pair-Share", "Group Discussion", "Case Study"),     // Relational
            List.of("Design Sprint / Prototype Challenge", "Debate", "Role Play", "Peer Review")  // Extended abstract
    );
    private static final List<String> SOLO_NAMES = List.of("unistructural", "multistructural", "relational", "extendedabstract");

    private ActivityRecommender() {}

    /**
     * Returns the activity to use for {@code goal}, or null when the goal has no recognised
     * level and no fallback applies (the caller then keeps its previous behaviour).
     */
    public static String recommend(LearningGoalPlanDto goal, List<String> selectedActivities) {
        if (goal == null) return null;
        return recommend(goal.bloomLevel(), goal.soloLevel(), selectedActivities);
    }

    public static String recommend(String bloomLevel, String soloLevel, List<String> selectedActivities) {
        int bloom = levelIndex(bloomLevel, BLOOM_NAMES);
        int solo = levelIndex(soloLevel, SOLO_NAMES);
        if (bloom < 0 && solo < 0) return null;

        List<String> selected = selectedActivities != null ? selectedActivities : List.of();
        if (selected.isEmpty()) {
            return candidates(bloom, solo).get(0);
        }

        // Step down both taxonomies together until one of the user's activities fits
        for (int step = 0; bloom - step >= 0 || solo - step >= 0; step++) {
            for (String candidate : candidates(bloom - step, solo - step)) {
                String match = matchSelected(candidate, selected);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static List<String> candidates(int bloom, int solo) {
        Set<String> result = new LinkedHashSet<>();
        if (bloom >= 0) result.addAll(BLOOM.get(bloom));
        if (solo >= 0) result.addAll(SOLO.get(solo));
        return new ArrayList<>(result);
    }

    /** Maps "EXTENDED_ABSTRACT", "Extended abstract", "analyse" etc. to the level index, or -1. */
    static int levelIndex(String level, List<String> names) {
        if (level == null) return -1;
        String key = level.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "").replace("analyse", "analyze");
        for (int i = 0; i < names.size(); i++) {
            if (key.contains(names.get(i))) return i;
        }
        return -1;
    }

    private static String matchSelected(String candidate, List<String> selected) {
        String key = normalise(candidate);
        return selected.stream().filter(s -> s != null && normalise(s).equals(key)).findFirst().orElse(null);
    }

    private static String normalise(String activity) {
        return activity.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }
}
