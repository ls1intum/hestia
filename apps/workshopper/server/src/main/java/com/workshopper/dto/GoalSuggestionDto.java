package com.workshopper.dto;

import java.util.List;

/**
 * A single AI suggestion for a learning goal.
 * type = "refine" → one improved version of the goal
 * type = "split"  → two sub-goals to replace the original
 */
public record GoalSuggestionDto(
        String type,       // "refine" | "split"
        List<String> values,
        String message
) {}
