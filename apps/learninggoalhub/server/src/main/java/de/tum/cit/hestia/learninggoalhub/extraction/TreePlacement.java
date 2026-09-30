package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.extraction.TopicTreeSynthesizer.MenuTopic;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicTreeSynthesizer.Placement;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Goals placed into a finished tree by {@link TopicTreeSynthesizer#place}: per topic the goals
 * directly beneath it, per skill the goals beneath that skill, and the goals no topic covers.
 *
 * <p>Only skill-tier goals are offered the skills. A skill's children are sub-skills, and knowledge
 * belongs beneath a sub-skill rather than a skill, so knowledge goals see the topics alone and go
 * directly under the topic they belong to.
 */
record TreePlacement(List<List<LearningGoal>> underTopic,
                     List<List<List<LearningGoal>>> underSkill,
                     List<LearningGoal> unplaced) {

    /**
     * Places {@code goals} into the tree {@code menu} describes: one call for the skill-tier goals
     * against topics and skills, one for the knowledge goals against topics only.
     *
     * @throws RuntimeException when a call fails.
     */
    static TreePlacement place(TopicTreeSynthesizer synthesizer, List<MenuTopic> menu,
                               List<LearningGoal> goals, String modelOverride) {
        TreePlacement result = empty(menu);
        List<LearningGoal> skills = goals.stream().filter(ExtractionRunner::isSkillTier).toList();
        List<LearningGoal> knowledge = goals.stream().filter(g -> !ExtractionRunner.isSkillTier(g)).toList();
        List<MenuTopic> topicsOnly = menu.stream().map(topic -> new MenuTopic(topic.label(), List.of())).toList();
        result.add(skills, call(synthesizer, menu, skills, modelOverride));
        result.add(knowledge, call(synthesizer, topicsOnly, knowledge, modelOverride));
        return result;
    }

    /** A placement with nothing placed yet, shaped like {@code menu}. */
    static TreePlacement empty(List<MenuTopic> menu) {
        List<List<LearningGoal>> underTopic = new ArrayList<>();
        List<List<List<LearningGoal>>> underSkill = new ArrayList<>();
        for (MenuTopic topic : menu) {
            underTopic.add(new ArrayList<>());
            List<List<LearningGoal>> skills = new ArrayList<>();
            topic.skills().forEach(skill -> skills.add(new ArrayList<>()));
            underSkill.add(skills);
        }
        return new TreePlacement(underTopic, underSkill, new ArrayList<>());
    }

    private static Map<Integer, Placement> call(TopicTreeSynthesizer synthesizer, List<MenuTopic> menu,
                                                List<LearningGoal> goals, String modelOverride) {
        if (goals.isEmpty()) {
            return Map.of();
        }
        return synthesizer.place(menu, goals.stream().map(LearningGoal::getText).toList(), modelOverride);
    }

    private void add(List<LearningGoal> goals, Map<Integer, Placement> placements) {
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
        for (List<List<LearningGoal>> skills : underSkill) {
            placed += skills.stream().mapToInt(List::size).sum();
        }
        return placed;
    }
}
