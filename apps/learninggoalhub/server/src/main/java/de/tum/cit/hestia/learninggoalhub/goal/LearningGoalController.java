package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.LanguageUtils;
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
                                                 @ParameterObject @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.ASC)
                                                 Pageable pageable) {
        if (!courseRepository.existsById(courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId);
        }

        Page<LearningGoal> page = status == null
                ? goalRepository.findByCourseId(courseId, pageable)
                : goalRepository.findByCourseIdAndStatus(courseId, status, pageable);
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
                                                    @Parameter(description = "Optional id of a single hierarchy node (module/session/exercise) to return only that group. "
                                                            + "Read the id from the 'nodeId' field of this endpoint's unfiltered response. An unknown id yields an empty list.")
                                                    @RequestParam(required = false) Long nodeId) {
        if (!courseRepository.existsById(courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId);
        }

        List<LearningGoal> goals = status == null
                ? goalRepository.findByCourseId(courseId)
                : goalRepository.findByCourseIdAndStatus(courseId, status);
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
        return sources.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getGoal().getId(),
                        Collectors.mapping(s -> GoalSourceResponse.from(s,
                                contentDocumentIds.contains(s.getDocument().getId())), Collectors.toList())));
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
     * {@code status=PENDING} goal with no CONTRIBUTES_TO edges and no source snippet, tagged
     * {@code USER_CREATED} so it stays distinguishable from clustered terminals. Bloom/SOLO are
     * classified best-effort — a classification failure still creates the skill, just without levels;
     * no embedding is computed, matching the pipeline's terminal competencies. The goal is attached to
     * the course's COMPETENCY root, reusing it or creating it on first use.
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
        try {
            TaxonomyClassification classification = taxonomyService.classify(text);
            if (classification != null) {
                goal.setBloomLevel(classification.bloom());
                goal.setSoloLevel(classification.solo());
            }
        } catch (RuntimeException ignored) {
            // Best-effort, mirroring the pipeline: a classification failure still creates the skill.
        }
        goalRepository.save(goal);
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

        List<LearningGoal> subSkills = new ArrayList<>();
        List<List<LearningGoal>> knowledgeBySubSkill = new ArrayList<>();
        List<LearningGoal> nodes = new ArrayList<>();
        nodes.add(terminal);
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

        applyClassifications(nodes, model);
        goalRepository.saveAll(nodes);
        goalRepository.flush();
        for (int i = 0; i < subSkills.size(); i++) {
            for (LearningGoal knowledgeGoal : knowledgeBySubSkill.get(i)) {
                linkContributors(List.of(knowledgeGoal), subSkills.get(i));
            }
        }
        for (LearningGoal subSkill : subSkills) {
            linkContributors(List.of(subSkill), terminal);
        }
        return LearningGoalResponse.from(terminal, List.of(), List.of());
    }

    private LearningGoal newGeneratedGoal(Course course, String text, GoalOrigin origin) {
        LearningGoal goal = new LearningGoal(course, text.strip(), GoalKind.IMPLICIT);
        goal.setOrigin(origin);
        goal.setStatus(GoalStatus.PENDING);
        goal.setCreationProvenance(GoalCreationProvenance.WIZARD_AI_SUBTREE);
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
        List<GoalSourceResponse> sources = goalSources.stream()
                .map(s -> GoalSourceResponse.from(s, contentDocumentIds.contains(s.getDocument().getId())))
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

    @DeleteMapping("/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long courseId, @PathVariable Long goalId) {
        // The DB cascades the delete to goal_source rows and to relationships in both directions.
        LearningGoal goal = findGoal(courseId, goalId);
        if (goal.getOrigin() == GoalOrigin.TERMINAL
                && goal.getCreationProvenance() == GoalCreationProvenance.WIZARD_AI_SUBTREE) {
            deleteGeneratedDescendants(goal);
        }
        goalRepository.delete(goal);
    }

    private void deleteGeneratedDescendants(LearningGoal root) {
        List<LearningGoal> descendants = new ArrayList<>();
        List<GoalRelationship> ownedEdges = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<LearningGoal> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            LearningGoal target = pending.removeFirst();
            for (GoalRelationship relationship : goalRelationshipRepository.findByTargetId(target.getId())) {
                if (relationship.getType() != RelationshipType.CONTRIBUTES_TO
                        || relationship.getSource().getCreationProvenance() != GoalCreationProvenance.WIZARD_AI_SUBTREE) {
                    continue;
                }
                ownedEdges.add(relationship);
                LearningGoal source = relationship.getSource();
                if (visited.add(source.getId())) {
                    descendants.add(source);
                    pending.addLast(source);
                }
            }
        }
        // Delete the loaded edge entities first: they are managed in the persistence context, so
        // deleting the goals they reference while they linger would fail the flush with a
        // TransientObjectException. The plain delete() path never loads relationships, hence relies on
        // the DB cascade alone.
        goalRelationshipRepository.deleteAll(ownedEdges);
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
                                       GoalStatus status,
                                       GoalOrigin origin,
                                       GoalCreationProvenance creationProvenance,
                                       HierarchyPath hierarchy,
                                       BloomLevel bloomLevel,
                                       SoloLevel soloLevel,
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
                    g.getStatus(),
                    g.getOrigin(),
                    g.getCreationProvenance(),
                    hierarchy,
                    g.getBloomLevel(),
                    g.getSoloLevel(),
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
                                     String snippet, Integer page, boolean contentAvailable, boolean grounded) {
        static GoalSourceResponse from(GoalSource s, boolean contentAvailable) {
            return new GoalSourceResponse(s.getDocument().getId(), s.getDocument().getFilename(),
                    s.getDocument().getDisplayName(), s.getSnippet(), s.getPage(), contentAvailable, s.isGrounded());
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
