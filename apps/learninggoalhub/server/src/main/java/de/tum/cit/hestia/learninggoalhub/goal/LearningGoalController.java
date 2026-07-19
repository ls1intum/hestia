package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyPath;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
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
    private final GoalRelationshipRepository goalRelationshipRepository;
    private final LearningGoalCsvWriter csvWriter;

    public LearningGoalController(CourseRepository courseRepository,
                                  LearningGoalRepository goalRepository,
                                  GoalSourceRepository goalSourceRepository,
                                  DocumentContentRepository documentContentRepository,
                                  GoalRelationshipRepository goalRelationshipRepository,
                                  LearningGoalCsvWriter csvWriter) {
        this.courseRepository = courseRepository;
        this.goalRepository = goalRepository;
        this.goalSourceRepository = goalSourceRepository;
        this.documentContentRepository = documentContentRepository;
        this.goalRelationshipRepository = goalRelationshipRepository;
        this.csvWriter = csvWriter;
    }

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
        goalRepository.delete(findGoal(courseId, goalId));
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
                    hierarchy,
                    g.getBloomLevel(),
                    g.getSoloLevel(),
                    g.getCreatedAt(),
                    sources,
                    relationships);
        }
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

    public record GoalSourceResponse(Long documentId, String filename, String snippet,
                                     Integer page, boolean contentAvailable, boolean grounded) {
        static GoalSourceResponse from(GoalSource s, boolean contentAvailable) {
            return new GoalSourceResponse(s.getDocument().getId(), s.getDocument().getFilename(),
                    s.getSnippet(), s.getPage(), contentAvailable, s.isGrounded());
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
