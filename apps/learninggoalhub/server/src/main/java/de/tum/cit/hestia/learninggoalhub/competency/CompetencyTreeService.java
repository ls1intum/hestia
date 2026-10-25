package de.tum.cit.hestia.learninggoalhub.competency;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentKind;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.LanguageUtils;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.GoalStatus;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.goal.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.RelationshipType;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.taxonomy.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.taxonomy.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import de.tum.cit.hestia.learninggoalhub.web.ExtractionErrors;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

/**
 * Builds, rebuilds and clears a course's competency tree: topic → capability → extracted skill →
 * knowledge, under the course's {@code COMPETENCY} root.
 *
 * <p>Every build reads the goals in one transaction, plans the tree with the models while no
 * transaction is open, and writes it in a second transaction under the course lock. A failed call
 * therefore leaves the existing tree and the extracted goals untouched.
 */
@Service
public class CompetencyTreeService {

    private static final Logger log = LoggerFactory.getLogger(CompetencyTreeService.class);

    private final CourseRepository courseRepository;
    private final DocumentRepository documentRepository;
    private final LearningGoalRepository goalRepository;
    private final GoalRelationshipRepository goalRelationshipRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final TopicTreeSynthesizer topicTreeSynthesizer;
    private final TaxonomyService taxonomyService;
    private final TransactionOperations transactions;

    public CompetencyTreeService(CourseRepository courseRepository,
                                 DocumentRepository documentRepository,
                                 LearningGoalRepository goalRepository,
                                 GoalRelationshipRepository goalRelationshipRepository,
                                 HierarchyNodeRepository hierarchyNodeRepository,
                                 TopicTreeSynthesizer topicTreeSynthesizer,
                                 TaxonomyService taxonomyService,
                                 TransactionOperations transactions) {
        this.courseRepository = courseRepository;
        this.documentRepository = documentRepository;
        this.goalRepository = goalRepository;
        this.goalRelationshipRepository = goalRelationshipRepository;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
        this.topicTreeSynthesizer = topicTreeSynthesizer;
        this.taxonomyService = taxonomyService;
        this.transactions = transactions;
    }

    /** Legacy Bloom fallback for role-null pre-V24 goals. */
    private static final Set<BloomLevel> SUB_SKILL_BLOOM =
            EnumSet.of(BloomLevel.APPLY, BloomLevel.ANALYZE, BloomLevel.EVALUATE, BloomLevel.CREATE);

    public static boolean isSkillTier(LearningGoal goal) {
        return goal.getRole() == GoalRole.SKILL
                || (goal.getRole() == null && SUB_SKILL_BLOOM.contains(goal.getBloomLevel()));
    }

    /**
     * Throws away a course's competency tree and builds a fresh one from the goals it already has,
     * without re-reading a single document. Only topic naming, batched assignment, per-topic
     * structuring and classification run, so iterating on the tree costs less than a full extraction.
     *
     * <p>Refuses by default once the tree contains instructor work — a hand-added skill, a
     * hand-added child, a generated subtree, or an approved terminal — because a rebuild replaces
     * exactly those nodes and nothing records what they were. {@code force} overrides that and
     * deletes them. Extracted session/exercise goals are never touched either way: the rebuild only
     * removes the terminals and the tree edges, so instructor edits and approvals on the goals
     * themselves survive.
     *
     * @return the freshly built tree; synthesis failures leave the existing tree untouched.
     */
    public CompetencyTreeResult rebuildCompetencyTree(Long courseId, String modelOverride, boolean force) {
        RebuildInput input = transactions.execute(status -> {
            Course course = courseRepository.findOrThrow(courseId);
            rejectManualWorkUnlessForced(courseId, force);
            List<Document> documents = documentRepository.findByCourseId(courseId);
            String courseLanguage = LanguageUtils.resolveLanguage(course, null, LanguageUtils.dominantLanguage(documents));
            return new RebuildInput(course, LanguageUtils.englishName(courseLanguage), loadTreeSeeds(course));
        });
        if (input == null) {
            throw new IllegalStateException("Competency tree setup completed without a result.");
        }
        // Synthesise the replacement BEFORE destroying the old tree, and with no transaction open:
        // the planning calls take minutes. Clearing first would let a transient model outage delete
        // a course's tree and leave nothing in its place.
        CompetencyTreePlan plan;
        try {
            plan = planFullCompetencyTree(input.course(), input.seeds(), modelOverride, input.languageName());
        } catch (RuntimeException ex) {
            log.warn("Could not synthesise a replacement competency tree for course {}: {}",
                    courseId, errorMessage(ex), ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not synthesise a new competency tree; the existing one was left untouched. "
                            + "Details: " + ExtractionErrors.describe(ex), ex);
        }
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not synthesise a new competency tree; the existing one was left untouched.");
        }

