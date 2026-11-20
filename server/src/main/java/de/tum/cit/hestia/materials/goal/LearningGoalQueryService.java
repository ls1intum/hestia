package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.GoalRelationInfo;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.materials.document.DocumentContentRepository;
import de.tum.cit.hestia.materials.document.PageDescription;
import de.tum.cit.hestia.materials.document.PageDescriptionRepository;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a course's learning goals and turns them into API responses, each with its sources and its
 * outgoing relationships. Every response is assembled inside the read transaction, since the
 * hierarchy, document and target goal it shows are loaded lazily.
 */
@Service
public class LearningGoalQueryService {

    private final CourseAccess courseAccess;
    private final LearningGoalApi goalApi;
    private final PlacedGoals goals;
    private final GoalSourceRepository goalSourceRepository;
    private final DocumentContentRepository documentContentRepository;
    private final PageDescriptionRepository pageDescriptionRepository;
    private final LearningGoalCsvWriter csvWriter;

    public LearningGoalQueryService(CourseAccess courseAccess,
                                    LearningGoalApi goalApi,
                                    PlacedGoals goals,
                                    GoalSourceRepository goalSourceRepository,
                                    DocumentContentRepository documentContentRepository,
                                    PageDescriptionRepository pageDescriptionRepository,
                                    LearningGoalCsvWriter csvWriter) {
        this.courseAccess = courseAccess;
        this.goalApi = goalApi;
        this.goals = goals;
        this.goalSourceRepository = goalSourceRepository;
        this.documentContentRepository = documentContentRepository;
        this.pageDescriptionRepository = pageDescriptionRepository;
        this.csvWriter = csvWriter;
    }

    /**
     * One page of the course's goals, oldest first; the pages are cut in memory, since the goals of
     * a course come from {@code core} in one list. The sort of the request is not used.
     */
    @Transactional(readOnly = true)
    public Page<LearningGoalResponse> page(UUID courseId, GoalStatus status, GoalRole role, Pageable pageable) {
        List<PlacedGoal> filtered = filtered(courseId, status, role);
        int from = (int) Math.min(pageable.getOffset(), filtered.size());
        int to = Math.min(from + pageable.getPageSize(), filtered.size());
        List<PlacedGoal> pageGoals = filtered.subList(from, to);
        Map<UUID, LearningGoalResponse> responses = responsesById(courseId, pageGoals);
        return new PageImpl<>(pageGoals.stream().map(g -> responses.get(g.id())).toList(), pageable,
                filtered.size());
    }

    /** Every goal of the course, oldest first, any review status. */
    @Transactional(readOnly = true)
    public List<LearningGoalResponse> all(UUID courseId) {
        List<PlacedGoal> all = goals.list(courseId);
        Map<UUID, LearningGoalResponse> responses = responsesById(courseId, all);
        return all.stream().map(g -> responses.get(g.id())).toList();
    }

    /** One goal of the course, or empty when the course has no such goal. */
    @Transactional(readOnly = true)
    public java.util.Optional<LearningGoalResponse> find(UUID courseId, UUID goalId) {
        courseAccess.requireRead(courseId);
        return goals.find(courseId, goalId)
                .map(goal -> responsesById(courseId, List.of(goal)).get(goal.id()));
    }

