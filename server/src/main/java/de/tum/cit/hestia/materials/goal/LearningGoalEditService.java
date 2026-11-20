package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalChange;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRelationInfo;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import de.tum.cit.hestia.materials.course.CourseContext;
import de.tum.cit.hestia.materials.course.MaterialSettingsService;
import de.tum.cit.hestia.materials.document.DocumentRepository;
import de.tum.cit.hestia.materials.document.LanguageUtils;
import de.tum.cit.hestia.materials.goal.SubtreeSynthesizer.GeneratedKnowledge;
import de.tum.cit.hestia.materials.goal.SubtreeSynthesizer.GeneratedSkill;
import de.tum.cit.hestia.materials.goal.SubtreeSynthesizer.GeneratedSubSkill;
import de.tum.cit.hestia.materials.goal.SubtreeSynthesizer.GeneratedSubtree;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import de.tum.cit.hestia.materials.taxonomy.SoloLevel;
import de.tum.cit.hestia.materials.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.materials.taxonomy.TaxonomyService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

/**
 * Changes a course's learning goals and its Topic tree: topics typed or generated, children
 * added by hand, goals edited and deleted. The goals live in {@code core}; this service adds their
 * materials placement.
 *
 * <p>Generation calls the model before any transaction opens and stores the result in one
 * transaction under the course lock, so a slow or failed call holds no locks and writes nothing.
 */
@Service
public class LearningGoalEditService {

    private static final Logger log = LoggerFactory.getLogger(LearningGoalEditService.class);

    static final int MAX_GENERATED_TOPIC_CHARS = 500;

    private static final String CONTRIBUTES_TO = RelationshipType.CONTRIBUTES_TO.name();

    private final MaterialSettingsService settingsService;
    private final LearningGoalApi goalApi;
    private final PlacedGoals goals;
    private final DocumentRepository documentRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final TaxonomyService taxonomyService;
    private final SubtreeSynthesizer subtreeSynthesizer;
    private final LearningGoalQueryService queries;
    private final TransactionOperations transactions;

    public LearningGoalEditService(MaterialSettingsService settingsService,
                                   LearningGoalApi goalApi,
                                   PlacedGoals goals,
                                   DocumentRepository documentRepository,
                                   HierarchyNodeRepository hierarchyNodeRepository,
                                   TaxonomyService taxonomyService,
                                   SubtreeSynthesizer subtreeSynthesizer,
                                   LearningGoalQueryService queries,
                                   TransactionOperations transactions) {
        this.settingsService = settingsService;
        this.goalApi = goalApi;
        this.goals = goals;
        this.documentRepository = documentRepository;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
        this.taxonomyService = taxonomyService;
        this.subtreeSynthesizer = subtreeSynthesizer;
        this.queries = queries;
        this.transactions = transactions;
    }

    /**
     * Adds a topic (competency root) exactly as an instructor typed it. It is NOT part of the ordinary
     * pipeline: created directly as an {@code origin=TERMINAL}, {@code status=PENDING} goal with no
     * source snippet, tagged {@code USER_CREATED} so it stays distinguishable from pipeline topics.
     * Nothing is generated beneath it and no model is called: adding by hand stays manual, and
     * generating skills is a choice of its own ({@code /terminal/generated}). Bloom/SOLO stay empty,
     * since a topic carries no level.
     * The goal is attached to the course's COMPETENCY root, reusing it or creating it on first use.
     */
    @Transactional
    public LearningGoalResponse createTerminalSkill(UUID courseId, String requestedText) {
        settingsService.lock(courseId);
        String text = requestedText == null ? "" : requestedText.strip();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Topic text must not be blank");
        }
        goals.rejectDuplicateTopic(courseId, text);

