package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.LanguageUtils;
import de.tum.cit.hestia.learninggoalhub.extraction.SkillSuggestionSynthesizer;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedKnowledge;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSkill;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSubSkill;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSubtree;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import de.tum.cit.hestia.learninggoalhub.taxonomy.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.taxonomy.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

/**
 * Changes a course's learning goals and its competency tree: topics typed or generated, children
 * added by hand, subtrees regenerated, goals edited and deleted.
 *
 * <p>Generation calls the model before any transaction opens and stores the result in one
 * transaction under the course lock, so a slow or failed call holds no locks and writes nothing.
 */
@Service
public class LearningGoalEditService {

    private static final Logger log = LoggerFactory.getLogger(LearningGoalEditService.class);

    static final int MAX_GENERATED_TOPIC_CHARS = 500;

    private final CourseRepository courseRepository;
    private final LearningGoalRepository goalRepository;
    private final GoalSourceRepository goalSourceRepository;
    private final DocumentRepository documentRepository;
    private final GoalRelationshipRepository goalRelationshipRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final TaxonomyService taxonomyService;
    private final SkillSuggestionSynthesizer skillSuggestionSynthesizer;
    private final SubtreeSynthesizer subtreeSynthesizer;
    private final LearningGoalQueryService queries;
    private final TransactionOperations transactions;

    public LearningGoalEditService(CourseRepository courseRepository,
                                   LearningGoalRepository goalRepository,
                                   GoalSourceRepository goalSourceRepository,
                                   DocumentRepository documentRepository,
                                   GoalRelationshipRepository goalRelationshipRepository,
                                   HierarchyNodeRepository hierarchyNodeRepository,
                                   TaxonomyService taxonomyService,
                                   SkillSuggestionSynthesizer skillSuggestionSynthesizer,
                                   SubtreeSynthesizer subtreeSynthesizer,
                                   LearningGoalQueryService queries,
                                   TransactionOperations transactions) {
        this.courseRepository = courseRepository;
        this.goalRepository = goalRepository;
        this.goalSourceRepository = goalSourceRepository;
        this.documentRepository = documentRepository;
        this.goalRelationshipRepository = goalRelationshipRepository;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
        this.taxonomyService = taxonomyService;
        this.skillSuggestionSynthesizer = skillSuggestionSynthesizer;
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
     * No embedding is computed, matching the pipeline's terminal competencies.
     * The goal is attached to the course's COMPETENCY root, reusing it or creating it on first use.
     */
    @Transactional
    public LearningGoalResponse createTerminalSkill(Long courseId, String requestedText) {
        Course course = courseRepository.lockOrThrow(courseId);
        String text = requestedText == null ? "" : requestedText.strip();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill text must not be blank");
        }
        goalRepository.rejectDuplicateTopic(courseId, text);

        LearningGoal goal = new LearningGoal(course, text, GoalKind.IMPLICIT);
        goal.setOrigin(GoalOrigin.TERMINAL);
        goal.setStatus(GoalStatus.PENDING);
        goal.setCreationProvenance(GoalCreationProvenance.USER_CREATED);
        goal.setHierarchyNode(hierarchyNodeRepository.findOrCreateCompetencyRoot(course));
        goal.setLectureOrder(nextLectureOrder(course));
        goalRepository.save(goal);
        return LearningGoalResponse.from(goal, List.of(), List.of());
    }

