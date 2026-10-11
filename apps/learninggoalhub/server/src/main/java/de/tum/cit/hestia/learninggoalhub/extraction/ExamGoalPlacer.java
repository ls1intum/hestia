package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.goal.GoalCreationProvenance;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Places freshly generated exam goals into a course's existing competency tree, with the call
 * exercise outcomes go through during a tree build: a skill-tier goal under one of a topic's
 * skills or directly under the topic, a knowledge goal directly under a topic. Exam goals usually
 * arrive after the tree exists, and a rebuild places them again itself, so this only covers the
 * goals of one exam-goal request against the tree as it stands.
 */
@Service
public class ExamGoalPlacer {

    private static final Logger log = LoggerFactory.getLogger(ExamGoalPlacer.class);

    private final TopicTreeSynthesizer topicTreeSynthesizer;
    private final LearningGoalRepository goalRepository;
    private final GoalRelationshipRepository relationshipRepository;

    public ExamGoalPlacer(TopicTreeSynthesizer topicTreeSynthesizer,
                          LearningGoalRepository goalRepository,
                          GoalRelationshipRepository relationshipRepository) {
        this.topicTreeSynthesizer = topicTreeSynthesizer;
        this.goalRepository = goalRepository;
        this.relationshipRepository = relationshipRepository;
    }

    /** A topic of the stored tree and the skills beneath it, in menu order. */
    private record StoredTopic(LearningGoal topic, List<LearningGoal> skills) {}

    /**
     * Places the persisted {@code examGoals} into the course's tree and links each placed goal to
     * its topic or skill with a synthesis edge, the kind a rebuild clears and redraws. A goal no
     * topic covers stays under the EXAM root. Best effort: without a tree, or when a call fails,
     * nothing is linked and the exam goals are still kept.
     *
     * @return how many exam goals were placed in the tree.
     */
    public int place(Course course, List<LearningGoal> examGoals, String modelOverride) {
        if (examGoals.isEmpty()) {
            return 0;
        }
        List<StoredTopic> tree;
        TreePlacement placement;
        try {
            tree = storedTree(course);
            if (tree.isEmpty()) {
                return 0;
            }
            List<TopicTreeSynthesizer.MenuTopic> menu = tree.stream()
                    .map(t -> new TopicTreeSynthesizer.MenuTopic(t.topic().getText(),
                            t.skills().stream().map(LearningGoal::getText).toList()))
                    .toList();
            placement = TreePlacement.place(topicTreeSynthesizer, menu, examGoals, modelOverride);
        } catch (RuntimeException ex) {
            log.warn("Exam goal placement failed for course {}, leaving {} exam goal(s) under the exam root: {}",
                    course.getId(), examGoals.size(), ex.getMessage());
            return 0;
        }
        for (int topic = 0; topic < tree.size(); topic++) {
            link(placement.underTopic().get(topic), tree.get(topic).topic());
            for (int skill = 0; skill < tree.get(topic).skills().size(); skill++) {
                link(placement.underSkill().get(topic).get(skill), tree.get(topic).skills().get(skill));
            }
        }
        int placed = placement.placedCount();
        log.info("Placed {} of {} exam goal(s) in the competency tree of course {}",
                placed, examGoals.size(), course.getId());
        return placed;
    }

    /**
     * The course's topics with their skills. A skill is a generated or hand-added skill-tier node
     * hanging directly under a topic, the node the client shows as a skill.
     */
    private List<StoredTopic> storedTree(Course course) {
        List<StoredTopic> tree = new ArrayList<>();
        List<LearningGoal> topics = goalRepository.findByCourseIdAndOriginIn(course.getId(), List.of(GoalOrigin.TERMINAL))
                .stream()
                .sorted(Comparator.comparing(LearningGoal::getId))
                .toList();
        if (topics.isEmpty()) {
            return tree;
        }
        // Sources are fetched with the edges: this runs outside a transaction, so a lazy source
        // could not be read afterwards.
        List<GoalRelationship> edges = relationshipRepository.findByTargetIdInWithSource(
                topics.stream().map(LearningGoal::getId).toList());
        for (LearningGoal topic : topics) {
            List<LearningGoal> skills = edges.stream()
                    .filter(edge -> edge.getTarget().getId().equals(topic.getId()))
                    .filter(edge -> edge.getType() == RelationshipType.CONTRIBUTES_TO)
                    .map(GoalRelationship::getSource)
                    .filter(ExamGoalPlacer::isSkill)
                    .sorted(Comparator.comparing(LearningGoal::getId))
                    .toList();
            tree.add(new StoredTopic(topic, skills));
        }
        return tree;
    }

    private static boolean isSkill(LearningGoal goal) {
        return goal.getRole() == GoalRole.SKILL
                && (goal.getOrigin() == GoalOrigin.SYNTHESIZED
                    || goal.getCreationProvenance() == GoalCreationProvenance.USER_CREATED);
    }

    private void link(List<LearningGoal> goals, LearningGoal target) {
        for (LearningGoal goal : goals) {
            relationshipRepository.save(new GoalRelationship(goal, target,
                    RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.SYNTHESIS));
        }
    }
}