    /** See {@link LearningGoalController#listBySession} for the grouping and ordering rules. */
    @Transactional(readOnly = true)
    public List<SessionGoalsResponse> bySession(UUID courseId, GoalStatus status, GoalRole role, Long nodeId) {
        List<PlacedGoal> filtered = filtered(courseId, status, role);
        if (nodeId != null) {
            filtered = filtered.stream()
                    .filter(g -> g.hierarchyNode() != null && nodeId.equals(g.hierarchyNode().getId()))
                    .toList();
        }
        Map<UUID, LearningGoalResponse> responses = responsesById(courseId, filtered);

        Map<HierarchyNode, List<PlacedGoal>> byNode = new LinkedHashMap<>();
        List<PlacedGoal> ungrouped = new ArrayList<>();
        for (PlacedGoal g : filtered) {
            if (g.hierarchyNode() == null) {
                ungrouped.add(g);
            } else {
                byNode.computeIfAbsent(g.hierarchyNode(), n -> new ArrayList<>()).add(g);
            }
        }

        List<SessionGoalsResponse> groups = new ArrayList<>();
        byNode.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(HierarchyNode::getId)))
                .forEach(e -> groups.add(new SessionGoalsResponse(
                        e.getKey().getId(),
                        e.getKey().getLevel(),
                        e.getKey().getLabel(),
                        e.getValue().stream().map(g -> responses.get(g.id())).toList())));
        if (!ungrouped.isEmpty()) {
            groups.add(new SessionGoalsResponse(null, null, null,
                    ungrouped.stream().map(g -> responses.get(g.id())).toList()));
        }
        return groups;
    }

    /**
     * Writes every goal of the course, with its sources and relationships, as CSV. The output is
     * opened only once the course is known to be readable, so a 404 is not sent as a CSV download.
     */
    @Transactional(readOnly = true)
    public void exportCsv(UUID courseId, CsvOutput output) throws IOException {
        courseAccess.requireRead(courseId);

        List<PlacedGoal> all = goals.list(courseId);
        List<UUID> goalIds = all.stream().map(PlacedGoal::id).toList();
        List<GoalSource> sources = goalIds.isEmpty()
                ? List.of()
                : goalSourceRepository.findByIdGoalIdIn(goalIds);
        csvWriter.write(output.open(), all, sources, goalApi.relationships(courseId));
    }

    /** Where the CSV export goes, opened after the course check. */
    @FunctionalInterface
    public interface CsvOutput {
        Writer open() throws IOException;
    }

    /** One goal's response, with its sources and relationships. Joins the caller's transaction. */
    @Transactional(readOnly = true)
    public LearningGoalResponse toResponse(PlacedGoal goal) {
        return responsesById(goal.courseId(), List.of(goal)).get(goal.id());
    }

    /** The goals of a course matching the filters, oldest first (the order of {@code core}). */
    private List<PlacedGoal> filtered(UUID courseId, GoalStatus status, GoalRole role) {
        return goals.list(courseId).stream()
                .filter(g -> status == null || g.status() == status)
                .filter(g -> role == null || g.role() == role)
                .toList();
    }

    private Map<UUID, LearningGoalResponse> responsesById(UUID courseId, List<PlacedGoal> placed) {
        List<UUID> goalIds = placed.stream().map(PlacedGoal::id).toList();
        Map<UUID, List<GoalSourceResponse>> sourcesByGoal = sourcesByGoal(goalIds);
        Map<UUID, List<GoalRelationshipResponse>> relationshipsByGoal = relationshipsByGoal(courseId, goalIds);
        Map<UUID, LearningGoalResponse> responses = new LinkedHashMap<>();
        for (PlacedGoal goal : placed) {
            responses.put(goal.id(), LearningGoalResponse.from(
                    goal,
                    sourcesByGoal.getOrDefault(goal.id(), List.of()),
                    relationshipsByGoal.getOrDefault(goal.id(), List.of())));
        }
        return responses;
    }

    private Map<UUID, List<GoalSourceResponse>> sourcesByGoal(List<UUID> goalIds) {
        if (goalIds.isEmpty()) {
            return Map.of();
        }
        List<GoalSource> sources = goalSourceRepository.findByIdGoalIdIn(goalIds);
        Set<Long> contentDocumentIds = contentDocumentIds(sources);
        Map<Long, Map<Integer, String>> figureDescriptions = figureDescriptionsBySource(sources);
        return sources.stream()
                .collect(Collectors.groupingBy(
                        GoalSource::getGoalId,
                        Collectors.mapping(s -> GoalSourceResponse.from(s,
                                contentDocumentIds.contains(s.getDocument().getId()),
                                figureDescription(s, figureDescriptions)), Collectors.toList())));
    }

    /** The outgoing relationships of the goals; their targets are named from the course's goals. */
    private Map<UUID, List<GoalRelationshipResponse>> relationshipsByGoal(UUID courseId, List<UUID> goalIds) {
        if (goalIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> wanted = new HashSet<>(goalIds);
        List<GoalRelationInfo> edges = goalApi.relationships(courseId).stream()
                .filter(r -> wanted.contains(r.sourceGoalId()))
                .toList();
        if (edges.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> targetTexts = goalApi.goals(courseId).stream()
                .collect(Collectors.toMap(g -> g.id(), g -> g.text(), (a, b) -> a));
        return edges.stream()
                .filter(r -> targetTexts.containsKey(r.targetGoalId()))
                .collect(Collectors.groupingBy(
                        GoalRelationInfo::sourceGoalId,
                        Collectors.collectingAndThen(
                                Collectors.mapping(r -> GoalRelationshipResponse.from(r,
                                        targetTexts.get(r.targetGoalId())), Collectors.toList()),
                                list -> list.stream().sorted(GoalRelationshipResponse.ORDER).toList())));
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
}
