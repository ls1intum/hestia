package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescription;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
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
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
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

    private final CourseRepository courseRepository;
    private final LearningGoalRepository goalRepository;
    private final GoalSourceRepository goalSourceRepository;
    private final DocumentContentRepository documentContentRepository;
    private final PageDescriptionRepository pageDescriptionRepository;
    private final GoalRelationshipRepository goalRelationshipRepository;
    private final LearningGoalCsvWriter csvWriter;

    public LearningGoalQueryService(CourseRepository courseRepository,
                                    LearningGoalRepository goalRepository,
                                    GoalSourceRepository goalSourceRepository,
                                    DocumentContentRepository documentContentRepository,
                                    PageDescriptionRepository pageDescriptionRepository,
                                    GoalRelationshipRepository goalRelationshipRepository,
                                    LearningGoalCsvWriter csvWriter) {
        this.courseRepository = courseRepository;
        this.goalRepository = goalRepository;
        this.goalSourceRepository = goalSourceRepository;
        this.documentContentRepository = documentContentRepository;
        this.pageDescriptionRepository = pageDescriptionRepository;
        this.goalRelationshipRepository = goalRelationshipRepository;
        this.csvWriter = csvWriter;
    }

    @Transactional(readOnly = true)
    public Page<LearningGoalResponse> page(Long courseId, GoalStatus status, GoalRole role, Pageable pageable) {
        courseRepository.requireExists(courseId);

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
        Map<Long, LearningGoalResponse> responses = responsesById(page.getContent());
        return page.map(g -> responses.get(g.getId()));
    }

    /** See {@link LearningGoalController#listBySession} for the grouping and ordering rules. */
    @Transactional(readOnly = true)
    public List<SessionGoalsResponse> bySession(Long courseId, GoalStatus status, GoalRole role, Long nodeId) {
        courseRepository.requireExists(courseId);

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
        Map<Long, LearningGoalResponse> responses = responsesById(goals);

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
                        e.getValue().stream().map(g -> responses.get(g.getId())).toList())));
        if (!ungrouped.isEmpty()) {
            groups.add(new SessionGoalsResponse(null, null, null,
                    ungrouped.stream().map(g -> responses.get(g.getId())).toList()));
        }
        return groups;
    }

    /**
     * Writes every goal of the course, with its sources and relationships, as CSV. The output is
     * opened only once the course is known to exist, so a 404 is not sent as a CSV download.
     */
    @Transactional(readOnly = true)
    public void exportCsv(Long courseId, CsvOutput output) throws IOException {
        courseRepository.requireExists(courseId);

        List<LearningGoal> goals = goalRepository.findByCourseId(courseId);
        List<Long> goalIds = goals.stream().map(LearningGoal::getId).toList();
        List<GoalSource> sources = goalIds.isEmpty()
                ? List.of()
                : goalSourceRepository.findByGoalIdIn(goalIds);
        List<GoalRelationship> relationships = goalIds.isEmpty()
                ? List.of()
                : goalRelationshipRepository.findBySourceIdIn(goalIds);
        csvWriter.write(output.open(), goals, sources, relationships);
    }

    /** Where the CSV export goes, opened after the course check. */
    @FunctionalInterface
    public interface CsvOutput {
        Writer open() throws IOException;
    }

    /** One goal's response, with its sources and relationships. Joins the caller's transaction. */
    @Transactional(readOnly = true)
    public LearningGoalResponse toResponse(LearningGoal goal) {
        return responsesById(List.of(goal)).get(goal.getId());
    }

    private Map<Long, LearningGoalResponse> responsesById(List<LearningGoal> goals) {
        List<Long> goalIds = goals.stream().map(LearningGoal::getId).toList();
        Map<Long, List<GoalSourceResponse>> sourcesByGoal = sourcesByGoal(goalIds);
        Map<Long, List<GoalRelationshipResponse>> relationshipsByGoal = relationshipsByGoal(goalIds);
        Map<Long, LearningGoalResponse> responses = new LinkedHashMap<>();
        for (LearningGoal goal : goals) {
            responses.put(goal.getId(), LearningGoalResponse.from(
                    goal,
                    sourcesByGoal.getOrDefault(goal.getId(), List.of()),
                    relationshipsByGoal.getOrDefault(goal.getId(), List.of())));
        }
        return responses;
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
