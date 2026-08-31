package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.extraction.SourceMatchQuality;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.HighlightRect;
import de.tum.cit.hestia.learninggoalhub.document.LanguageUtils;
import de.tum.cit.hestia.learninggoalhub.document.PageDescription;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.extraction.SkillSuggestionSynthesizer;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedKnowledge;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSubSkill;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSubtree;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyPath;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/courses/{courseId}/learning-goals")
public class LearningGoalController {

    private final CourseRepository courseRepository;
    private final LearningGoalRepository goalRepository;
    private final GoalSourceRepository goalSourceRepository;
    private final DocumentContentRepository documentContentRepository;
    private final PageDescriptionRepository pageDescriptionRepository;
    private final DocumentRepository documentRepository;
    private final GoalRelationshipRepository goalRelationshipRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final TaxonomyService taxonomyService;
    private final SkillSuggestionSynthesizer skillSuggestionSynthesizer;
    private final SubtreeSynthesizer subtreeSynthesizer;
    private final LearningGoalCsvWriter csvWriter;

    public LearningGoalController(CourseRepository courseRepository,
                                  LearningGoalRepository goalRepository,
                                  GoalSourceRepository goalSourceRepository,
                                  DocumentContentRepository documentContentRepository,
                                  PageDescriptionRepository pageDescriptionRepository,
                                  DocumentRepository documentRepository,
                                  GoalRelationshipRepository goalRelationshipRepository,
                                  HierarchyNodeRepository hierarchyNodeRepository,
                                  TaxonomyService taxonomyService,
                                  SkillSuggestionSynthesizer skillSuggestionSynthesizer,
                                  SubtreeSynthesizer subtreeSynthesizer,
                                  LearningGoalCsvWriter csvWriter) {
        this.courseRepository = courseRepository;
        this.goalRepository = goalRepository;
        this.goalSourceRepository = goalSourceRepository;
        this.documentContentRepository = documentContentRepository;
        this.pageDescriptionRepository = pageDescriptionRepository;
        this.documentRepository = documentRepository;
        this.goalRelationshipRepository = goalRelationshipRepository;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
        this.taxonomyService = taxonomyService;
        this.skillSuggestionSynthesizer = skillSuggestionSynthesizer;
        this.subtreeSynthesizer = subtreeSynthesizer;
        this.csvWriter = csvWriter;
    }

    /** Label of the COMPETENCY hierarchy root; mirrors the one the extraction pipeline creates. */
    static final String COMPETENCY_ROOT_LABEL = "Terminal Competencies";

