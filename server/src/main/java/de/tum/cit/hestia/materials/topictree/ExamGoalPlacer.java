package de.tum.cit.hestia.materials.topictree;

import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRelationInfo;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import de.tum.cit.hestia.materials.goal.GoalCreationProvenance;
import de.tum.cit.hestia.materials.goal.PlacedGoal;
import de.tum.cit.hestia.materials.goal.RelationshipOrigin;
import de.tum.cit.hestia.materials.goal.RelationshipType;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Places freshly generated exam goals into a course's existing Topic tree, with the call
 * exercise outcomes go through during a tree build: a skill-tier goal under one of a topic's
 * skills or directly under the topic, a knowledge goal directly under a topic. Exam goals usually
 * arrive after the tree exists, and a rebuild places them again itself, so this only covers the
 * goals of one exam-goal request against the tree as it stands.
 */
@Service
public class ExamGoalPlacer {

    private static final Logger log = LoggerFactory.getLogger(ExamGoalPlacer.class);

    private final TopicTreeSynthesizer topicTreeSynthesizer;
    private final LearningGoalApi goalApi;

    public ExamGoalPlacer(TopicTreeSynthesizer topicTreeSynthesizer, LearningGoalApi goalApi) {
        this.topicTreeSynthesizer = topicTreeSynthesizer;
        this.goalApi = goalApi;
    }

    /** A topic of the stored tree and the skills beneath it, in menu order. */
    private record StoredTopic(GoalInfo topic, List<GoalInfo> skills) {}

    /** One synthesis edge a placement draws: the exam goal (by its index) and the goal it goes under. */
    public record PlannedEdge(int goalIndex, UUID targetGoalId) {}

    /** The synthesis edges a placement draws, not yet stored, and how many exam goals it placed. */
    public record PlannedPlacement(List<PlannedEdge> edges, int placed) {

        static final PlannedPlacement NONE = new PlannedPlacement(List.of(), 0);
    }

    /**
     * Places the {@code examGoals} into the course's tree: each placed goal gets a synthesis edge to
     * its topic or skill, the kind a rebuild clears and redraws. The goals need not be stored yet;
     * the edges are stored with them by {@link #link}. A goal no topic covers stays under the EXAM
     * root. Best effort: without a tree, or when a call fails, nothing is placed and the exam goals
     * are still kept.
     */
    public PlannedPlacement plan(UUID courseId, List<NewGoal> examGoals, String modelOverride) {
        if (examGoals.isEmpty()) {
            return PlannedPlacement.NONE;
        }
        List<StoredTopic> tree;
        TreePlacement<NewGoal> placement;
        try {
            tree = storedTree(courseId);
            if (tree.isEmpty()) {
                return PlannedPlacement.NONE;
            }
            List<TopicTreeSynthesizer.MenuTopic> menu = tree.stream()
                    .map(t -> new TopicTreeSynthesizer.MenuTopic(t.topic().text(),
                            t.skills().stream().map(GoalInfo::text).toList()))
                    .toList();
            placement = TreePlacement.place(topicTreeSynthesizer, menu, examGoals, NewGoal::text,
                    goal -> TopicTreeService.isSkillTier(goal.role(),
                            goal.bloomLevel() == null ? null : BloomLevel.valueOf(goal.bloomLevel())),
                    modelOverride);
        } catch (RuntimeException ex) {
            log.warn("Exam goal placement failed for course {}, leaving {} exam goal(s) under the exam root: {}",
                    courseId, examGoals.size(), ex.getMessage());
            return PlannedPlacement.NONE;
        }
        Map<NewGoal, Integer> indexOf = new IdentityHashMap<>();
        for (int i = 0; i < examGoals.size(); i++) {
            indexOf.put(examGoals.get(i), i);
        }
        List<PlannedEdge> edges = new ArrayList<>();
        for (int topic = 0; topic < tree.size(); topic++) {
            edges(placement.underTopic().get(topic), tree.get(topic).topic(), indexOf, edges);
            for (int skill = 0; skill < tree.get(topic).skills().size(); skill++) {
                edges(placement.underSkill().get(topic).get(skill), tree.get(topic).skills().get(skill),
                        indexOf, edges);
            }
        }
        int placed = placement.placedCount();
        log.info("Placed {} of {} exam goal(s) in the Topic tree of course {}", placed, examGoals.size(), courseId);
        return new PlannedPlacement(edges, placed);
    }

    /**
     * Stores a placement's edges; call it in the transaction that stores the exam goals, with the
     * stored goals in the order they were planned.
     */
    public void link(PlannedPlacement placement, List<PlacedGoal> storedGoals) {
        for (PlannedEdge edge : placement.edges()) {
            goalApi.relate(storedGoals.get(edge.goalIndex()).id(), edge.targetGoalId(),
                    RelationshipType.CONTRIBUTES_TO.name(), 1.0, RelationshipOrigin.SYNTHESIS.name());
        }
    }

    /**
     * The course's topics with their skills. A skill is a generated or hand-added skill-tier node
     * hanging directly under a topic, the node the client shows as a skill.
     */
    private List<StoredTopic> storedTree(UUID courseId) {
        List<StoredTopic> tree = new ArrayList<>();
        List<GoalInfo> all = goalApi.goals(courseId);
        List<GoalInfo> topics = all.stream().filter(g -> g.origin() == GoalOrigin.TERMINAL).toList();
        if (topics.isEmpty()) {
            return tree;
        }
        Map<UUID, GoalInfo> byId = all.stream().collect(Collectors.toMap(GoalInfo::id, g -> g));
        List<GoalRelationInfo> edges = goalApi.relationships(courseId);
        for (GoalInfo topic : topics) {
            List<GoalInfo> skills = edges.stream()
                    .filter(edge -> edge.targetGoalId().equals(topic.id()))
                    .filter(edge -> RelationshipType.CONTRIBUTES_TO.name().equals(edge.type()))
                    .map(edge -> byId.get(edge.sourceGoalId()))
                    .filter(java.util.Objects::nonNull)
                    .filter(ExamGoalPlacer::isSkill)
                    .toList();
            tree.add(new StoredTopic(topic, skills));
        }
        return tree;
    }

    private static boolean isSkill(GoalInfo goal) {
        return goal.role() == GoalRole.SKILL
                && (goal.origin() == GoalOrigin.SYNTHESIZED
                    || GoalCreationProvenance.USER_CREATED.name().equals(goal.creationProvenance()));
    }

    private static void edges(List<NewGoal> goals, GoalInfo target, Map<NewGoal, Integer> indexOf,
                              List<PlannedEdge> edges) {
        for (NewGoal goal : goals) {
            edges.add(new PlannedEdge(indexOf.get(goal), target.id()));
        }
    }
}