        CompetencyTreeResult result = transactions.execute(status -> {
            Course course = courseRepository.lockOrThrow(courseId);
            // Checked again under the course lock: an instructor may have added to the tree while
            // the replacement was being planned.
            List<LearningGoal> terminals = rejectManualWorkUnlessForced(courseId, force);
            clearCompetencyTree(course, terminals, manualTreeGoals(courseId, terminals));
            persistCompetencyTree(course, plan);
            return new CompetencyTreeResult(plan.competencies().size(), plan.unmatchedGoals());
        });
        log.info("Rebuilt competency tree for course {}: {} competencies, {} unmatched goal(s)",
                courseId, plan.competencies().size(), plan.unmatchedGoals());
        return result;
    }

    /** What the rebuild's read transaction hands to the planning calls. */
    private record RebuildInput(Course course, String languageName, TreeSeeds seeds) {
    }

    /**
     * Refuses a rebuild that would destroy instructor work unless it is forced.
     *
     * @return the course's terminals, which the rebuild replaces.
     */
    private List<LearningGoal> rejectManualWorkUnlessForced(Long courseId, boolean force) {
        List<LearningGoal> terminals = goalRepository.findByCourseIdAndOriginIn(courseId, List.of(GoalOrigin.TERMINAL));
        List<LearningGoal> manual = manualTreeGoals(courseId, terminals);
        if (!manual.isEmpty() && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The competency tree contains " + manual.size() + " hand-made or approved node(s). "
                            + "Rebuilding replaces them; pass force=true to discard them.");
        }
        return terminals;
    }

    /**
     * The tree nodes a rebuild would destroy and cannot recreate: terminals an instructor typed or
     * approved, plus the hand-added and wizard-generated children hanging beneath the tree (those
     * carry a creation provenance and, unlike extracted goals, no hierarchy node of their own).
     */
    private List<LearningGoal> manualTreeGoals(Long courseId, List<LearningGoal> terminals) {
        List<LearningGoal> manual = new ArrayList<>();
        for (LearningGoal terminal : terminals) {
            if (terminal.getCreationProvenance() != null || terminal.getStatus() == GoalStatus.APPROVED) {
                manual.add(terminal);
            }
        }
        for (LearningGoal goal : goalRepository.findByCourseId(courseId)) {
            if (goal.getHierarchyNode() == null && goal.getCreationProvenance() != null) {
                manual.add(goal);
            }
        }
        return manual;
    }

    /**
     * Removes the terminals, the given manual nodes and the tree CONTRIBUTES_TO edges in the course,
     * then the now-empty {@code COMPETENCY} root. Extracted goals keep existing; the rebuild draws
     * their skill → terminal edges again.
     *
     * <p>Knowledge → skill edges are created during extraction and survive a tree rebuild. On a
     * pre-V24 course no goal has a role, so every edge is still deleted — identical to today's
     * behaviour, with no legacy multi-parent stacking. Hand-added ({@code USER_CREATED}) and wizard
     * ({@code WIZARD_AI_SUBTREE}) nodes also have no role, so their edges keep being cleared exactly
     * as today. Do not use "target is a TERMINAL" as the predicate: legacy knowledge → sub-skill
     * edges must not survive.
     */
    private void clearCompetencyTree(Course course, List<LearningGoal> terminals, List<LearningGoal> manual) {
        List<LearningGoal> doomed = new ArrayList<>(terminals);
        for (LearningGoal goal : manual) {
            if (doomed.stream().noneMatch(g -> g.getId().equals(goal.getId()))) {
                doomed.add(goal);
            }
        }
        // Overfull extracted branches create source-less synthesized grouping nodes. They are fully
        // reproducible tree artefacts, not instructor work, so rebuilds remove them without tripping
        // the manual-work guard above.
        for (LearningGoal goal : goalRepository.findByCourseId(course.getId())) {
            boolean consolidatedGroup = goal.getOrigin() == GoalOrigin.SYNTHESIZED
                    && goal.getRole() == GoalRole.SKILL
                    && goal.getHierarchyNode() == null
                    && goal.getCreationProvenance() == null;
            if (consolidatedGroup && doomed.stream().noneMatch(g -> g.getId().equals(goal.getId()))) {
                doomed.add(goal);
            }
        }
        // Load the edges before deleting anything: Hibernate keeps loaded edges managed and would
        // fail the flush against a removed goal.
        List<Long> courseGoalIds = goalRepository.findByCourseId(course.getId()).stream()
                .map(LearningGoal::getId)
                .toList();
        Set<Long> doomedIds = doomed.stream().map(LearningGoal::getId).collect(Collectors.toSet());
        clearRelationshipsForGoals(courseGoalIds, doomedIds, true);
        goalRepository.deleteAll(doomed);
        goalRepository.flush();

        List<HierarchyNode> competencyRoots = hierarchyNodeRepository.findByCourseId(course.getId()).stream()
                .filter(n -> n.getLevel() == HierarchyLevel.COMPETENCY)
                .toList();
        hierarchyNodeRepository.deleteAll(competencyRoots);
        hierarchyNodeRepository.flush();
    }

    /**
     * Removes the course's whole competency tree, instructor work included. A forced re-extraction
     * clears it this way before it replaces the extracted goals.
     */
    public void clearTree(Course course) {
        List<LearningGoal> terminals = goalRepository.findByCourseIdAndOriginIn(
                course.getId(), List.of(GoalOrigin.TERMINAL));
        clearCompetencyTree(course, terminals, manualTreeGoals(course.getId(), terminals));
    }

    /** Deletes loaded relationship entities before deleting any of their goals. */
    public void clearRelationshipsForGoals(Collection<Long> courseGoalIds, Set<Long> doomedIds,
                                            boolean allContributes) {
        List<GoalRelationship> edges = new ArrayList<>();
        for (GoalRelationship relationship : goalRelationshipRepository.findBySourceIdIn(courseGoalIds)) {
            boolean extractionEdge = relationship.getType() == RelationshipType.CONTRIBUTES_TO
                    && relationship.getSource().getRole() == GoalRole.KNOWLEDGE
                    && relationship.getTarget().getRole() == GoalRole.SKILL;
            // Anything synthesis laid down is reproducible and always goes. The type checks below
            // stay for trees built before edges carried that marker: SUPPORTS has no other producer,
            // and a CONTRIBUTES_TO that is not the extraction's own knowledge edge came from a tree.
            boolean treeEdge = allContributes
                    && (relationship.getOrigin() == RelationshipOrigin.SYNTHESIS
                        || relationship.getType() == RelationshipType.SUPPORTS
                        || (relationship.getType() == RelationshipType.CONTRIBUTES_TO && !extractionEdge));
            boolean touchesDoomed = doomedIds.contains(relationship.getSource().getId())
                    || doomedIds.contains(relationship.getTarget().getId());
            if (treeEdge || touchesDoomed) {
                edges.add(relationship);
            }
        }
        // A doomed node may be the target of an edge whose source sits outside the course goal list.
        for (Long doomedId : doomedIds) {
            for (GoalRelationship relationship : goalRelationshipRepository.findByTargetId(doomedId)) {
                if (edges.stream().noneMatch(edge -> edge.getId().equals(relationship.getId()))) {
                    edges.add(relationship);
                }
            }
        }
        if (!edges.isEmpty()) {
            goalRelationshipRepository.deleteAll(edges);
            goalRelationshipRepository.flush();
        }
    }

    /**
     * Builds the competency-tree view ALONGSIDE the module goals (not a replacement) under its own
     * {@code COMPETENCY} root: topic → capability → extracted skill → knowledge, with skills that
     * form no capability directly under their topic.
     *
     * <p>Topics are named, skills assigned and topics structured before anything is written. A failed
     * call therefore leaves the source-backed outcomes untouched and available for a tree-only retry.
     *
     * <p>The goals are read in one transaction, planned with none open, and the tree is written in
     * a second one, separate from extraction persistence.
     */
    public CompetencyTreeResult buildCompetencyTree(Long courseId, String modelOverride,
                                                    String languageName) {
        Optional<TreeInput> input = transactions.execute(status -> {
            Course course = courseRepository.findOrThrow(courseId);
            if (hierarchyNodeRepository.existsByCourseIdAndLevel(courseId, HierarchyLevel.COMPETENCY)) {
                return Optional.empty();
            }
            return Optional.of(new TreeInput(course, loadTreeSeeds(course)));
        });
        if (input == null) {
            throw new IllegalStateException("Competency tree setup completed without a result.");
        }
        if (input.isEmpty()) {
            return CompetencyTreeResult.NONE;
        }
        CompetencyTreePlan plan = planFullCompetencyTree(
                input.get().course(), input.get().seeds(), modelOverride, languageName);
        if (plan == null) {
            return CompetencyTreeResult.NONE;
        }
        CompetencyTreeResult result = transactions.execute(status -> {
            Course course = courseRepository.lockOrThrow(courseId);
            if (hierarchyNodeRepository.existsByCourseIdAndLevel(courseId, HierarchyLevel.COMPETENCY)) {
                return CompetencyTreeResult.NONE;
            }
            persistCompetencyTree(course, plan);
            return new CompetencyTreeResult(plan.competencies().size(), plan.unmatchedGoals());
        });
        if (result == null) {
            throw new IllegalStateException("Competency tree transaction completed without a result.");
        }
        log.info("Built topic competency tree for course {}: {} topics, {} unmatched goal(s)",
                courseId, result.competencies(), result.unmatchedGoals());
        return result;
    }

    private record TreeInput(Course course, TreeSeeds seeds) {
    }

    /**
     * The goals a competency tree is planned from, read while the hierarchy they hang off is still
     * loadable. Planning itself only reads their own columns, so it runs with no transaction open.
     *
     * @param candidates lecture skill-tier outcomes in lecture order; they name the topics.
     * @param exercises  exercise skill-tier outcomes, placed into the finished topics.
     * @param examGoals  exam goals, placed the same way.
     */
    private record TreeSeeds(List<LearningGoal> candidates, List<LearningGoal> exercises,
                             List<LearningGoal> examGoals) {
    }

    private TreeSeeds loadTreeSeeds(Course course) {
        // Only skill-tier session/exercise goals are tree candidates. Role is structural for V24+
        // data, while role-null legacy goals retain the Bloom fallback. Exam goals are role-null
        // too, so the level check keeps them out: they are assigned onto the finished topics later.
        List<LearningGoal> skillTier = goalRepository.findByCourseIdAndHierarchyNodeIsNotNull(course.getId()).stream()
                .filter(g -> g.getHierarchyNode().getLevel() == HierarchyLevel.SESSION
                        || g.getHierarchyNode().getLevel() == HierarchyLevel.EXERCISE)
                .filter(CompetencyTreeService::isSkillTier)
                .sorted(Comparator.comparing(
                                LearningGoal::getLectureOrder,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LearningGoal::getId))
                .toList();
        return new TreeSeeds(
                skillTier.stream().filter(g -> !isExerciseOutcome(g)).toList(),
                skillTier.stream().filter(CompetencyTreeService::isExerciseOutcome).toList(),
                goalRepository.findByCourseIdAndOriginIn(course.getId(), List.of(GoalOrigin.EXAM)));
    }

    /**
     * Runs all synthesis for a course's competency tree and returns the finished plan, or
     * {@code null} when there are no lecture skill seeds. Touches nothing in the database, so it runs
     * with no transaction open and the caller decides when — and whether — to write. Synthesis
     * failures are propagated to the caller.
     *
     * <p>Topics are named, assigned and structured from lecture outcomes only; outcomes of documents
     * without a kind count as lecture outcomes, so existing courses build as before. Exercise
     * material is evidence, not a topic namer: once topics and capabilities stand, exercise outcomes
     * and exam goals are placed into them, under a capability or directly under a topic, so the
     * validated structuring prompt only ever groups lecture outcomes. An exercise outcome no topic
     * covers is practised but not introduced; it is counted and left out, not forced in.
     */
    private CompetencyTreePlan planFullCompetencyTree(Course course, TreeSeeds seeds, String modelOverride,
                                                      String languageName) {
        List<LearningGoal> exercises = seeds.exercises();
        List<LearningGoal> candidates = seeds.candidates();
        // The skill tier IS the seed set. Bloom must not narrow it any further: extraction now
        // decides the tier, and the session prompt deliberately keeps verbs low ("when in doubt,
        // prefer understand/know"), so a course whose skills all classify as UNDERSTAND would
        // otherwise produce no seeds and silently lose its whole competency tree. For role-null
        // legacy goals isSkillTier already means "high Bloom", so their seeds are unchanged.
        if (candidates.isEmpty()) {
            if (!exercises.isEmpty()) {
                log.info("Course {} has {} exercise outcome(s) but no lecture outcome to name topics from; "
                        + "no competency tree is built", course.getId(), exercises.size());
            }
            return null;
        }

        TopicTreeSynthesizer.Plan plan;
        try {
            plan = topicTreeSynthesizer.synthesize(
                    candidates.stream().map(LearningGoal::getText).toList(), languageName, modelOverride);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Topic tree synthesis failed: " + errorMessage(ex), ex);
        }
        if (plan.topics().isEmpty()) {
            throw new IllegalStateException("Topic tree synthesis placed none of the " + candidates.size()
                    + " source outcomes under a topic. Retry the competency tree.");
        }
        // Topics are noun phrases and carry no level of their own; only capability names state a
        // performance, so only they are classified. Every placed outcome keeps its extracted levels.
        List<String> capabilityNames = plan.topics().stream()
                .flatMap(topic -> topic.capabilities().stream())
                .map(TopicTreeSynthesizer.PlannedCapability::name)
                .toList();
        List<TaxonomyClassification> classifications = capabilityNames.isEmpty()
                ? List.of()
                : safeClassifyBatch(capabilityNames, modelOverride);
        // Exercise outcomes and exam goals are placed into the finished tree: under a skill, directly
        // under a topic, or nowhere. They never decide the topics or skills themselves.
        List<TopicTreeSynthesizer.MenuTopic> menu = plan.topics().stream()
                .map(topic -> new TopicTreeSynthesizer.MenuTopic(topic.label(),
                        topic.capabilities().stream().map(TopicTreeSynthesizer.PlannedCapability::name).toList()))
                .toList();
        TreePlacement practised;
        try {
            practised = TreePlacement.place(topicTreeSynthesizer, menu, exercises, modelOverride);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Exercise outcome assignment failed: " + errorMessage(ex), ex);
        }
        List<LearningGoal> notIntroduced = practised.unplaced();
        TreePlacement examined = placeExamGoals(course, menu, seeds.examGoals(), modelOverride);
        List<PlannedCompetency> planned = new ArrayList<>();
        int capabilityIndex = 0;
        int topicIndex = 0;
        for (TopicTreeSynthesizer.PlannedTopic topic : plan.topics()) {
            List<PlannedCapability> capabilities = new ArrayList<>();
            int skillIndex = 0;
            for (TopicTreeSynthesizer.PlannedCapability capability : topic.capabilities()) {
                List<LearningGoal> members = capability.outcomes().stream().map(candidates::get).toList();
                List<LearningGoal> placed = new ArrayList<>(practised.underSkill().get(topicIndex).get(skillIndex));
                placed.addAll(examined.underSkill().get(topicIndex).get(skillIndex++));
                capabilities.add(new PlannedCapability(capability.name(), capability.shortLabel(),
                        atLeastChildLevels(classifications.get(capabilityIndex++),
                                bloomLevels(members), soloLevels(members)),
                        members, placed));
            }
            List<LearningGoal> direct = topic.direct().stream().map(candidates::get).toList();
            List<LearningGoal> placed = new ArrayList<>(practised.underTopic().get(topicIndex));
            placed.addAll(examined.underTopic().get(topicIndex++));
            planned.add(new PlannedCompetency(topic.label(), capabilities, direct, placed));
        }
        planned.sort(Comparator.comparingInt(CompetencyTreeService::medianLectureOrder));
        int unmatched = plan.unmatched().size();
        // An outcome no topic covers is left out of the tree rather than forced under the nearest
        // label. The count is reported so a reviewer can tell how much of the course that is.
        if (unmatched > 0) {
            log.info("Competency tree for course {} leaves {} of {} source outcomes under no topic",
                    course.getId(), unmatched, candidates.size());
        }
        if (!notIntroduced.isEmpty()) {
            log.info("Competency tree for course {}: {} of {} exercise outcomes are practised but not introduced "
                    + "(no lecture topic covers them)", course.getId(), notIntroduced.size(), exercises.size());
        }
        return new CompetencyTreePlan(planned, unmatched + notIntroduced.size());
    }

    /**
     * Places a course's exam goals into the finished tree with the call exercise outcomes go through.
     * Exam goals are evidence about the tree, not part of what builds it, so a failed call leaves them
     * all unplaced instead of failing the tree. An exam goal no topic covers is examined but not
     * taught; it stays under the EXAM root.
     */
    private TreePlacement placeExamGoals(Course course, List<TopicTreeSynthesizer.MenuTopic> menu,
                                         List<LearningGoal> examGoals, String modelOverride) {
        TreePlacement placement;
        try {
            placement = TreePlacement.place(topicTreeSynthesizer, menu, examGoals, modelOverride);
        } catch (RuntimeException ex) {
            log.warn("Exam goal placement failed for course {}, leaving {} exam goal(s) unplaced: {}",
                    course.getId(), examGoals.size(), errorMessage(ex));
            return TreePlacement.empty(menu);
        }
        if (!placement.unplaced().isEmpty()) {
            log.info("Competency tree for course {}: {} of {} exam goals are examined but not taught "
                    + "(no lecture topic covers them)", course.getId(), placement.unplaced().size(), examGoals.size());
        }
        return placement;
    }

    /** An outcome extracted from a document uploaded as an exercise. */
    private static boolean isExerciseOutcome(LearningGoal goal) {
        return goal.getHierarchyNode().getDocument() != null
                && goal.getHierarchyNode().getDocument().getKind() == DocumentKind.EXERCISE;
    }

    /**
     * The finished competency tree, computed entirely in memory so that every LLM call has already
     * succeeded before the first row is written.
     *
     * @param competencies   one entry per terminal competency, in tree order.
     * @param unmatchedGoals source goals no topic covers, left outside the tree: lecture outcomes the
     *                       assignment placed nowhere and exercise outcomes no topic covers. Reported
     *                       to the caller rather than suppressed.
     */
    private record CompetencyTreePlan(List<PlannedCompetency> competencies, int unmatchedGoals) {}

    /**
     * One terminal competency: a topic with the capabilities and outcomes beneath it.
     *
     * A topic is a noun phrase and carries no Bloom or SOLO level.
     *
     * @param text         the topic label.
     * @param capabilities generated capabilities, each over at least two extracted outcomes.
     * @param direct       extracted lecture outcomes that hang directly under the topic.
     * @param placed       exercise outcomes and exam goals placed directly under the topic; they do
     *                     not decide where the topic sits in the course.
     */
    private record PlannedCompetency(String text, List<PlannedCapability> capabilities,
                                     List<LearningGoal> direct, List<LearningGoal> placed) {}

    /**
     * A generated capability: its name, its levels, the lecture outcomes it was built from, and the
     * exercise outcomes and exam goals placed beneath it afterwards. Both its Bloom and its SOLO level
     * are at least the highest among its members; placed goals do not raise them.
     */
    private record PlannedCapability(String text, String shortLabel, TaxonomyClassification classification,
                                     List<LearningGoal> members, List<LearningGoal> placed) {}

    private static List<BloomLevel> bloomLevels(List<LearningGoal> goals) {
        return goals.stream().map(LearningGoal::getBloomLevel).filter(java.util.Objects::nonNull).toList();
    }

    private static List<SoloLevel> soloLevels(List<LearningGoal> goals) {
        return goals.stream().map(LearningGoal::getSoloLevel).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * A capability never sits below the outcomes beneath it.
     *
     * <p>Its levels are classified from generated text, the least reliable thing in the tree to read
     * a level off: a name can be classified by a clause at its tail, or come back below the outcomes
     * it groups, which inverts the tier the tree is built on. The members' levels are the trustworthy
     * half — each was classified during extraction with a source passage behind it — so Bloom is
     * raised to the highest level among them, and SOLO gets the same floor. A capability covers every
     * outcome beneath it, so its structure is at least as complex as the most complex of them. The
     * classified SOLO level is kept when it already reaches that floor, since a capability that
     * relates several simple outcomes can rightly sit above all of them. When classification failed,
     * the floor is the only SOLO level the capability gets.
     */
    public static TaxonomyClassification atLeastChildLevels(TaxonomyClassification classified,
                                                        List<BloomLevel> childBlooms,
                                                        List<SoloLevel> childSolos) {
        BloomLevel bloom = atLeast(classified == null ? null : classified.bloom(), childBlooms);
        SoloLevel solo = atLeast(classified == null ? null : classified.solo(), childSolos);
        if (classified == null ? bloom == null && solo == null
                : bloom == classified.bloom() && solo == classified.solo()) {
            return classified;
        }
        return new TaxonomyClassification(bloom, solo);
    }

    private static <T extends Comparable<T>> T atLeast(T level, List<T> childLevels) {
        T floor = childLevels.stream().max(Comparator.naturalOrder()).orElse(null);
        if (floor == null || (level != null && level.compareTo(floor) >= 0)) {
            return level;
        }
        return floor;
    }

    /**
     * Where a terminal skill sits in the course. The MEDIAN of every source outcome beneath it, not
     * the earliest: a single stray outcome from lecture one would otherwise drag a whole late chapter
     * to the top of the tree.
     */
    private static int medianLectureOrder(PlannedCompetency competency) {
        List<LearningGoal> goals = new ArrayList<>(competency.direct());
        competency.capabilities().forEach(capability -> goals.addAll(capability.members()));
        return medianOrderOf(goals);
    }

    private static int medianOrderOf(List<LearningGoal> goals) {
        List<Integer> orders = goals.stream()
                .map(LearningGoal::getLectureOrder)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        return orders.isEmpty() ? Integer.MAX_VALUE : orders.get(orders.size() / 2);
    }

    /**
     * Writes a planned tree: the {@code COMPETENCY} root, one terminal goal per topic, one generated
     * goal per capability, and the CONTRIBUTES_TO edges capability → topic, skill →
     * capability, exercise or exam goal → capability, and ungrouped, exercise or exam goal → topic. Knowledge → skill edges were created during extraction.
     * All LLM work is already done by the time this runs.
     */
    private void persistCompetencyTree(Course course, CompetencyTreePlan plan) {
        HierarchyNode competencyRoot = hierarchyNodeRepository.findOrCreateCompetencyRoot(course);

        for (PlannedCompetency competency : plan.competencies()) {
            LearningGoal terminal = new LearningGoal(course, competency.text(), GoalKind.IMPLICIT);
            terminal.setShortLabel(competency.text());
            terminal.setOrigin(GoalOrigin.TERMINAL);
            terminal.setHierarchyNode(competencyRoot);
            int medianLectureOrder = medianLectureOrder(competency);
            terminal.setLectureOrder(medianLectureOrder == Integer.MAX_VALUE ? null : medianLectureOrder);
            goalRepository.saveAndFlush(terminal);

            for (PlannedCapability planned : competency.capabilities()) {
                // A generated grouping node without a source of its own. Its members keep their
                // quotes, pages and levels, and each keeps the knowledge extraction hung beneath it.
                LearningGoal capability = new LearningGoal(course, planned.text(), GoalKind.IMPLICIT);
                capability.setOrigin(GoalOrigin.SYNTHESIZED);
                capability.setRole(GoalRole.SKILL);
                capability.setShortLabel(planned.shortLabel());
                int capabilityOrder = medianOrderOf(planned.members());
                capability.setLectureOrder(capabilityOrder == Integer.MAX_VALUE ? null : capabilityOrder);
                applyLevels(capability, planned.classification());
                goalRepository.saveAndFlush(capability);
                linkSynthesized(List.of(capability), terminal, RelationshipType.CONTRIBUTES_TO);
                linkSynthesized(planned.members(), capability, RelationshipType.CONTRIBUTES_TO);
                linkSynthesized(planned.placed(), capability, RelationshipType.CONTRIBUTES_TO);
            }
            linkSynthesized(competency.direct(), terminal, RelationshipType.CONTRIBUTES_TO);
            linkSynthesized(competency.placed(), terminal, RelationshipType.CONTRIBUTES_TO);
        }
    }

    private static void applyLevels(LearningGoal goal, TaxonomyClassification classification) {
        if (classification != null) {
            goal.setBloomLevel(classification.bloom());
            goal.setSoloLevel(classification.solo());
        }
    }

    /**
     * Materializes an edge laid down by tree synthesis. Marked {@link RelationshipOrigin#SYNTHESIS}
     * so a rebuild can delete exactly the edges it created: with elected sub-skills both ends of a
     * tree edge are surviving extracted goals, so nothing else distinguishes it from the extraction
     * edge beside it, and a rebuild would otherwise stack a second copy on every re-run.
     */
    private int linkSynthesized(Collection<LearningGoal> supporters, LearningGoal targetGoal,
                                RelationshipType type) {
        return linkRelationships(supporters, targetGoal, type, RelationshipOrigin.SYNTHESIS);
    }

    /** Materializes idempotent edges between goals. */
    private int linkRelationships(Collection<LearningGoal> supporters, LearningGoal targetGoal,
                                  RelationshipType type, RelationshipOrigin origin) {
        int created = 0;
        for (LearningGoal source : supporters) {
            if (source.getId().equals(targetGoal.getId())) {
                continue;
            }
            if (goalRelationshipRepository.existsBySourceIdAndTargetIdAndType(
                    source.getId(), targetGoal.getId(), type)) {
                continue;
            }
            goalRelationshipRepository.save(new GoalRelationship(
                    source, targetGoal, type, 1.0, origin));
            created++;
        }
        return created;
    }

    /**
     * Classifies one batch, returning a list aligned to {@code texts} (null entries where the model
     * gave no usable level). On failure the whole batch falls back to nulls so the goals still persist
     * without levels, matching the per-goal behaviour.
     */
    private List<TaxonomyClassification> safeClassifyBatch(List<String> texts, String modelOverride) {
        try {
            List<TaxonomyClassification> result = taxonomyService.classifyBatch(texts, modelOverride);
            if (result.size() == texts.size()) {
                return result;
            }
            log.warn("Taxonomy batch returned {} results for {} goals, persisting batch without levels",
                    result.size(), texts.size());
        } catch (RuntimeException ex) {
            log.warn("Taxonomy classification failed for batch, persisting without levels: {}", ex.getMessage());
        }
        return new ArrayList<>(Collections.nCopies(texts.size(), null));
    }

    private static String errorMessage(RuntimeException ex) {
        Throwable cause = ex;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return cause.getClass().getSimpleName();
    }
}