    /** Returns transient AI suggestions grounded in the already extracted course goals. */
    @Transactional(readOnly = true)
    public List<SkillSuggestionSynthesizer.Suggestion> suggestTerminalSkills(Long courseId) {
        Course course = courseRepository.findOrThrow(courseId);
        List<LearningGoal> extractedGoals = goalRepository
                .findByCourseIdAndOriginIn(courseId, List.of(GoalOrigin.EXTRACTED)).stream()
                .filter(goal -> goal.getHierarchyNode() != null
                        && (goal.getHierarchyNode().getLevel() == HierarchyLevel.SESSION
                        || goal.getHierarchyNode().getLevel() == HierarchyLevel.EXERCISE))
                .toList();
        if (extractedGoals.isEmpty()) {
            return List.of();
        }

        List<Long> goalIds = extractedGoals.stream().map(LearningGoal::getId).toList();
        Map<Long, List<GoalSource>> sourcesByGoal = goalSourceRepository.findByGoalIdIn(goalIds).stream()
                .collect(Collectors.groupingBy(source -> source.getGoal().getId()));
        List<String> existingTerminals = goalRepository
                .findByCourseIdAndOriginIn(courseId, List.of(GoalOrigin.TERMINAL)).stream()
                .map(LearningGoal::getText)
                .toList();
        List<SkillSuggestionSynthesizer.Evidence> evidence = extractedGoals.stream()
                .map(goal -> new SkillSuggestionSynthesizer.Evidence(
                        goal.getText(),
                        goal.getBloomLevel() == null ? null : goal.getBloomLevel().name(),
                        sourceSnippet(sourcesByGoal.getOrDefault(goal.getId(), List.of()))))
                .toList();

        List<SkillSuggestionSynthesizer.Suggestion> suggestions = skillSuggestionSynthesizer.suggest(
                existingTerminals, evidence, LanguageUtils.courseLanguageName(course, documentRepository.findByCourseId(course.getId())), null);
        return suggestions == null ? List.of() : suggestions.stream()
                .filter(suggestion -> suggestion != null && suggestion.text() != null && !suggestion.text().isBlank())
                .toList();
    }

    /**
     * Generates and atomically persists a topic with a complete skill → sub-skill → knowledge subtree,
     * written from the topic's name alone, so no generated node has a source. The model calls run
     * before the transaction that stores the result.
     */
    public LearningGoalResponse createGeneratedTerminalSkill(Long courseId, String requestedText, String shortLabel) {
        Course course = courseRepository.findOrThrow(courseId);
        String text = requestedText == null ? "" : requestedText.strip();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill text must not be blank");
        }
        // The text is the whole prompt input of the generation call.
        if (text.length() > MAX_GENERATED_TOPIC_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Skill text must be at most " + MAX_GENERATED_TOPIC_CHARS + " characters");
        }
        goalRepository.rejectDuplicateTopic(courseId, text);

        GeneratedSubtree generated = SubtreeSynthesizer.validate(
                subtreeSynthesizer.generateSubtree(text, LanguageUtils.courseLanguageName(course, documentRepository.findByCourseId(course.getId())), null));
        GeneratedNodes generatedNodes = buildGeneratedNodes(course, generated);
        // The terminal is a topic, a noun phrase with no level of its own; only the nodes beneath it
        // state a performance.
        applyClassifications(generatedNodes.nodes());

