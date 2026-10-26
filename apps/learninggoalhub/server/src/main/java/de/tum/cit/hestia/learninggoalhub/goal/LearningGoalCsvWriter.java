package de.tum.cit.hestia.learninggoalhub.goal;

import com.opencsv.CSVWriter;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyPath;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Writes a course's goals as one CSV row per goal, in Topic tree order: each topic, then its
 * subtree depth-first, then the goals outside the tree. {@code parent_ids} and {@code tree_depth}
 * come from the {@code CONTRIBUTES_TO} edges (child → parent), so the tree can be rebuilt from the
 * file. Legacy GAP goals and legacy edge types are left out, as in the client.
 */
@Component
public class LearningGoalCsvWriter {

    static final String[] HEADER = {
            "id",
            "parent_ids",
            "tree_depth",
            "role",
            "origin",
            "created_by",
            "learning_goal",
            "short_label",
            "kind",
            "bloom",
            "solo",
            "status",
            "hierarchy_module",
            "hierarchy_session",
            "hierarchy_exercise",
            "sources",
            "grounded"
    };

    /** Lets Excel detect UTF-8, so umlauts and other non-ASCII text open intact. */
    static final char BOM = '﻿';

    private static final Comparator<LearningGoal> LECTURE_ORDER = Comparator
            .comparing(LearningGoal::getLectureOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(LearningGoal::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    public void write(Writer writer,
                      List<LearningGoal> goals,
                      List<GoalSource> sources,
                      List<GoalRelationship> relationships) throws IOException {
        List<LearningGoal> exported = goals.stream()
                .filter(g -> g.getOrigin() != GoalOrigin.GAP)
                .toList();
        Map<Long, LearningGoal> byId = exported.stream()
                .collect(Collectors.toMap(LearningGoal::getId, g -> g));
        Map<Long, List<GoalSource>> sourcesByGoal = sources.stream()
                .collect(Collectors.groupingBy(s -> s.getGoal().getId()));

        Map<Long, List<Long>> parentIds = new HashMap<>();
        Map<Long, List<LearningGoal>> children = new HashMap<>();
        for (GoalRelationship r : relationships) {
            Long child = r.getSource().getId();
            Long parent = r.getTarget().getId();
            if (r.getType() != RelationshipType.CONTRIBUTES_TO
                    || !byId.containsKey(child) || !byId.containsKey(parent)) {
                continue;
            }
            // Extraction and Topic tree synthesis can both store the same edge.
            List<Long> parents = parentIds.computeIfAbsent(child, k -> new ArrayList<>());
            if (!parents.contains(parent)) {
                parents.add(parent);
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(byId.get(child));
            }
        }

        // Insertion order is the row order; a null depth marks a goal outside the tree.
        Map<Long, Integer> depthById = new LinkedHashMap<>();
        exported.stream()
                .filter(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .sorted(LECTURE_ORDER)
                .forEach(topic -> walk(topic, 0, children, depthById));
        exported.stream()
                .filter(g -> !depthById.containsKey(g.getId()))
                .sorted(LECTURE_ORDER)
                .forEach(g -> depthById.put(g.getId(), null));

        writer.write(BOM);
        try (CSVWriter csv = new CSVWriter(writer)) {
            csv.writeNext(HEADER);
            for (Map.Entry<Long, Integer> e : depthById.entrySet()) {
                LearningGoal g = byId.get(e.getKey());
                csv.writeNext(neutralizeFormulas(toRow(
                        g,
                        e.getValue(),
                        parentIds.getOrDefault(g.getId(), List.of()),
                        sourcesByGoal.getOrDefault(g.getId(), List.of()))));
            }
            csv.flushQuietly();
        }
    }

    /** Depth-first, first visit wins: a goal with two parents is listed once, under the first. */
    private void walk(LearningGoal goal, int depth, Map<Long, List<LearningGoal>> children,
                      Map<Long, Integer> depthById) {
        if (depthById.containsKey(goal.getId())) {
            return;
        }
        depthById.put(goal.getId(), depth);
        children.getOrDefault(goal.getId(), List.of()).stream()
                .sorted(LECTURE_ORDER)
                .forEach(child -> walk(child, depth + 1, children, depthById));
    }

    private String[] toRow(LearningGoal goal, Integer depth, List<Long> parentIds,
                           List<GoalSource> goalSources) {
        HierarchyPath path = HierarchyPath.from(goal.getHierarchyNode());
        String sources = goalSources.stream()
                .map(s -> s.getPage() == null
                        ? s.getDocument().getFilename()
                        : s.getDocument().getFilename() + " p." + s.getPage())
                .collect(Collectors.joining("; "));
        String grounded = goalSources.isEmpty()
                ? ""
                : String.valueOf(goalSources.stream().allMatch(GoalSource::isGrounded));
        return new String[]{
                String.valueOf(goal.getId()),
                parentIds.stream().map(String::valueOf).collect(Collectors.joining("; ")),
                depth == null ? "" : String.valueOf(depth),
                nameOrEmpty(goal.getRole()),
                nameOrEmpty(goal.getOrigin()),
                nameOrEmpty(goal.getCreationProvenance()),
                goal.getText(),
                orEmpty(goal.getShortLabel()),
                nameOrEmpty(goal.getKind()),
                nameOrEmpty(goal.getBloomLevel()),
                nameOrEmpty(goal.getSoloLevel()),
                goal.getStatus().name(),
                orEmpty(path.module()),
                orEmpty(path.session()),
                orEmpty(path.exercise()),
                sources,
                grounded
        };
    }

    /**
     * Goal text, hierarchy labels and filenames come from uploads and model output. A spreadsheet
     * treats a cell that starts with one of these characters as a formula, so such a cell gets a
     * leading apostrophe and opens as plain text (OWASP's CSV-injection guidance).
     */
    static String[] neutralizeFormulas(String[] row) {
        String[] safe = new String[row.length];
        for (int i = 0; i < row.length; i++) {
            String cell = row[i];
            safe[i] = cell != null && !cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0
                    ? "'" + cell
                    : cell;
        }
        return safe;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nameOrEmpty(Enum<?> value) {
        return value == null ? "" : value.name();
    }
}