        NewGoal topic = new NewGoal(text, null, GoalKind.IMPLICIT, null, null, null, GoalStatus.PENDING,
                GoalOrigin.TERMINAL, GoalCreationProvenance.USER_CREATED.name(), DomainModule.MATERIALS);
        PlacedGoal created = goals.create(courseId, new NewPlacedGoal(topic,
                hierarchyNodeRepository.findOrCreateCompetencyRoot(courseId), null,
                goals.nextLectureOrder(courseId)));
        return LearningGoalResponse.from(created, List.of(), List.of());
    }

    /**
     * Generates and atomically persists a topic with a complete skill → sub-skill → knowledge subtree,
     * written from the topic's name alone, so no generated node has a source. The model calls run
     * before the transaction that stores the result.
     */
    public LearningGoalResponse createGeneratedTerminalSkill(UUID courseId, String requestedText, String shortLabel) {
        CourseContext course = settingsService.context(courseId);
        String text = requestedText == null ? "" : requestedText.strip();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Topic text must not be blank");
        }
        // The text is the whole prompt input of the generation call.
        if (text.length() > MAX_GENERATED_TOPIC_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Topic text must be at most " + MAX_GENERATED_TOPIC_CHARS + " characters");
        }
        goals.rejectDuplicateTopic(courseId, text);

        GeneratedSubtree generated = SubtreeSynthesizer.validate(
                subtreeSynthesizer.generateSubtree(text,
                        LanguageUtils.courseLanguageName(course, documentRepository.findByCourseId(courseId)), null));
        List<GeneratedNode> nodes = buildGeneratedNodes(generated);
        // The terminal is a topic, a noun phrase with no level of its own; only the nodes beneath it
        // state a performance.
        List<TaxonomyClassification> classifications = classify(nodes);

        return transactions.execute(status -> {
            settingsService.lock(courseId);
            // Checked again under the lock: the same topic may have been added during generation.
            goals.rejectDuplicateTopic(courseId, text);
            HierarchyNode root = hierarchyNodeRepository.findOrCreateCompetencyRoot(courseId);
            int order = goals.nextLectureOrder(courseId);
            List<NewPlacedGoal> items = new ArrayList<>();
            NewGoal terminal = new NewGoal(text, trimToNull(shortLabel), GoalKind.IMPLICIT, null, null, null,
                    GoalStatus.PENDING, GoalOrigin.TERMINAL, GoalCreationProvenance.WIZARD_AI_SUBTREE.name(),
                    DomainModule.MATERIALS);
            items.add(new NewPlacedGoal(terminal, root, null, order++));
            for (int i = 0; i < nodes.size(); i++) {
                GeneratedNode node = nodes.get(i);
                TaxonomyClassification classification = classifications.get(i);
                NewGoal goal = new NewGoal(node.text().strip(), trimToNull(node.shortLabel()), GoalKind.IMPLICIT,
                        node.role(), classification == null ? null : name(classification.bloom()),
                        classification == null ? null : name(classification.solo()), GoalStatus.PENDING,
                        GoalOrigin.SYNTHESIZED, GoalCreationProvenance.WIZARD_AI_SUBTREE.name(),
                        DomainModule.MATERIALS);
                items.add(new NewPlacedGoal(goal, null, null, order++));
            }
            List<PlacedGoal> created = goals.createAll(courseId, items);
            for (int i = 0; i < nodes.size(); i++) {
                int parent = nodes.get(i).parentIndex() + 1; // 0 is the terminal
                goalApi.relate(created.get(i + 1).id(), created.get(parent).id(), CONTRIBUTES_TO, 1.0,
                        RelationshipOrigin.HIERARCHY.name());
            }
            return LearningGoalResponse.from(created.get(0), List.of(), List.of());
        });
    }

    /**
     * Adds one skill, sub-skill or knowledge item an instructor typed, as a {@code USER_CREATED} child
     * that CONTRIBUTES_TO {@code goalId}. Additive by design: it is allowed under extracted goals too,
     * because it destroys nothing. Only the tier is constrained — see {@link #rejectIfKnowledgeTier}.
     * The optional {@code role} states what the child is: a childless node's tier cannot be read off
     * its position, since a skill and a sub-skill both hang directly under a topic.
     * Like a typed skill, it stays unclassified: Bloom/SOLO are the instructor's to set, and skipping
     * the model keeps the add instant.
     */
    @Transactional
    public LearningGoalResponse addChild(UUID courseId, UUID goalId, String requestedText, String shortLabel,
                                         GoalRole role) {
        PlacedGoal parent = goals.require(courseId, goalId);
        String text = requestedText == null ? "" : requestedText.strip();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal text must not be blank");
        }
        rejectIfKnowledgeTier(parent.info());

        settingsService.lock(courseId);
        NewGoal child = new NewGoal(text, trimToNull(shortLabel), GoalKind.IMPLICIT, role, null, null,
                GoalStatus.PENDING, GoalOrigin.SYNTHESIZED, GoalCreationProvenance.USER_CREATED.name(),
                DomainModule.MATERIALS);
        PlacedGoal created = goals.create(courseId,
                new NewPlacedGoal(child, null, null, goals.nextLectureOrder(courseId)));
        goalApi.relate(created.id(), parent.id(), CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY.name());
        return LearningGoalResponse.from(created, List.of(), List.of());
    }

    /**
     * Rejects a parent that sits on the knowledge tier. The Topic tree is capped at four
     * tiers (topic → skill → sub-skill → knowledge), so a child below knowledge would be persisted
     * but never rendered in any view. A parent is eligible when it is a topic, contributes to one, or
     * is a {@link GoalRole#SKILL} beneath a goal that contributes to one. A knowledge goal, and
     * anything outside the Topic tree altogether, cannot take children.
     */
    private void rejectIfKnowledgeTier(GoalInfo parent) {
        if (parent.origin() == GoalOrigin.TERMINAL) {
            return;
        }
        Map<UUID, GoalInfo> byId = goalApi.goals(parent.courseId()).stream()
                .collect(Collectors.toMap(GoalInfo::id, g -> g));
        List<GoalRelationInfo> relationships = goalApi.relationships(parent.courseId());
        boolean eligible = parent.role() != GoalRole.KNOWLEDGE
                && parentsOf(parent, byId, relationships).stream()
                        .anyMatch(above -> above.origin() == GoalOrigin.TERMINAL
                                || (parent.role() == GoalRole.SKILL
                                        && parentsOf(above, byId, relationships).stream()
                                                .anyMatch(top -> top.origin() == GoalOrigin.TERMINAL)));
        if (!eligible) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a topic, a skill or a sub-skill can take children");
        }
    }

    /** The goals {@code goal} CONTRIBUTES_TO, one tier up in the Topic tree. */
    private static List<GoalInfo> parentsOf(GoalInfo goal, Map<UUID, GoalInfo> byId,
                                            List<GoalRelationInfo> relationships) {
        return relationships.stream()
                .filter(r -> r.sourceGoalId().equals(goal.id()) && CONTRIBUTES_TO.equals(r.type()))
                .map(r -> byId.get(r.targetGoalId()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Flattens a generated subtree into nodes in tree order. Every node states its role: a childless
     * generated node's tier could not be read off its position otherwise. {@code parentIndex} is the
     * index of the parent node in the list, -1 for the topic the subtree hangs under.
     */
    private List<GeneratedNode> buildGeneratedNodes(GeneratedSubtree generated) {
        List<GeneratedNode> nodes = new ArrayList<>();
        for (GeneratedSkill generatedSkill : generated.skills()) {
            int skillIndex = nodes.size();
            nodes.add(new GeneratedNode(generatedSkill.text(), generatedSkill.shortLabel(), GoalRole.SKILL, -1));
            for (GeneratedSubSkill generatedSubSkill : generatedSkill.subSkills()) {
                int subSkillIndex = nodes.size();
                nodes.add(new GeneratedNode(generatedSubSkill.text(), generatedSubSkill.shortLabel(),
                        GoalRole.SKILL, skillIndex));
                for (GeneratedKnowledge generatedKnowledge : generatedSubSkill.knowledge()) {
                    nodes.add(new GeneratedNode(generatedKnowledge.text(), generatedKnowledge.shortLabel(),
                            GoalRole.KNOWLEDGE, subSkillIndex));
                }
            }
        }
        return nodes;
    }

    /** One node of a generated subtree; see {@link #buildGeneratedNodes}. */
    private record GeneratedNode(String text, String shortLabel, GoalRole role, int parentIndex) {
    }

    /** The levels for the nodes, aligned to them; null entries where there is none. */
    private List<TaxonomyClassification> classify(List<GeneratedNode> nodes) {
        List<TaxonomyClassification> none = new ArrayList<>(java.util.Collections.nCopies(nodes.size(), null));
        List<String> texts = nodes.stream().map(GeneratedNode::text).toList();
        try {
            List<TaxonomyClassification> classifications = taxonomyService.classifyBatch(texts, null);
            if (classifications == null || classifications.size() != nodes.size()) {
                log.warn("Taxonomy batch returned {} results for {} generated goals; storing them without levels",
                        classifications == null ? 0 : classifications.size(), nodes.size());
                return none;
            }
            return new ArrayList<>(classifications);
        } catch (RuntimeException ex) {
            // Classification is best-effort; the generated subtree must still be persisted.
            log.warn("Taxonomy classification failed for {} generated goals; storing them without levels: {}",
                    nodes.size(), ex.getMessage());
            return none;
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    @Transactional
    public LearningGoalResponse update(UUID courseId, UUID goalId, String requestedText, GoalStatus status,
                                       BloomLevel bloomLevel, SoloLevel soloLevel) {
        PlacedGoal goal = goals.require(courseId, goalId);
        GoalChange change = goal.info().asChange();
        if (requestedText != null) {
            String text = requestedText.strip();
            if (text.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal text must not be blank");
            }
            if (!text.equals(goal.text())) {
                // The generated wording stays as version 1 of the goal ({@code originalText}).
                change = change.withText(text).withShortLabel(null);
            }
        }
        if (bloomLevel != null) {
            change = change.withBloomLevel(bloomLevel.name());
        }
        if (soloLevel != null) {
            change = change.withSoloLevel(soloLevel.name());
        }
        goalApi.update(goalId, change);
        if (status != null) {
            goalApi.setStatus(goalId, status);
        }
        return queries.toResponse(goals.require(courseId, goalId));
    }

    @Transactional
    public void delete(UUID courseId, UUID goalId) {
        PlacedGoal goal = goals.require(courseId, goalId);
        goals.removeAll(subtreeToRemove(goal));
    }

    /**
     * DELETE removes everything beneath the goal in the Topic tree: generated, hand-added and
     * extracted goals alike. Dismissing a goal rejects it, and a rejected goal is removed (see
     * {@link GoalStatus}), so nothing beneath a dismissed goal lingers outside the tree, where the
     * instructor can no longer see it but other consumers of the API still would. A descendant that
     * also contributes to a goal outside the removed subtree keeps that parent and stays, with
     * everything beneath it.
     *
     * @return the ids of the goal and the descendants that go with it.
     */
    private Set<UUID> subtreeToRemove(PlacedGoal root) {
        List<GoalRelationInfo> contributes = goalApi.relationships(root.courseId()).stream()
                .filter(r -> CONTRIBUTES_TO.equals(r.type()))
                .toList();
        Map<UUID, List<UUID>> childrenOf = new HashMap<>();
        Map<UUID, List<UUID>> parentsOf = new HashMap<>();
        for (GoalRelationInfo r : contributes) {
            childrenOf.computeIfAbsent(r.targetGoalId(), k -> new ArrayList<>()).add(r.sourceGoalId());
            parentsOf.computeIfAbsent(r.sourceGoalId(), k -> new ArrayList<>()).add(r.targetGoalId());
        }
        Set<UUID> reachable = new java.util.LinkedHashSet<>();
        Deque<UUID> pending = new ArrayDeque<>();
        pending.add(root.id());
        while (!pending.isEmpty()) {
            UUID target = pending.removeFirst();
            for (UUID source : childrenOf.getOrDefault(target, List.of())) {
                if (!source.equals(root.id()) && reachable.add(source)) {
                    pending.addLast(source);
                }
            }
        }

        // Spare every descendant with a parent outside the removed set, repeating until nothing changes:
        // sparing one goal gives its own children a surviving parent too.
        Set<UUID> doomed = new HashSet<>(reachable);
        doomed.add(root.id());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (UUID id : reachable) {
                if (doomed.contains(id)
                        && parentsOf.getOrDefault(id, List.of()).stream().anyMatch(parent -> !doomed.contains(parent))) {
                    doomed.remove(id);
                    changed = true;
                }
            }
        }
        return doomed;
    }
}