        return transactions.execute(status -> {
            Course locked = courseRepository.lockOrThrow(courseId);
            // Checked again under the lock: the same topic may have been added during generation.
            goalRepository.rejectDuplicateTopic(courseId, text);
            LearningGoal created = newGeneratedGoal(locked, text, GoalOrigin.TERMINAL);
            created.setShortLabel(trimToNull(shortLabel));
            created.setHierarchyNode(hierarchyNodeRepository.findOrCreateCompetencyRoot(locked));
            persistGeneratedNodes(created, generatedNodes);
            return LearningGoalResponse.from(created, List.of(), List.of());
        });
    }

    /**
     * Generates a new subtree for an existing terminal, replacing only its owned AI descendants.
     *
     * <p>Refused for a terminal the pipeline clustered (no creation provenance): its structure was
     * extracted alongside it, so there is nothing owned to replace and the generated nodes would
     * simply hang next to goals that carry source quotes — an ungrounded branch in a grounded tree.
     * A hand-typed or wizard-generated terminal may well have picked up extracted contributors along
     * the way; those survive regeneration untouched, which is the intended mix.
     */
    public LearningGoalResponse generateSubtree(Long courseId, Long goalId) {
        LearningGoal terminal = findGoal(courseId, goalId);
        if (terminal.getOrigin() != GoalOrigin.TERMINAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only terminal skills can generate a subtree");
        }
        if (terminal.getCreationProvenance() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A skill extracted from the course material cannot be regenerated");
        }

        // Read outside a transaction, the terminal's lazy course cannot be followed.
        Course course = courseRepository.findOrThrow(courseId);
        GeneratedSubtree generated = SubtreeSynthesizer.validate(
                subtreeSynthesizer.generateSubtree(terminal.getText(), LanguageUtils.courseLanguageName(course, documentRepository.findByCourseId(course.getId())), null));
        GeneratedNodes generatedNodes = buildGeneratedNodes(course, generated);
        applyClassifications(generatedNodes.nodes());

        return transactions.execute(status -> {
            courseRepository.lockOrThrow(courseId);
            LearningGoal current = findGoal(courseId, goalId);
            deleteWizardGeneratedDescendants(current);
            goalRepository.flush();
            persistGeneratedNodes(current, generatedNodes);
            // The terminal was still created by hand; its provenance describes the node, not its children.
            return LearningGoalResponse.from(current, List.of(), List.of());
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
    public LearningGoalResponse addChild(Long courseId, Long goalId, String requestedText, String shortLabel,
                                         GoalRole role) {
        LearningGoal parent = findGoal(courseId, goalId);
        String text = requestedText == null ? "" : requestedText.strip();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal text must not be blank");
        }
        rejectIfKnowledgeTier(parent);

        Course course = courseRepository.lockOrThrow(courseId);
        LearningGoal child = newUserCreatedChild(course, text);
        child.setShortLabel(trimToNull(shortLabel));
        child.setRole(role);
        child.setLectureOrder(nextLectureOrder(course));
        goalRepository.save(child);
        goalRepository.flush();
        linkContributors(List.of(child), parent);
        return LearningGoalResponse.from(child, List.of(), List.of());
    }

    /**
     * Rejects a parent that sits on the knowledge tier. The competency forest is capped at four
     * tiers (topic → skill → sub-skill → knowledge), so a child below knowledge would be persisted
     * but never rendered in any view. A parent is eligible when it is a topic, contributes to one, or
     * is a {@link GoalRole#SKILL} beneath a goal that contributes to one. A knowledge goal, and
     * anything outside the competency tree altogether, cannot take children.
     */
    private void rejectIfKnowledgeTier(LearningGoal parent) {
        if (parent.getOrigin() == GoalOrigin.TERMINAL) {
            return;
        }
        boolean eligible = parent.getRole() != GoalRole.KNOWLEDGE
                && parentsOf(parent).stream().anyMatch(above -> above.getOrigin() == GoalOrigin.TERMINAL
                        || (parent.getRole() == GoalRole.SKILL && parentsOf(above).stream()
                                .anyMatch(top -> top.getOrigin() == GoalOrigin.TERMINAL)));
        if (!eligible) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a topic, a skill or a sub-skill can take children");
        }
    }

    /** The goals {@code goal} CONTRIBUTES_TO, one tier up in the competency tree. */
    private List<LearningGoal> parentsOf(LearningGoal goal) {
        return goalRelationshipRepository.findBySourceId(goal.getId()).stream()
                .filter(relationship -> relationship.getType() == RelationshipType.CONTRIBUTES_TO)
                .map(relationship -> relationship.getTarget())
                .toList();
    }

    /**
     * Turns a generated subtree into unsaved goals and the edges between them. Every node states its
     * role: a childless generated node's tier could not be read off its position otherwise.
     */
    private GeneratedNodes buildGeneratedNodes(Course course, GeneratedSubtree generated) {
        List<LearningGoal> nodes = new ArrayList<>();
        List<GeneratedEdge> edges = new ArrayList<>();
        for (GeneratedSkill generatedSkill : generated.skills()) {
            LearningGoal skill = newGeneratedNode(course, generatedSkill.text(), generatedSkill.shortLabel(),
                    GoalRole.SKILL);
            nodes.add(skill);
            edges.add(new GeneratedEdge(skill, null));
            for (GeneratedSubSkill generatedSubSkill : generatedSkill.subSkills()) {
                LearningGoal subSkill = newGeneratedNode(course, generatedSubSkill.text(),
                        generatedSubSkill.shortLabel(), GoalRole.SKILL);
                nodes.add(subSkill);
                edges.add(new GeneratedEdge(subSkill, skill));
                for (GeneratedKnowledge generatedKnowledge : generatedSubSkill.knowledge()) {
                    LearningGoal knowledge = newGeneratedNode(course, generatedKnowledge.text(),
                            generatedKnowledge.shortLabel(), GoalRole.KNOWLEDGE);
                    nodes.add(knowledge);
                    edges.add(new GeneratedEdge(knowledge, subSkill));
                }
            }
        }
        return new GeneratedNodes(nodes, edges);
    }

    private LearningGoal newGeneratedNode(Course course, String text, String shortLabel, GoalRole role) {
        LearningGoal goal = newGeneratedGoal(course, text, GoalOrigin.SYNTHESIZED);
        goal.setShortLabel(trimToNull(shortLabel));
        goal.setRole(role);
        return goal;
    }

    private List<LearningGoal> allNodes(LearningGoal terminal, GeneratedNodes generatedNodes) {
        List<LearningGoal> nodes = new ArrayList<>(generatedNodes.nodes());
        nodes.add(0, terminal);
        return nodes;
    }

    private void persistGeneratedNodes(LearningGoal terminal, GeneratedNodes generatedNodes) {
        int nextOrder = nextLectureOrder(terminal.getCourse());
        if (terminal.getLectureOrder() == null) {
            terminal.setLectureOrder(nextOrder++);
        }
        for (LearningGoal node : generatedNodes.nodes()) {
            if (node.getLectureOrder() == null) {
                node.setLectureOrder(nextOrder++);
            }
        }
        goalRepository.saveAll(allNodes(terminal, generatedNodes));
        goalRepository.flush();
        for (GeneratedEdge edge : generatedNodes.edges()) {
            linkContributors(List.of(edge.child()), edge.parent() == null ? terminal : edge.parent());
        }
    }

    /** Generated goals in tree order, and the CONTRIBUTES_TO edges between them. */
    private record GeneratedNodes(List<LearningGoal> nodes, List<GeneratedEdge> edges) {
    }

    /** One generated edge; a null parent stands for the topic the subtree hangs under. */
    private record GeneratedEdge(LearningGoal child, LearningGoal parent) {
    }

    private int nextLectureOrder(Course course) {
        return goalRepository.findByCourseId(course.getId()).stream()
                .map(LearningGoal::getLectureOrder)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1) + 1;
    }

    private LearningGoal newGeneratedGoal(Course course, String text, GoalOrigin origin) {
        LearningGoal goal = new LearningGoal(course, text.strip(), GoalKind.IMPLICIT);
        goal.setOrigin(origin);
        goal.setStatus(GoalStatus.PENDING);
        goal.setCreationProvenance(GoalCreationProvenance.WIZARD_AI_SUBTREE);
        return goal;
    }

    private LearningGoal newUserCreatedChild(Course course, String text) {
        LearningGoal goal = new LearningGoal(course, text, GoalKind.IMPLICIT);
        goal.setOrigin(GoalOrigin.SYNTHESIZED);
        goal.setStatus(GoalStatus.PENDING);
        goal.setCreationProvenance(GoalCreationProvenance.USER_CREATED);
        return goal;
    }

    private void applyClassifications(List<LearningGoal> nodes) {
        List<String> texts = nodes.stream().map(LearningGoal::getText).toList();
        try {
            List<TaxonomyClassification> classifications = taxonomyService.classifyBatch(texts, null);
            if (classifications == null || classifications.size() != nodes.size()) {
                log.warn("Taxonomy batch returned {} results for {} generated goals; storing them without levels",
                        classifications == null ? 0 : classifications.size(), nodes.size());
                return;
            }
            for (int i = 0; i < nodes.size(); i++) {
                TaxonomyClassification classification = classifications.get(i);
                if (classification != null) {
                    nodes.get(i).setBloomLevel(classification.bloom());
                    nodes.get(i).setSoloLevel(classification.solo());
                }
            }
        } catch (RuntimeException ex) {
            // Classification is best-effort; the generated subtree must still be persisted.
            log.warn("Taxonomy classification failed for {} generated goals; storing them without levels: {}",
                    nodes.size(), ex.getMessage());
        }
    }

    private int linkContributors(Collection<LearningGoal> supporters, LearningGoal targetGoal) {
        int created = 0;
        for (LearningGoal source : supporters) {
            if (source.getId().equals(targetGoal.getId())) {
                continue;
            }
            if (goalRelationshipRepository.existsBySourceIdAndTargetIdAndType(
                    source.getId(), targetGoal.getId(), RelationshipType.CONTRIBUTES_TO)) {
                continue;
            }
            goalRelationshipRepository.save(new GoalRelationship(
                    source, targetGoal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
            created++;
        }
        return created;
    }

    private String sourceSnippet(List<GoalSource> sources) {
        return sources.stream()
                .map(GoalSource::getSnippet)
                .filter(snippet -> snippet != null && !snippet.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(" | "));
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    @Transactional
    public LearningGoalResponse update(Long courseId, Long goalId, String requestedText, GoalStatus status,
                                       BloomLevel bloomLevel, SoloLevel soloLevel) {
        LearningGoal goal = findGoal(courseId, goalId);
        if (requestedText != null) {
            String text = requestedText.strip();
            if (text.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal text must not be blank");
            }
            if (!text.equals(goal.getText())) {
                // Keep the generated wording on the first rename. An instructor's own goal has no
                // generated wording to keep.
                if (goal.getOriginalText() == null
                        && goal.getCreationProvenance() != GoalCreationProvenance.USER_CREATED) {
                    goal.setOriginalText(goal.getText());
                }
                goal.setText(text);
                goal.setShortLabel(null);
            }
        }
        if (status != null) {
            goal.setStatus(status);
        }
        if (bloomLevel != null) {
            goal.setBloomLevel(bloomLevel);
        }
        if (soloLevel != null) {
            goal.setSoloLevel(soloLevel);
        }
        return queries.toResponse(goal);
    }

    @Transactional
    public void delete(Long courseId, Long goalId) {
        // The DB cascades the delete to goal_source rows and to relationships in both directions.
        LearningGoal goal = findGoal(courseId, goalId);
        deleteSubtree(goal);
        goalRepository.delete(goal);
    }

    /** Regeneration owns only the descendants created by the AI subtree wizard; the root survives. */
    private void deleteWizardGeneratedDescendants(LearningGoal root) {
        deleteDescendants(root, Set.of(GoalCreationProvenance.WIZARD_AI_SUBTREE));
    }

    /**
     * DELETE removes everything beneath the goal in the competency tree: generated, hand-added and
     * extracted goals alike. Dismissing a goal rejects it, and a rejected goal is deleted (see
     * {@link GoalStatus}), so nothing beneath a dismissed goal lingers outside the tree, where the
     * instructor can no longer see it but other consumers of the API still would. A descendant that
     * also contributes to a goal outside the removed subtree keeps that parent and stays, with
     * everything beneath it.
     */
    private void deleteSubtree(LearningGoal root) {
        Map<Long, LearningGoal> reachable = new LinkedHashMap<>();
        Map<Long, List<Long>> parentIds = new HashMap<>();
        Map<Long, GoalRelationship> loadedEdges = new LinkedHashMap<>();
        Deque<LearningGoal> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            LearningGoal target = pending.removeFirst();
            for (GoalRelationship relationship : goalRelationshipRepository.findByTargetId(target.getId())) {
                loadedEdges.put(relationship.getId(), relationship);
                LearningGoal source = relationship.getSource();
                if (relationship.getType() != RelationshipType.CONTRIBUTES_TO
                        || source.getId().equals(root.getId())
                        || reachable.putIfAbsent(source.getId(), source) != null) {
                    continue;
                }
                List<Long> parents = new ArrayList<>();
                for (GoalRelationship outgoing : goalRelationshipRepository.findBySourceId(source.getId())) {
                    loadedEdges.put(outgoing.getId(), outgoing);
                    if (outgoing.getType() == RelationshipType.CONTRIBUTES_TO) {
                        parents.add(outgoing.getTarget().getId());
                    }
                }
                parentIds.put(source.getId(), parents);
                pending.addLast(source);
            }
        }

        // Spare every descendant with a parent outside the removed set, repeating until nothing changes:
        // sparing one goal gives its own children a surviving parent too.
        Set<Long> doomed = new HashSet<>(reachable.keySet());
        doomed.add(root.getId());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Long id : reachable.keySet()) {
                if (doomed.contains(id) && parentIds.get(id).stream().anyMatch(parent -> !doomed.contains(parent))) {
                    doomed.remove(id);
                    changed = true;
                }
            }
        }

        // Delete the loaded edge entities first: they are managed in the persistence context, so
        // deleting the goals they reference while they linger would fail the flush with a
        // TransientObjectException.
        goalRelationshipRepository.deleteAll(loadedEdges.values().stream()
                .filter(edge -> doomed.contains(edge.getSource().getId())
                        || doomed.contains(edge.getTarget().getId()))
                .toList());
        goalRelationshipRepository.flush();
        List<LearningGoal> descendants = new ArrayList<>(reachable.values());
        for (int i = descendants.size() - 1; i >= 0; i--) {
            if (doomed.contains(descendants.get(i).getId())) {
                goalRepository.delete(descendants.get(i));
            }
        }
    }

    /**
     * Collects and removes the descendants of {@code root} whose creation provenance marks them as
     * owned, together with every edge from them. The root survives, and manual or extracted
     * contributors stay attached to it.
     */
    private void deleteDescendants(LearningGoal root, Set<GoalCreationProvenance> ownedProvenances) {
        List<LearningGoal> descendants = new ArrayList<>();
        List<GoalRelationship> loadedEdges = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<LearningGoal> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            LearningGoal target = pending.removeFirst();
            boolean targetDeleted = !target.getId().equals(root.getId());
            for (GoalRelationship relationship : goalRelationshipRepository.findByTargetId(target.getId())) {
                // A null provenance marks a pipeline or extracted goal, which regeneration never owns.
                // Test it explicitly: ownedProvenances is a Set.of(...), and those throw on contains(null).
                GoalCreationProvenance provenance = relationship.getSource().getCreationProvenance();
                boolean owned = relationship.getType() == RelationshipType.CONTRIBUTES_TO
                        && provenance != null && ownedProvenances.contains(provenance);
                // An edge dies with either endpoint: its source is an owned goal we delete, or its
                // target is going away. Anything else stays and keeps its surviving goal attached.
                if (owned || targetDeleted) {
                    loadedEdges.add(relationship);
                }
                if (!owned) {
                    continue;
                }
                LearningGoal source = relationship.getSource();
                if (visited.add(source.getId())) {
                    descendants.add(source);
                    pending.addLast(source);
                }
            }
        }
        // Delete the loaded edge entities first: they are managed in the persistence context, so
        // deleting the goals they reference while they linger would fail the flush with a
        // TransientObjectException. Leaving a skipped edge to the DB cascade is not enough — once
        // loaded, Hibernate still holds it.
        goalRelationshipRepository.deleteAll(loadedEdges);
        goalRelationshipRepository.flush();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            goalRepository.delete(descendants.get(i));
        }
    }

    private LearningGoal findGoal(Long courseId, Long goalId) {
        return goalRepository.findByIdAndCourseId(goalId, courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Learning goal " + goalId + " not found in course " + courseId));
    }
}
