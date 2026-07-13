package de.tum.cit.hestia.learninggoalhub.goal;

import com.opencsv.CSVWriter;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyPath;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class LearningGoalCsvWriter {

    static final String[] HEADER = {
            "hierarchy_module",
            "hierarchy_session",
            "hierarchy_exercise",
            "learning_goal",
            "kind",
            "sources",
            "taxonomy",
            "relationships",
            "status"
    };

    private static final List<RelationshipType> TYPE_ORDER = List.of(
            RelationshipType.CONTRIBUTES_TO,
            RelationshipType.PREREQUISITE_OF,
            RelationshipType.OVERLAPS_WITH);

    public void write(Writer writer,
                      List<LearningGoal> goals,
                      List<GoalSource> sources,
                      List<GoalRelationship> relationships) throws IOException {
        Map<Long, List<GoalSource>> sourcesByGoal = sources.stream()
                .collect(Collectors.groupingBy(s -> s.getGoal().getId()));
        Map<Long, List<GoalRelationship>> relationshipsBySource = relationships.stream()
                .collect(Collectors.groupingBy(r -> r.getSource().getId()));

        try (CSVWriter csv = new CSVWriter(writer)) {
            csv.writeNext(HEADER);
            for (LearningGoal g : goals) {
                csv.writeNext(toRow(
                        g,
                        sourcesByGoal.getOrDefault(g.getId(), List.of()),
                        relationshipsBySource.getOrDefault(g.getId(), List.of())));
            }
            csv.flushQuietly();
        }
    }

    private String[] toRow(LearningGoal goal, List<GoalSource> goalSources,
                           List<GoalRelationship> goalRelationships) {
        HierarchyPath path = HierarchyPath.from(goal.getHierarchyNode());
        String sources = goalSources.stream()
                .map(s -> s.getDocument().getFilename())
                .collect(Collectors.joining("; "));
        return new String[]{
                orEmpty(path.module()),
                orEmpty(path.session()),
                orEmpty(path.exercise()),
                goal.getText(),
                goal.getKind().name(),
                sources,
                formatTaxonomy(goal),
                formatRelationships(goalRelationships),
                goal.getStatus().name()
        };
    }

    private String formatTaxonomy(LearningGoal goal) {
        BloomLevel bloom = goal.getBloomLevel();
        SoloLevel solo = goal.getSoloLevel();
        if (bloom == null && solo == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (bloom != null) {
            sb.append("bloom=").append(bloom.name());
        }
        if (solo != null) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("solo=").append(solo.name());
        }
        return sb.toString();
    }

    private String formatRelationships(List<GoalRelationship> relationships) {
        if (relationships.isEmpty()) {
            return "";
        }
        Comparator<GoalRelationship> byTypeThenTarget = Comparator
                .<GoalRelationship>comparingInt(r -> TYPE_ORDER.indexOf(r.getType()))
                .thenComparing(r -> r.getTarget().getText());
        return relationships.stream()
                .sorted(byTypeThenTarget)
                .map(r -> r.getType().name() + "→" + r.getTarget().getText())
                .collect(Collectors.joining("; "));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
