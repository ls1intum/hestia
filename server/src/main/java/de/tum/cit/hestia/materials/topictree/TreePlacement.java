package de.tum.cit.hestia.materials.topictree;

import de.tum.cit.hestia.materials.topictree.TopicTreeSynthesizer.MenuTopic;
import de.tum.cit.hestia.materials.topictree.TopicTreeSynthesizer.Placement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Goals placed into a finished tree by {@link TopicTreeSynthesizer#place}: per topic the goals
 * directly beneath it, per skill the goals beneath that skill, and the goals no topic covers. The
 * goals may be stored ones or goals about to be stored; {@code T} is whatever represents them.
 *
 * <p>Only skill-tier goals are offered the skills. A skill's children are sub-skills, and knowledge
 * belongs beneath a sub-skill rather than a skill, so knowledge goals see the topics alone and go
 * directly under the topic they belong to.
 */
record TreePlacement<T>(List<List<T>> underTopic,
                        List<List<List<T>>> underSkill,
                        List<T> unplaced) {

    /**
     * Places {@code goals} into the tree {@code menu} describes: one call for the skill-tier goals
     * against topics and skills, one for the knowledge goals against topics only.
     *
     * @throws RuntimeException when a call fails.
     */
    static <T> TreePlacement<T> place(TopicTreeSynthesizer synthesizer, List<MenuTopic> menu, List<T> goals,
                                      Function<T, String> textOf, Predicate<T> skillTier, String modelOverride) {
        TreePlacement<T> result = empty(menu);
        List<T> skills = goals.stream().filter(skillTier).toList();
        List<T> knowledge = goals.stream().filter(skillTier.negate()).toList();
        List<MenuTopic> topicsOnly = menu.stream().map(topic -> new MenuTopic(topic.label(), List.of())).toList();
        result.add(skills, call(synthesizer, menu, skills, textOf, modelOverride));
        result.add(knowledge, call(synthesizer, topicsOnly, knowledge, textOf, modelOverride));
        return result;
    }

    /** A placement with nothing placed yet, shaped like {@code menu}. */
    static <T> TreePlacement<T> empty(List<MenuTopic> menu) {
        List<List<T>> underTopic = new ArrayList<>();
        List<List<List<T>>> underSkill = new ArrayList<>();
        for (MenuTopic topic : menu) {
            underTopic.add(new ArrayList<>());
            List<List<T>> skills = new ArrayList<>();
            topic.skills().forEach(skill -> skills.add(new ArrayList<>()));
            underSkill.add(skills);
        }
        return new TreePlacement<>(underTopic, underSkill, new ArrayList<>());
    }

    private static <T> Map<Integer, Placement> call(TopicTreeSynthesizer synthesizer, List<MenuTopic> menu,
                                                    List<T> goals, Function<T, String> textOf,
                                                    String modelOverride) {
        if (goals.isEmpty()) {
            return Map.of();
        }
        return synthesizer.place(menu, goals.stream().map(textOf).toList(), modelOverride);
    }

    private void add(List<T> goals, Map<Integer, Placement> placements) {
        for (int index = 0; index < goals.size(); index++) {
            Placement placement = placements.get(index);
            if (placement == null) {
                unplaced.add(goals.get(index));
            } else if (placement.skill() == null) {
                underTopic.get(placement.topic()).add(goals.get(index));
            } else {
                underSkill.get(placement.topic()).get(placement.skill()).add(goals.get(index));
            }
        }
    }

    /** How many goals were placed somewhere in the tree. */
    int placedCount() {
        int placed = underTopic.stream().mapToInt(List::size).sum();
        for (List<List<T>> skills : underSkill) {
            placed += skills.stream().mapToInt(List::size).sum();
        }
        return placed;
    }
}