    @GetMapping
    @Transactional(readOnly = true)
    public PagedModel<LearningGoalResponse> list(@PathVariable Long courseId,
                                                 @RequestParam(required = false) GoalStatus status,
                                                 @Parameter(description = "Optional extraction-tier filter: SKILL or KNOWLEDGE. Omit for all goals.")
                                                 @RequestParam(required = false) GoalRole role,
                                                 @ParameterObject @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.ASC)
                                                 Pageable pageable) {
        if (!courseRepository.existsById(courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId);
        }

        Page<LearningGoal> page;
        if (status == null && role == null) {
            page = goalRepository.findByCourseId(courseId, pageable);
        } else if (status == null) {
            page = goalRepository.findByCourseIdAndRole(courseId, role, pageable);
        } else if (role == null) {
            page = goalRepository.findByCourseIdAndStatus(courseId, status, pageable);
        } else {
            page = goalRepository.findByCourseIdAndStatusAndRole(courseId, status, role, pageable);
        }
        List<Long> goalIds = page.getContent().stream().map(LearningGoal::getId).toList();
        Map<Long, List<GoalSourceResponse>> sourcesByGoal = sourcesByGoal(goalIds);
        Map<Long, List<GoalRelationshipResponse>> relationshipsByGoal = relationshipsByGoal(goalIds);

        // PagedModel serializes a stable {content, page} JSON shape, unlike PageImpl whose format
        // is an implementation detail that has changed across Spring Data versions.
        return new PagedModel<>(page.map(g -> LearningGoalResponse.from(
                g,
                sourcesByGoal.getOrDefault(g.getId(), List.of()),
                relationshipsByGoal.getOrDefault(g.getId(), List.of()))));
    }

    /**
     * Goals grouped by the hierarchy node (module/session/exercise) they belong to, for API
     * consumers that need per-session granularity. Groups follow node creation order — the module
     * root first, then sessions/exercises in document order; goals without a hierarchy node come
     * last in a group whose {@code nodeId}, {@code level} and {@code label} are {@code null}.
     * Nodes without any (matching) goals are omitted.
     *
     * <p>An optional {@code nodeId} narrows the result to the goals of that single hierarchy node
     * (so consumers can fetch one session without loading the whole course); the node-less bucket is
     * excluded, and an unknown {@code nodeId} simply yields an empty list.
     */
    @GetMapping("/by-session")
    @Transactional(readOnly = true)
    public List<SessionGoalsResponse> listBySession(@PathVariable Long courseId,
                                                    @Parameter(description = "Optional review-status filter: PENDING or APPROVED. Omit for all goals.")
                                                    @RequestParam(required = false) GoalStatus status,
                                                    @Parameter(description = "Optional extraction-tier filter: SKILL or KNOWLEDGE. Omit for all goals.")
                                                    @RequestParam(required = false) GoalRole role,
                                                    @Parameter(description = "Optional id of a single hierarchy node (module/session/exercise) to return only that group. "
                                                            + "Read the id from the 'nodeId' field of this endpoint's unfiltered response. An unknown id yields an empty list.")
                                                    @RequestParam(required = false) Long nodeId) {
        if (!courseRepository.existsById(courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId);
        }

        List<LearningGoal> goals;
        if (status == null && role == null) {
            goals = goalRepository.findByCourseId(courseId);
        } else if (status == null) {
            goals = goalRepository.findByCourseIdAndRole(courseId, role);
        } else if (role == null) {
            goals = goalRepository.findByCourseIdAndStatus(courseId, status);
        } else {
            goals = goalRepository.findByCourseIdAndStatusAndRole(courseId, status, role);
        }
        if (nodeId != null) {
            goals = goals.stream()
                    .filter(g -> g.getHierarchyNode() != null && nodeId.equals(g.getHierarchyNode().getId()))
                    .toList();
        }
        List<Long> goalIds = goals.stream().map(LearningGoal::getId).toList();
        Map<Long, List<GoalSourceResponse>> sourcesByGoal = sourcesByGoal(goalIds);
        Map<Long, List<GoalRelationshipResponse>> relationshipsByGoal = relationshipsByGoal(goalIds);

        Map<HierarchyNode, List<LearningGoal>> byNode = new LinkedHashMap<>();
        List<LearningGoal> ungrouped = new ArrayList<>();
        goals.stream()
                .sorted(Comparator.comparing(LearningGoal::getId))
                .forEach(g -> {
                    if (g.getHierarchyNode() == null) {
                        ungrouped.add(g);
                    } else {
                        byNode.computeIfAbsent(g.getHierarchyNode(), n -> new ArrayList<>()).add(g);
                    }
                });

        List<SessionGoalsResponse> groups = new ArrayList<>();
        byNode.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(HierarchyNode::getId)))
                .forEach(e -> groups.add(new SessionGoalsResponse(
                        e.getKey().getId(),
                        e.getKey().getLevel(),
                        e.getKey().getLabel(),
                        toResponses(e.getValue(), sourcesByGoal, relationshipsByGoal))));
        if (!ungrouped.isEmpty()) {
            groups.add(new SessionGoalsResponse(null, null, null,
                    toResponses(ungrouped, sourcesByGoal, relationshipsByGoal)));
        }
        return groups;
    }

    private List<LearningGoalResponse> toResponses(List<LearningGoal> goals,
                                                   Map<Long, List<GoalSourceResponse>> sourcesByGoal,
                                                   Map<Long, List<GoalRelationshipResponse>> relationshipsByGoal) {
        return goals.stream()
                .map(g -> LearningGoalResponse.from(
                        g,
                        sourcesByGoal.getOrDefault(g.getId(), List.of()),
                        relationshipsByGoal.getOrDefault(g.getId(), List.of())))
                .toList();
    }

    private Map<Long, List<GoalSourceResponse>> sourcesByGoal(List<Long> goalIds) {
        if (goalIds.isEmpty()) {
            return Map.of();
        }
        List<GoalSource> sources = goalSourceRepository.findByGoalIdIn(goalIds);
        Set<Long> contentDocumentIds = contentDocumentIds(sources);
        Map<Long, Map<Integer, String>> figureDescriptions = figureDescriptionsBySource(sources);
        return sources.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getGoal().getId(),
                        Collectors.mapping(s -> GoalSourceResponse.from(s,
                                contentDocumentIds.contains(s.getDocument().getId()),
                                figureDescription(s, figureDescriptions)), Collectors.toList())));
    }

    private Map<Long, List<GoalRelationshipResponse>> relationshipsByGoal(List<Long> goalIds) {
        return goalIds.isEmpty()
                ? Map.of()
                : goalRelationshipRepository.findBySourceIdInWithTarget(goalIds).stream()
                        .collect(Collectors.groupingBy(
                                r -> r.getSource().getId(),
                                Collectors.collectingAndThen(
                                        Collectors.mapping(GoalRelationshipResponse::from, Collectors.toList()),
                                        list -> list.stream().sorted(GoalRelationshipResponse.ORDER).toList())));
    }

    /**
     * Adds a terminal skill (competency root) an instructor typed in the post-extraction review. It is
     * NOT part of the ordinary pipeline: created directly as an {@code origin=TERMINAL},
     * {@code status=PENDING} goal with no source snippet, tagged {@code USER_CREATED} so it stays
     * distinguishable from clustered terminals. Bloom/SOLO stay empty — a typed skill is the
     * instructor's own wording, so the levels are theirs to set in the review rather than a model's
     * guess; only generated nodes are classified. The AI subtree is best-effort: a failure still
     * creates the skill and can be retried later.
     * No embedding is computed, matching the pipeline's terminal competencies.
     * The goal is attached to the course's COMPETENCY root, reusing it or creating it on first use.
     */
    @PostMapping("/terminal")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LearningGoalResponse createTerminalSkill(@PathVariable Long courseId,
                                                    @RequestBody CreateTerminalSkillRequest request) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId));
        String text = request.text() == null ? "" : request.text().strip();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill text must not be blank");
        }
        boolean duplicate = goalRepository.findByCourseIdAndOriginIn(courseId, List.of(GoalOrigin.TERMINAL)).stream()
                .anyMatch(g -> g.getText() != null && g.getText().strip().equalsIgnoreCase(text));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A terminal skill with this text already exists");
        }

        LearningGoal goal = new LearningGoal(course, text, GoalKind.IMPLICIT);
        goal.setOrigin(GoalOrigin.TERMINAL);
        goal.setStatus(GoalStatus.PENDING);
        goal.setCreationProvenance(GoalCreationProvenance.USER_CREATED);
        goal.setHierarchyNode(competencyRoot(course));
        goal.setLectureOrder(nextLectureOrder(course));
        goalRepository.save(goal);
        String languageName = courseLanguageName(course);
        GeneratedSubtree generated = null;
        try {
            generated = SubtreeSynthesizer.validate(
                    subtreeSynthesizer.generateSubtree(text, languageName, null));
        } catch (RuntimeException ignored) {
            // The instructor's skill is still useful without an AI subtree; the review can retry it later.
        }
        if (generated != null) {
            GeneratedNodes generatedNodes = buildGeneratedNodes(course, generated);
            // Only the generated nodes: the skill itself was already classified above.
            applyClassifications(generatedNodes.nodes(), null);
            persistGeneratedNodes(goal, generatedNodes);
        }
        return LearningGoalResponse.from(goal, List.of(), List.of());
    }

    /** Returns transient AI suggestions grounded in the already extracted course goals. */
    @PostMapping("/skill-suggestions")
    @Transactional(readOnly = true)
    public List<SkillSuggestionResponse> suggestTerminalSkills(@PathVariable Long courseId,
                                                               @RequestParam(required = false) String model) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId));
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
                existingTerminals, evidence, courseLanguageName(course), model);
        return suggestions == null ? List.of() : suggestions.stream()
                .filter(suggestion -> suggestion != null && suggestion.text() != null && !suggestion.text().isBlank())
                .map(suggestion -> new SkillSuggestionResponse(suggestion.text(), suggestion.shortLabel()))
                .toList();
    }

    /** Generates and atomically persists a complete terminal → sub-skill → knowledge subtree. */
    @PostMapping("/terminal/generated")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LearningGoalResponse createGeneratedTerminalSkill(@PathVariable Long courseId,
                                                             @RequestParam(required = false) String model,
                                                             @RequestBody CreateGeneratedTerminalSkillRequest request) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId));
        String text = request.text() == null ? "" : request.text().strip();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill text must not be blank");
        }
        boolean duplicate = goalRepository.findByCourseIdAndOriginIn(courseId, List.of(GoalOrigin.TERMINAL)).stream()
                .anyMatch(goal -> goal.getText() != null && goal.getText().strip().equalsIgnoreCase(text));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A terminal skill with this text already exists");
        }

        GeneratedSubtree generated = SubtreeSynthesizer.validate(
                subtreeSynthesizer.generateSubtree(text, courseLanguageName(course), model));
        LearningGoal terminal = newGeneratedGoal(course, text, GoalOrigin.TERMINAL);
        terminal.setShortLabel(trimToNull(request.shortLabel()));
        terminal.setHierarchyNode(competencyRoot(course));

        GeneratedNodes generatedNodes = buildGeneratedNodes(course, generated);
        applyClassifications(allNodes(terminal, generatedNodes), model);
        persistGeneratedNodes(terminal, generatedNodes);
        return LearningGoalResponse.from(terminal, List.of(), List.of());
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
    @PostMapping("/{goalId}/subtree")
    @Transactional
    public LearningGoalResponse generateSubtree(@PathVariable Long courseId,
                                                @PathVariable Long goalId,
                                                @RequestParam(required = false) String model) {
        LearningGoal terminal = findGoal(courseId, goalId);
        if (terminal.getOrigin() != GoalOrigin.TERMINAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only terminal skills can generate a subtree");
        }
        if (terminal.getCreationProvenance() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A skill extracted from the course material cannot be regenerated");
        }

        Course course = terminal.getCourse();
        GeneratedSubtree generated = SubtreeSynthesizer.validate(
                subtreeSynthesizer.generateSubtree(terminal.getText(), courseLanguageName(course), model));
        GeneratedNodes generatedNodes = buildGeneratedNodes(course, generated);
        applyClassifications(generatedNodes.nodes(), model);

        deleteWizardGeneratedDescendants(terminal);
        goalRepository.flush();
        persistGeneratedNodes(terminal, generatedNodes);
        // The terminal was still created by hand; its provenance describes the node, not its children.
        return LearningGoalResponse.from(terminal, List.of(), List.of());
    }

    /**
     * Adds one sub-skill or knowledge item an instructor typed, as a {@code USER_CREATED} child that
     * CONTRIBUTES_TO {@code goalId}. Additive by design: it is allowed under extracted goals too,
     * because it destroys nothing. Only the tier is constrained — see {@link #rejectIfKnowledgeTier}.
     * Like a typed skill, it stays unclassified: Bloom/SOLO are the instructor's to set, and skipping
     * the model keeps the add instant.
     */
    @PostMapping("/{goalId}/children")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LearningGoalResponse addChild(@PathVariable Long courseId,
                                         @PathVariable Long goalId,
                                         @RequestBody AddChildRequest request) {
        LearningGoal parent = findGoal(courseId, goalId);
        String text = request.text() == null ? "" : request.text().strip();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal text must not be blank");
        }
        rejectIfKnowledgeTier(parent);

        Course course = parent.getCourse();
        LearningGoal child = newUserCreatedChild(course, text);
        child.setShortLabel(trimToNull(request.shortLabel()));
        child.setLectureOrder(nextLectureOrder(course));
        goalRepository.save(child);
        goalRepository.flush();
        linkContributors(List.of(child), parent);
        return LearningGoalResponse.from(child, List.of(), List.of());
    }

    /**
     * Rejects a parent that already sits on the knowledge tier. The competency forest is capped at
     * three tiers (skill → sub-skill → knowledge), so a child below knowledge would be persisted but
     * never rendered in any view. A parent is eligible when it is a terminal skill (tier 1) or
     * contributes to one (tier 2); anything else — including a goal outside the competency tree
     * altogether — cannot take children.
     */
    private void rejectIfKnowledgeTier(LearningGoal parent) {
        if (parent.getOrigin() == GoalOrigin.TERMINAL) {
            return;
        }
        boolean contributesToTerminal = goalRelationshipRepository.findBySourceId(parent.getId()).stream()
                .anyMatch(relationship -> relationship.getType() == RelationshipType.CONTRIBUTES_TO
                        && relationship.getTarget().getOrigin() == GoalOrigin.TERMINAL);
        if (!contributesToTerminal) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a skill or a sub-skill can take children");
        }
    }

    private GeneratedNodes buildGeneratedNodes(Course course, GeneratedSubtree generated) {
        List<LearningGoal> subSkills = new ArrayList<>();
        List<List<LearningGoal>> knowledgeBySubSkill = new ArrayList<>();
        List<LearningGoal> nodes = new ArrayList<>();
        for (GeneratedSubSkill generatedSubSkill : generated.subSkills()) {
            LearningGoal subSkill = newGeneratedGoal(course, generatedSubSkill.text(), GoalOrigin.SYNTHESIZED);
            subSkill.setShortLabel(trimToNull(generatedSubSkill.shortLabel()));
            subSkills.add(subSkill);
            nodes.add(subSkill);
            List<LearningGoal> subSkillKnowledge = new ArrayList<>();
            for (GeneratedKnowledge generatedKnowledge : generatedSubSkill.knowledge()) {
                LearningGoal knowledgeGoal = newGeneratedGoal(course, generatedKnowledge.text(), GoalOrigin.SYNTHESIZED);
                knowledgeGoal.setShortLabel(trimToNull(generatedKnowledge.shortLabel()));
                subSkillKnowledge.add(knowledgeGoal);
                nodes.add(knowledgeGoal);
            }
            knowledgeBySubSkill.add(subSkillKnowledge);
        }
        return new GeneratedNodes(subSkills, knowledgeBySubSkill, nodes);
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
        for (int i = 0; i < generatedNodes.subSkills().size(); i++) {
            for (LearningGoal knowledgeGoal : generatedNodes.knowledgeBySubSkill().get(i)) {
                linkContributors(List.of(knowledgeGoal), generatedNodes.subSkills().get(i));
            }
        }
        for (LearningGoal subSkill : generatedNodes.subSkills()) {
            linkContributors(List.of(subSkill), terminal);
        }
    }

    private record GeneratedNodes(List<LearningGoal> subSkills,
                                  List<List<LearningGoal>> knowledgeBySubSkill,
                                  List<LearningGoal> nodes) {
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

    private void applyClassifications(List<LearningGoal> nodes, String model) {
        List<String> texts = nodes.stream().map(LearningGoal::getText).toList();
        try {
            List<TaxonomyClassification> classifications = taxonomyService.classifyBatch(texts, model);
            if (classifications == null || classifications.size() != nodes.size()) {
                return;
            }
            for (int i = 0; i < nodes.size(); i++) {
                TaxonomyClassification classification = classifications.get(i);
                if (classification != null) {
                    nodes.get(i).setBloomLevel(classification.bloom());
                    nodes.get(i).setSoloLevel(classification.solo());
                }
            }
        } catch (RuntimeException ignored) {
            // Classification is best-effort; the generated subtree must still be persisted.
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

    private String courseLanguageName(Course course) {
        List<Document> documents = documentRepository.findByCourseId(course.getId());
        Map<String, Long> weights = new LinkedHashMap<>();
        for (Document document : documents) {
            String language = document.getLanguage();
            if (language == null || language.isBlank()) {
                continue;
            }
            long weight = document.getRawText() == null ? 0 : document.getRawText().length();
            if (weight > 0) {
                weights.merge(language, weight, Long::sum);
            }
        }
        String dominantLanguage = weights.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        String language = course.getOutputLanguage() != null
                ? course.getOutputLanguage()
                : dominantLanguage;
        return LanguageUtils.englishName(language == null ? "en" : language);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    /** All terminal skills of a course share one COMPETENCY root node, created on first use. */
    private HierarchyNode competencyRoot(Course course) {
        return hierarchyNodeRepository
                .findFirstByCourseIdAndLevelOrderByIdAsc(course.getId(), HierarchyLevel.COMPETENCY)
                .orElseGet(() -> hierarchyNodeRepository.save(
                        new HierarchyNode(course, null, HierarchyLevel.COMPETENCY, COMPETENCY_ROOT_LABEL)));
    }

    @PatchMapping("/{goalId}")
    @Transactional
    public LearningGoalResponse update(@PathVariable Long courseId,
                                       @PathVariable Long goalId,
                                       @RequestBody UpdateLearningGoalRequest request) {
        LearningGoal goal = findGoal(courseId, goalId);
        if (request.text() != null) {
            String text = request.text().strip();
            if (text.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal text must not be blank");
            }
            if (!text.equals(goal.getText())) {
                goal.setText(text);
                goal.setShortLabel(null);
                // The embedding was computed from the old wording; drop it rather than keep a stale one.
                goal.setEmbedding(null);
            }
        }
        if (request.status() != null) {
            goal.setStatus(request.status());
        }
        if (request.bloomLevel() != null) {
            goal.setBloomLevel(request.bloomLevel());
        }
        if (request.soloLevel() != null) {
            goal.setSoloLevel(request.soloLevel());
        }

        List<GoalSource> goalSources = goalSourceRepository.findByGoalIdIn(List.of(goalId));
        Set<Long> contentDocumentIds = contentDocumentIds(goalSources);
        Map<Long, Map<Integer, String>> figureDescriptions = figureDescriptionsBySource(goalSources);
        List<GoalSourceResponse> sources = goalSources.stream()
                .map(s -> GoalSourceResponse.from(s, contentDocumentIds.contains(s.getDocument().getId()),
                        figureDescription(s, figureDescriptions)))
                .toList();
        List<GoalRelationshipResponse> relationships = goalRelationshipRepository
                .findBySourceIdInWithTarget(List.of(goalId)).stream()
                .map(GoalRelationshipResponse::from)
                .sorted(GoalRelationshipResponse.ORDER)
                .toList();
        return LearningGoalResponse.from(goal, sources, relationships);
    }

    private Set<Long> contentDocumentIds(Collection<GoalSource> sources) {
        Set<Long> documentIds = new HashSet<>();
        for (GoalSource source : sources) {
            documentIds.add(source.getDocument().getId());
        }
        return documentIds.isEmpty()
                ? Set.of()
                : documentContentRepository.findExistingDocumentIds(documentIds);
    }

    private Map<Long, Map<Integer, String>> figureDescriptionsBySource(Collection<GoalSource> sources) {
        Set<Long> documentIds = sources.stream()
                .filter(source -> source.getEvidenceKind() == EvidenceKind.FIGURE && source.getPage() != null)
                .map(source -> source.getDocument().getId())
                .collect(Collectors.toSet());
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return pageDescriptionRepository.findByDocumentIdIn(documentIds).stream()
                .collect(Collectors.groupingBy(description -> description.getDocument().getId(),
                        Collectors.toMap(PageDescription::getPage, PageDescription::getDescription)));
    }

    private static String figureDescription(GoalSource source, Map<Long, Map<Integer, String>> descriptions) {
        if (source.getEvidenceKind() != EvidenceKind.FIGURE || source.getPage() == null) {
            return null;
        }
        return descriptions.getOrDefault(source.getDocument().getId(), Map.of()).get(source.getPage());
    }

    @DeleteMapping("/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long courseId, @PathVariable Long goalId) {
        // The DB cascades the delete to goal_source rows and to relationships in both directions.
        LearningGoal goal = findGoal(courseId, goalId);
        deleteOwnedDescendants(goal);
        goalRepository.delete(goal);
    }

    /** Regeneration owns only the descendants created by the AI subtree wizard; the root survives. */
    private void deleteWizardGeneratedDescendants(LearningGoal root) {
        deleteDescendants(root, Set.of(GoalCreationProvenance.WIZARD_AI_SUBTREE), false);
    }

    /** DELETE removes both AI-generated and manually added descendants, but never pipeline goals. */
    private void deleteOwnedDescendants(LearningGoal root) {
        deleteDescendants(root, Set.of(
                GoalCreationProvenance.WIZARD_AI_SUBTREE,
                GoalCreationProvenance.USER_CREATED), true);
    }

    /**
     * Collects and removes the descendants of {@code root} whose creation provenance marks them as
     * owned, together with every edge that would outlive one of its endpoints.
     *
     * @param rootDeleted whether {@code root} itself is being removed by the caller. DELETE removes it,
     *                    so all of its incoming edges go; regeneration keeps it, so only the edges from
     *                    owned descendants go and manual or extracted contributors stay attached.
     */
    private void deleteDescendants(LearningGoal root,
                                   Set<GoalCreationProvenance> ownedProvenances,
                                   boolean rootDeleted) {
        List<LearningGoal> descendants = new ArrayList<>();
        List<GoalRelationship> loadedEdges = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<LearningGoal> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            LearningGoal target = pending.removeFirst();
            boolean targetDeleted = rootDeleted || !target.getId().equals(root.getId());
            for (GoalRelationship relationship : goalRelationshipRepository.findByTargetId(target.getId())) {
                // A null provenance marks a pipeline/extracted goal, which is never owned. Test it
                // explicitly: ownedProvenances is a Set.of(...), and those throw on contains(null).
                GoalCreationProvenance provenance = relationship.getSource().getCreationProvenance();
                boolean owned = relationship.getType() == RelationshipType.CONTRIBUTES_TO
                        && provenance != null
                        && ownedProvenances.contains(provenance);
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

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Transactional(readOnly = true)
    public void exportCsv(@PathVariable Long courseId, HttpServletResponse response) throws IOException {
        if (!courseRepository.existsById(courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId);
        }

        List<LearningGoal> goals = goalRepository.findByCourseId(courseId);
        List<Long> goalIds = goals.stream().map(LearningGoal::getId).toList();
        List<GoalSource> sources = goalIds.isEmpty()
                ? List.of()
                : goalSourceRepository.findByGoalIdIn(goalIds);
        List<GoalRelationship> relationships = goalIds.isEmpty()
                ? List.of()
                : goalRelationshipRepository.findBySourceIdIn(goalIds);

        response.setContentType(MediaType.parseMediaType("text/csv").toString());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"course-" + courseId + "-learning-goals.csv\"");

        csvWriter.write(response.getWriter(), goals, sources, relationships);
    }

    public record LearningGoalResponse(Long id,
                                       String text,
                                       String shortLabel,
                                       GoalKind kind,
                                       GoalRole role,
                                       GoalStatus status,
                                       GoalOrigin origin,
                                       GoalCreationProvenance creationProvenance,
                                       HierarchyPath hierarchy,
                                       BloomLevel bloomLevel,
                                       SoloLevel soloLevel,
                                       Integer lectureOrder,
                                       OffsetDateTime createdAt,
                                       List<GoalSourceResponse> sources,
                                       List<GoalRelationshipResponse> relationships) {
        public static LearningGoalResponse from(LearningGoal g,
                                                List<GoalSourceResponse> sources,
                                                List<GoalRelationshipResponse> relationships) {
            HierarchyPath hierarchy = g.getHierarchyNode() == null
                    ? null
                    : HierarchyPath.from(g.getHierarchyNode());
            return new LearningGoalResponse(
                    g.getId(),
                    g.getText(),
                    g.getShortLabel(),
                    g.getKind(),
                    g.getRole(),
                    g.getStatus(),
                    g.getOrigin(),
                    g.getCreationProvenance(),
                    hierarchy,
                    g.getBloomLevel(),
                    g.getSoloLevel(),
                    g.getLectureOrder(),
                    g.getCreatedAt(),
                    sources,
                    relationships);
        }
    }

    /** Body of the "add a terminal skill" review action: just the instructor-typed skill text. */
    public record CreateTerminalSkillRequest(String text) {
    }

    public record SkillSuggestionResponse(String text, String shortLabel) {
    }

    public record CreateGeneratedTerminalSkillRequest(String text, String shortLabel) {
    }

    public record AddChildRequest(String text, String shortLabel) {
    }

    /** One hierarchy node (module/session/exercise) and its goals; all-null node fields = ungrouped. */
    public record SessionGoalsResponse(Long nodeId,
                                       HierarchyLevel level,
                                       String label,
                                       List<LearningGoalResponse> goals) {
    }

    /** Partial update: only non-null fields are applied (levels can be set, not cleared). */
    public record UpdateLearningGoalRequest(String text,
                                            GoalStatus status,
                                            BloomLevel bloomLevel,
                                            SoloLevel soloLevel) {
    }

    public record GoalSourceResponse(Long documentId, String filename, String displayName,
                                     String snippet, Integer page, boolean contentAvailable, boolean grounded,
                                     @Schema(nullable = true) SourceMatchQuality groundingQuality,
                                     EvidenceKind evidenceKind,
                                     @Schema(nullable = true) String figureDescription,
                                     @Schema(nullable = true) List<HighlightRect> highlightRects) {
        static GoalSourceResponse from(GoalSource s, boolean contentAvailable, String figureDescription) {
            return new GoalSourceResponse(s.getDocument().getId(), s.getDocument().getFilename(),
                    s.getDocument().getDisplayName(), s.getSnippet(), s.getPage(), contentAvailable, s.isGrounded(),
                    s.getGroundingQuality(), s.getEvidenceKind(), figureDescription, s.getHighlightRects());
        }
    }

    public record GoalRelationshipResponse(RelationshipType type,
                                           Long targetGoalId,
                                           String targetText,
                                           double confidence,
                                           RelationshipOrigin origin) {
        /** Natural enum order matches the CSV export's CONTRIBUTES_TO → PREREQUISITE_OF → OVERLAPS_WITH grouping. */
        private static final Comparator<GoalRelationshipResponse> ORDER =
                Comparator.comparing(GoalRelationshipResponse::type)
                        .thenComparing(GoalRelationshipResponse::targetText);

        static GoalRelationshipResponse from(GoalRelationship r) {
            return new GoalRelationshipResponse(
                    r.getType(),
                    r.getTarget().getId(),
                    r.getTarget().getText(),
                    r.getConfidence(),
                    r.getOrigin());
        }
    }
}
